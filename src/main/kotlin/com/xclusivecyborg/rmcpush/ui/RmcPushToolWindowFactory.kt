package com.xclusivecyborg.rmcpush.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.xclusivecyborg.rmcpush.session.RmcPushSession

/**
 * Builds the docked Firebase Push panel.
 *
 * The IDE calls this the first time the tool window is opened, so nothing
 * touches the network until the user actually asks for the panel.
 */
class RmcPushToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RmcPushPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(RefreshAction()))
    }

    private class RefreshAction : DumbAwareAction(
        "Reload Remote Config",
        "Re-fetch the template from Firebase",
        AllIcons.Actions.Refresh
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            RmcPushSession.getInstance(project).refresh()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = e.project != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    companion object {
        /** Must match the id of the toolWindow extension in plugin.xml. */
        const val TOOL_WINDOW_ID = "Firebase Push"

        /** Opens and focuses the panel. */
        fun activate(project: Project) {
            ToolWindowManager.getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID)
                ?.activate(null, true)
        }
    }
}
