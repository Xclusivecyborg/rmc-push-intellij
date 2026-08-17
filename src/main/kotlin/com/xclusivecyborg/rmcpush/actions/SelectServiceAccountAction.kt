package com.xclusivecyborg.rmcpush.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.xclusivecyborg.rmcpush.session.RmcPushSession
import com.xclusivecyborg.rmcpush.ui.RmcPushToolWindowFactory

/** Picks the service account JSON file, then shows the panel it connected. */
class SelectServiceAccountAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        RmcPushSession.getInstance(project).selectAccount()
        RmcPushToolWindowFactory.activate(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
