package com.xclusivecyborg.rmcpush.ui

import com.google.gson.GsonBuilder
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.xclusivecyborg.rmcpush.validation.parseStrictJson
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * The value field for one type: a switch for BOOLEAN, a monospace box for JSON,
 * a text box for STRING and a single line for NUMBER. Shared by the parameter
 * editor and the batch form. [onChange] fires on every edit.
 */
internal class ValueInput(private val onChange: () -> Unit) : JPanel(BorderLayout()) {

    private var control: JComponent = JBTextField()

    init {
        isOpaque = false
    }

    val value: String
        get() = when (val c = control) {
            is ToggleSwitch -> if (c.isOn) "true" else "false"
            is JTextComponent -> c.text
            else -> ""
        }

    /** Rebuilds the control for [type], starting from [text]. */
    fun set(type: String, text: String) {
        removeAll()
        val edits = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onChange()
        }
        control = when (type) {
            "BOOLEAN" -> ToggleSwitch(text.trim().lowercase() == "true").onToggle { onChange() }
            "JSON" -> JBTextArea(text, 8, 20).apply {
                lineWrap = true
                font = monospaceFont()
                document.addDocumentListener(edits)
            }

            "STRING" -> JBTextArea(text, 2, 20).apply {
                lineWrap = true
                wrapStyleWord = true
                document.addDocumentListener(edits)
            }

            else -> JBTextField(text).apply { document.addDocumentListener(edits) }
        }

        val c = control
        add(
            when (c) {
                // Shown as a field with the switch at its right edge, and the
                // value it stands for spelled out, as Firebase stores text.
                is ToggleSwitch -> RoundedPanel(BorderLayout(), arc = Radius.CONTROL).apply {
                    border = JBUI.Borders.empty(8, 12)
                    val label = JBLabel(if (c.isOn) "true" else "false")
                    c.onToggle { label.text = if (c.isOn) "true" else "false" }
                    add(label, BorderLayout.CENTER)
                    add(c, BorderLayout.EAST)
                }

                is JBTextArea -> JBScrollPane(c)
                else -> c
            },
            BorderLayout.CENTER
        )
        revalidate()
        repaint()
    }

    /** Changes the type, carrying the current text across so nothing typed is lost. */
    fun retype(type: String) = set(type, value)

    fun focusField() {
        control.requestFocusInWindow()
    }

    /** Pretty-prints JSON in place; invalid text is left for the inline error to explain. */
    fun formatJson() {
        val area = control as? JBTextArea ?: return
        try {
            area.text = PRETTY.toJson(parseStrictJson(area.text))
        } catch (_: Exception) {
            // The inline error already says why it does not parse.
        }
    }

    private companion object {
        val PRETTY = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    }
}
