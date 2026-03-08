package com.xclusivecyborg.rmcpush.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class PluginSettingsConfigurable(private val project: Project) : Configurable {

    private val serviceAccountPathField = TextFieldWithBrowseButton()

    override fun getDisplayName(): String = "Firebase Push"

    override fun createComponent(): JComponent {
        serviceAccountPathField.addBrowseFolderListener(
            "Select Service Account JSON",
            "Select your Firebase service account JSON file",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        )
        return panel {
            row("Service account JSON:") {
                cell(serviceAccountPathField).resizableColumn()
            }
            row {
                comment("Path to your Firebase service account JSON file. Stored per project in .idea/rmcPush.xml.")
            }
        }
    }

    override fun isModified(): Boolean =
        serviceAccountPathField.text != PluginSettings.getInstance(project).state.serviceAccountPath

    override fun apply() {
        PluginSettings.getInstance(project).state.serviceAccountPath = serviceAccountPathField.text
    }

    override fun reset() {
        serviceAccountPathField.text = PluginSettings.getInstance(project).state.serviceAccountPath
    }
}
