package com.xclusivecyborg.rmcpush.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.xclusivecyborg.rmcpush.auth.ServiceAccount
import com.xclusivecyborg.rmcpush.auth.getAccessToken
import com.xclusivecyborg.rmcpush.auth.readServiceAccount
import com.xclusivecyborg.rmcpush.firebase.RemoteConfigClient
import com.xclusivecyborg.rmcpush.model.ViewState
import com.xclusivecyborg.rmcpush.model.toSections
import com.xclusivecyborg.rmcpush.settings.PluginSettings
import com.xclusivecyborg.rmcpush.validation.normalizeValue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns everything that has to outlive the tool window component.
 *
 * The IDE creates the panel when the tool window is first opened and is free to
 * drop it afterwards, so the service account, the access token and the fetched
 * template all live here instead. The panel subscribes, renders whatever state
 * it is handed, and sends user intent back.
 */
@Service(Service.Level.PROJECT)
class RmcPushSession(private val project: Project) : Disposable {

    private val listeners = CopyOnWriteArrayList<(ViewState) -> Unit>()

    /**
     * Discriminates responses from overlapping loads. A refresh started while
     * an earlier one is still in flight must not be overwritten by the older
     * reply landing second.
     */
    private val generation = AtomicInteger(0)

    // Written on the EDT, read from background tasks.
    @Volatile
    private var state: ViewState = ViewState.NoAccount

    /** Access token and its expiry, in Unix epoch seconds. */
    @Volatile
    private var token: Pair<String, Long>? = null

    fun getState(): ViewState = state

    /**
     * Subscribes until [parent] is disposed — normally the tool window's own
     * disposable, so a closed tool window stops receiving updates.
     */
    fun addListener(parent: Disposable, listener: (ViewState) -> Unit) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
    }

    /** Loads the configured account, or reports that there is not one yet. */
    fun connect() = load("Connecting to Firebase…")

    fun refresh() = load("Reloading Remote Config…")

    /**
     * Reloads without flipping the panel to a spinner first.
     *
     * Used after a push, where the editor is showing the result of the write —
     * a busy state would replace that message with a spinner and then restore
     * it a moment later.
     */
    private fun refreshQuietly() = load(busyMessage = null)

    /** Prompts for a service account file and connects to it. */
    fun selectAccount() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return

        PluginSettings.getInstance(project).state.serviceAccountPath = chosen.path
        // The cached token belongs to the previous account.
        token = null
        connect()
    }

    /** Forgets the configured account and returns to the connect prompt. */
    fun clearAccount() {
        PluginSettings.getInstance(project).state.serviceAccountPath = ""
        token = null
        generation.incrementAndGet()
        setState(ViewState.NoAccount)
    }

    /**
     * Writes one parameter. [onResult] is called on the EDT with null on
     * success or a message on failure.
     */
    fun push(key: String, value: String, valueType: String, group: String?, onResult: (String?) -> Unit) {
        val path = configuredPath()
        if (path.isEmpty()) {
            onResult("No service account selected.")
            return
        }
        val normalized = normalizeValue(value, valueType)

        object : Task.Backgroundable(project, "Pushing to Firebase Remote Config", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val account = readServiceAccount(path)
                    indicator.text = "Authenticating…"
                    val accessToken = accessTokenFor(account)

                    // Re-fetch rather than reuse the template the panel is
                    // showing: the ETag it came with may be minutes old, and a
                    // stale one turns a valid push into a 409.
                    indicator.text = "Fetching current template…"
                    val fetched = RemoteConfigClient.fetch(account.project_id, accessToken)

                    indicator.text = "Pushing…"
                    val merged = RemoteConfigClient.mergeParameter(
                        fetched.json, key.trim(), normalized, valueType, group?.trim()?.takeIf { it.isNotEmpty() }
                    )
                    RemoteConfigClient.push(account.project_id, accessToken, merged, fetched.etag)

                    onEdt {
                        onResult(null)
                        // Show what Firebase now holds, including this write.
                        refreshQuietly()
                    }
                } catch (ex: Exception) {
                    onEdt { onResult(ex.message ?: "Unknown error") }
                }
            }
        }.queue()
    }

    override fun dispose() {
        listeners.clear()
    }

    // ---------- internals ----------

    private fun configuredPath(): String =
        PluginSettings.getInstance(project).state.serviceAccountPath.trim()

    /** [busyMessage] of null reloads in the background without a spinner. */
    private fun load(busyMessage: String?) {
        val path = configuredPath()
        val current = generation.incrementAndGet()

        if (path.isEmpty()) {
            setState(ViewState.NoAccount)
            return
        }
        if (busyMessage != null) {
            setState(ViewState.Busy(busyMessage))
        }

        object : Task.Backgroundable(project, "Loading Firebase Remote Config", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Reading service account…"
                    val account = readServiceAccount(path)

                    indicator.text = "Authenticating…"
                    val accessToken = accessTokenFor(account)

                    indicator.text = "Fetching Remote Config…"
                    val fetched = RemoteConfigClient.fetch(account.project_id, accessToken)

                    publish(current) {
                        setState(
                            ViewState.Ready(
                                projectId = account.project_id,
                                accountPath = path,
                                sections = toSections(fetched.json)
                            )
                        )
                    }
                } catch (ex: Exception) {
                    publish(current) {
                        setState(ViewState.Error(ex.message ?: "Unknown error", hasAccount = true))
                    }
                }
            }
        }.queue()
    }

    /** Reuses the cached token until a minute before it expires. */
    private fun accessTokenFor(account: ServiceAccount): String {
        val now = System.currentTimeMillis() / 1000
        token?.let { (value, expiresAt) ->
            if (now < expiresAt - 60) return value
        }
        val fresh = getAccessToken(account)
        token = fresh
        return fresh.first
    }

    private fun setState(next: ViewState) {
        state = next
        listeners.forEach { it(next) }
    }

    /** Runs on the EDT unless a newer load has already superseded this one. */
    private fun publish(generationAtStart: Int, action: () -> Unit) {
        onEdt {
            if (generation.get() == generationAtStart) {
                action()
            }
        }
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(
            { if (!project.isDisposed) action() },
            ModalityState.nonModal()
        )
    }

    companion object {
        fun getInstance(project: Project): RmcPushSession =
            project.getService(RmcPushSession::class.java)
    }
}
