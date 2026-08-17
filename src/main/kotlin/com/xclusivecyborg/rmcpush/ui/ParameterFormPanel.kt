package com.xclusivecyborg.rmcpush.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.xclusivecyborg.rmcpush.model.ConfigEntry
import com.xclusivecyborg.rmcpush.session.RmcPushSession
import com.xclusivecyborg.rmcpush.validation.Field
import com.xclusivecyborg.rmcpush.validation.VALUE_TYPES
import com.xclusivecyborg.rmcpush.validation.validatePush
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Create or edit one parameter.
 *
 * [existing] is null when creating. When editing, the key and group are fixed:
 * changing either would write a second parameter rather than move the original,
 * which is a rename Firebase does not offer.
 */
class ParameterFormPanel(
    private val session: RmcPushSession,
    val existing: ConfigEntry?,
    private val onBack: () -> Unit
) : JPanel(BorderLayout()) {

    private val isEdit = existing != null

    private val keyField = JBTextField(existing?.key ?: "").apply {
        isEditable = !isEdit
        emptyText.text = "e.g. welcome_title"
    }
    private val groupField = JBTextField(existing?.group ?: "").apply {
        isEditable = !isEdit
        emptyText.text = "Leave blank for root parameters"
    }
    private val typeCombo = ComboBox(VALUE_TYPES.toTypedArray()).apply {
        selectedItem = existing?.valueType ?: "STRING"
    }

    private val valueHost = JPanel(BorderLayout())
    private var valueControl: JComponent = JBTextField(existing?.value ?: "")

    private val statusLabel = JBLabel("")
    private val submitButton = JButton(if (isEdit) "Save to Firebase" else "Push to Firebase")

    init {
        swapValueControl(typeCombo.selectedItem as String)
        typeCombo.addActionListener { swapValueControl(typeCombo.selectedItem as String) }
        submitButton.addActionListener { submit() }

        val title = JBLabel(if (isEdit) existing!!.key else "New parameter").apply {
            font = font.deriveFont(Font.BOLD)
        }

        val form = panel {
            row { cell(ActionLink("‹ Back") { onBack() }) }
            row { cell(title) }
            row("Key:") {
                cell(keyField).align(AlignX.FILL).apply {
                    if (isEdit) comment("Keys cannot be renamed — create a new parameter instead.")
                }
            }
            row("Type:") { cell(typeCombo) }
            row("Value:") { cell(valueHost).align(AlignX.FILL) }
            row("Parameter group:") { cell(groupField).align(AlignX.FILL) }
            row { cell(submitButton).align(AlignX.FILL) }
            row { cell(statusLabel).align(AlignX.FILL) }
        }.apply {
            border = JBUI.Borders.empty(8)
        }

        add(JBScrollPane(form), BorderLayout.CENTER)
    }

    /**
     * Rebuilds the value control for [type], carrying the current text across so
     * flipping the type by accident does not discard what was typed.
     */
    private fun swapValueControl(type: String) {
        val carried = currentValue()
        valueHost.removeAll()

        valueControl = when (type) {
            "JSON" -> JBTextArea(carried, 4, 20).apply { lineWrap = true }
            "BOOLEAN" -> ComboBox(arrayOf("true", "false")).apply {
                selectedItem = if (carried.lowercase().trim() == "true") "true" else "false"
            }

            else -> JBTextField(carried)
        }

        valueHost.add(
            if (valueControl is JBTextArea) JBScrollPane(valueControl) else valueControl,
            BorderLayout.CENTER
        )
        valueHost.revalidate()
        valueHost.repaint()
    }

    private fun currentValue(): String = when (val control = valueControl) {
        is JBTextArea -> control.text
        is ComboBox<*> -> control.selectedItem as? String ?: ""
        is JBTextField -> control.text
        else -> ""
    }

    private fun submit() {
        val key = keyField.text.trim()
        val group = groupField.text.trim().takeIf { it.isNotEmpty() }
        val type = typeCombo.selectedItem as String
        val value = currentValue()

        // Validated here for instant feedback; the session re-normalises before
        // anything reaches Firebase.
        val error = validatePush(key, value, type, group)
        if (error != null) {
            showStatus(error.message, ERROR_COLOR)
            focusFieldFor(error.field)
            return
        }

        submitButton.isEnabled = false
        showStatus("Pushing…", JBColor.GRAY)
        session.push(key, value, type, group) { failure ->
            submitButton.isEnabled = true
            if (failure == null) {
                val target = group?.let { "group \"$it\"" } ?: "root parameters"
                showStatus("Pushed \"$key\" to $target.", SUCCESS_COLOR)
            } else {
                showStatus(failure, ERROR_COLOR)
            }
        }
    }

    private fun showStatus(message: String, color: Color) {
        statusLabel.text = message
        statusLabel.foreground = color
    }

    private fun focusFieldFor(field: Field) {
        when (field) {
            Field.KEY -> keyField
            Field.GROUP -> groupField
            Field.VALUE -> valueControl
        }.requestFocusInWindow()
    }

    private companion object {
        val ERROR_COLOR: Color = JBColor.namedColor("Label.errorForeground", JBColor.RED)
        val SUCCESS_COLOR: Color = JBColor.namedColor(
            "Label.successForeground",
            JBColor(Color(0x1E7D32), Color(0x6A8759))
        )
    }
}
