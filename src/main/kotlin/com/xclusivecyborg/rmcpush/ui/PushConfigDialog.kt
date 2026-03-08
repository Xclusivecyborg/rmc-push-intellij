package com.xclusivecyborg.rmcpush.ui

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JTextField

private val KEY_REGEX = Regex("^[a-zA-Z0-9_]+$")

class PushConfigDialog(project: Project, projectId: String) : DialogWrapper(project) {

    private val keyField = JTextField()
    private val valueField = JTextField()
    private val typeCombo = ComboBox(arrayOf("STRING", "NUMBER", "BOOLEAN", "JSON"))
    private val groupField = JTextField()

    init {
        title = "Push to Remote Config · $projectId"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Key:") {
            cell(keyField).columns(35)
        }
        row("Value:") {
            cell(valueField).columns(35)
        }
        row("Type:") {
            cell(typeCombo)
        }
        row("Group (optional):") {
            cell(groupField).columns(35)
                .comment("Leave blank to push to root parameters")
        }
    }

    override fun doValidate(): ValidationInfo? {
        val key = keyField.text.trim()
        val value = valueField.text
        val type = typeCombo.selectedItem as String
        val group = groupField.text.trim()

        if (key.isEmpty()) return ValidationInfo("Key is required", keyField)
        if (!KEY_REGEX.matches(key)) return ValidationInfo(
            "Key must contain only letters, numbers, and underscores", keyField
        )
        if (value.isEmpty()) return ValidationInfo("Value is required", valueField)
        if (group.isNotEmpty() && !KEY_REGEX.matches(group)) return ValidationInfo(
            "Group must contain only letters, numbers, and underscores", groupField
        )

        when (type) {
            "NUMBER" -> if (value.toDoubleOrNull() == null) return ValidationInfo("Invalid number", valueField)
            "BOOLEAN" -> if (value.lowercase().trim() !in listOf("true", "false")) return ValidationInfo(
                "Boolean must be \"true\" or \"false\"", valueField
            )
            "JSON" -> try {
                JsonParser.parseString(value)
            } catch (e: Exception) {
                return ValidationInfo("Invalid JSON: ${e.message}", valueField)
            }
        }

        return null
    }

    fun getKey(): String = keyField.text.trim()

    fun getValue(): String {
        val raw = valueField.text
        return if ((typeCombo.selectedItem as String) == "BOOLEAN") raw.lowercase().trim() else raw
    }

    fun getType(): String = typeCombo.selectedItem as String

    fun getGroup(): String? = groupField.text.trim().takeIf { it.isNotEmpty() }
}
