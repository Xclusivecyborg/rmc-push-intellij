package com.xclusivecyborg.rmcpush.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.xclusivecyborg.rmcpush.auth.ServiceAccount
import com.xclusivecyborg.rmcpush.auth.getAccessToken
import com.xclusivecyborg.rmcpush.auth.readServiceAccount
import com.xclusivecyborg.rmcpush.firebase.RemoteConfigClient
import com.xclusivecyborg.rmcpush.firebase.RemoteConfigTemplate
import com.xclusivecyborg.rmcpush.settings.PluginSettings
import com.xclusivecyborg.rmcpush.ui.PushConfigDialog

class PushRemoteConfigAction : AnAction() {

    // Per-instance token cache
    private var tokenCache: Pair<String, Long>? = null

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = PluginSettings.getInstance(project)

        // 1. Resolve service account path
        if (settings.state.serviceAccountPath.isEmpty()) {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            val chosen = FileChooser.chooseFile(descriptor, project, null)
            if (chosen == null) {
                Messages.showWarningDialog(project, "No service account file selected.", "Firebase Push")
                return
            }
            settings.state.serviceAccountPath = chosen.path
        }

        // 2. Read and validate service account
        val serviceAccount: ServiceAccount = try {
            readServiceAccount(settings.state.serviceAccountPath)
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, ex.message ?: "Failed to read service account.", "Firebase Push")
            return
        }

        // 3 & 4. Authenticate + verify connectivity in background
        var accessToken: String? = null
        var template: RemoteConfigTemplate? = null
        var etag: String? = null
        var connectError: String? = null

        ProgressManager.getInstance().run(object : Task.Modal(project, "Connecting to Firebase...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Authenticating..."
                    val now = System.currentTimeMillis() / 1000
                    val cache = tokenCache
                    accessToken = if (cache != null && now < cache.second - 60) {
                        cache.first
                    } else {
                        val (token, expiresAt) = getAccessToken(serviceAccount)
                        tokenCache = Pair(token, expiresAt)
                        token
                    }

                    indicator.text = "Fetching Remote Config..."
                    val (t, e) = RemoteConfigClient.fetch(serviceAccount.project_id, accessToken!!)
                    template = t
                    etag = e
                } catch (ex: Exception) {
                    connectError = ex.message ?: "Unknown error"
                }
            }
        })

        if (connectError != null) {
            Messages.showErrorDialog(project, connectError, "Firebase Push")
            return
        }

        // 5. Show push dialog
        val dialog = PushConfigDialog(project, serviceAccount.project_id)
        if (!dialog.showAndGet()) return

        // 6. Merge and push in background
        var pushError: String? = null
        ProgressManager.getInstance().run(object : Task.Modal(project, "Pushing to Firebase...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val updated = RemoteConfigClient.mergeParameter(
                        template!!,
                        dialog.getKey(),
                        dialog.getValue(),
                        dialog.getType(),
                        dialog.getGroup()
                    )
                    RemoteConfigClient.push(serviceAccount.project_id, accessToken!!, updated, etag!!)
                } catch (ex: Exception) {
                    pushError = ex.message ?: "Unknown error"
                }
            }
        })

        if (pushError != null) {
            Messages.showErrorDialog(project, pushError, "Firebase Push")
        } else {
            val location = dialog.getGroup()?.let { "group \"$it\"" } ?: "root parameters"
            Messages.showInfoMessage(
                project,
                "Successfully pushed \"${dialog.getKey()}\" to $location.",
                "Firebase Push"
            )
        }
    }
}
