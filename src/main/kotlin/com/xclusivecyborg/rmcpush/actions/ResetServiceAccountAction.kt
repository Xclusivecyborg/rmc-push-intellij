package com.xclusivecyborg.rmcpush.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.xclusivecyborg.rmcpush.settings.PluginSettings

class ResetServiceAccountAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PluginSettings.getInstance(project).state.serviceAccountPath = ""
        Messages.showInfoMessage(project, "Service account path has been reset for this project.", "Firebase Push")
    }
}
