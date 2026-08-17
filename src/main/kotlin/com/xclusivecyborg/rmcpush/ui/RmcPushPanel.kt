package com.xclusivecyborg.rmcpush.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.xclusivecyborg.rmcpush.model.ConfigEntry
import com.xclusivecyborg.rmcpush.model.ViewState
import com.xclusivecyborg.rmcpush.session.RmcPushSession
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The tool window's content: a pure function of the session's [ViewState].
 *
 * Holds only navigation — which screen is showing — so the IDE can drop and
 * rebuild this component without losing the connection behind it.
 */
class RmcPushPanel(project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private val session = RmcPushSession.getInstance(project)

    private val listPanel = ParameterListPanel(
        onCreate = { openForm(null) },
        onOpen = { entry -> openForm(entry) }
    )

    private val spinner = AsyncProcessIcon("rmcPushLoading")

    /** Non-null while the editor is open; null means the list is showing. */
    private var form: ParameterFormPanel? = null

    init {
        Disposer.register(parent, spinner)
        session.addListener(parent) { state -> render(state) }
        render(session.getState())

        // First open of the tool window. If a previous panel already connected,
        // the session is past NoAccount and there is nothing to redo.
        if (session.getState() is ViewState.NoAccount) {
            session.connect()
        }
    }

    private fun render(state: ViewState) {
        removeAll()
        when (state) {
            is ViewState.NoAccount -> {
                form = null
                add(
                    placeholder(
                        "Select a Firebase service account JSON file to connect this project " +
                            "to a Remote Config project.",
                        primaryText = "Select service account…",
                        primaryAction = { session.selectAccount() }
                    ),
                    BorderLayout.CENTER
                )
            }

            is ViewState.Busy -> add(busyPane(state.message), BorderLayout.CENTER)

            is ViewState.Error -> add(
                placeholder(
                    state.message,
                    primaryText = "Retry",
                    primaryAction = { session.refresh() },
                    secondaryText = if (state.hasAccount) "Use a different service account"
                    else "Select a service account",
                    secondaryAction = { session.selectAccount() },
                    messageColor = JBColor.namedColor("Label.errorForeground", JBColor.RED)
                ),
                BorderLayout.CENTER
            )

            is ViewState.Ready -> renderReady(state)
        }
        revalidate()
        repaint()
    }

    private fun renderReady(state: ViewState.Ready) {
        add(header(state), BorderLayout.NORTH)

        // A parameter open in the editor may have been removed or renamed
        // upstream; drop back to the list rather than editing a ghost.
        val editing = form?.existing
        if (editing != null && state.sections.none { section ->
                section.entries.any { it.key == editing.key && it.group == editing.group }
            }
        ) {
            form = null
        }

        val openForm = form
        if (openForm != null) {
            add(openForm, BorderLayout.CENTER)
        } else {
            listPanel.setSections(state.sections)
            add(listPanel, BorderLayout.CENTER)
        }
    }

    private fun openForm(entry: ConfigEntry?) {
        form = ParameterFormPanel(session, entry) {
            form = null
            render(session.getState())
        }
        render(session.getState())
    }

    private fun header(state: ViewState.Ready): JComponent {
        val project = JBLabel(state.projectId, AllIcons.General.InspectionsOK, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.BOLD)
            toolTipText = state.accountPath
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(6, 8)
            )
            add(project, BorderLayout.CENTER)
            add(ActionLink("Change") { session.selectAccount() }, BorderLayout.EAST)
        }
    }

    private fun busyPane(message: String): JComponent = centered(
        listOf(
            spinner.apply { alignmentX = Component.CENTER_ALIGNMENT },
            Box.createVerticalStrut(8),
            JBLabel(message).apply { alignmentX = Component.CENTER_ALIGNMENT }
        )
    )

    private fun placeholder(
        message: String,
        primaryText: String,
        primaryAction: () -> Unit,
        secondaryText: String? = null,
        secondaryAction: (() -> Unit)? = null,
        messageColor: java.awt.Color? = null
    ): JComponent {
        val rows = mutableListOf<Component>(
            // JBLabel wraps only when told to; API errors can be long.
            JBLabel("<html><body style='text-align:center'>${escapeHtml(message)}</body></html>").apply {
                alignmentX = Component.CENTER_ALIGNMENT
                messageColor?.let { foreground = it }
            },
            Box.createVerticalStrut(12),
            JButton(primaryText).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                addActionListener { primaryAction() }
            }
        )
        if (secondaryText != null && secondaryAction != null) {
            rows += Box.createVerticalStrut(10)
            rows += ActionLink(secondaryText) { secondaryAction() }.apply {
                alignmentX = Component.CENTER_ALIGNMENT
            }
        }
        return centered(rows)
    }

    private fun centered(rows: List<Component>): JComponent {
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(20, 16)
            rows.forEach { add(it) }
        }
        // GridBagLayout with no constraints centres its single child.
        return JPanel(GridBagLayout()).apply { add(column) }
    }

    /** The message is rendered as HTML to wrap it, so it must not carry markup. */
    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
