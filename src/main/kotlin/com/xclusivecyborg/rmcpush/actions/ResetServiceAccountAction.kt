package com.xclusivecyborg.rmcpush.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.xclusivecyborg.rmcpush.session.RmcPushSession

class ResetServiceAccountAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Goes through the session rather than the settings directly, so the
        // panel drops back to the connect prompt and the cached token is binned.
        RmcPushSession.getInstance(project).clearAccount()
        Messages.showInfoMessage(project, "Service account path has been reset for this project.", "Firebase Push")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
