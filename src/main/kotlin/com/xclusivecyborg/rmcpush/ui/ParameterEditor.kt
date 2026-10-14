package com.xclusivecyborg.rmcpush.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.xclusivecyborg.rmcpush.firebase.RemoteConfigClient
import com.xclusivecyborg.rmcpush.model.ParameterChange
import com.xclusivecyborg.rmcpush.session.RmcPushSession
import com.xclusivecyborg.rmcpush.validation.Field
import com.xclusivecyborg.rmcpush.validation.VALUE_TYPES
import com.xclusivecyborg.rmcpush.validation.ValidationError
import com.xclusivecyborg.rmcpush.validation.normalizeValue
import com.xclusivecyborg.rmcpush.validation.validateNewKey
import com.xclusivecyborg.rmcpush.validation.validatePush
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/** What the editor needs from the screen around it. */
internal interface EditorHost {
    /** Every key in the template or the batch; a new parameter may not reuse one. */
    fun takenKeys(): Set<String>

    /** Existing parameter group names, offered when creating. */
    fun groupNames(): List<String>

    /**
     * Selects [key] once it appears in the list and shows [message] in its
     * editor. Used after a new parameter is pushed or staged, when the
     * parameter only exists after the next update.
     */
    fun reveal(key: String, message: String)

    /** Closes this editor: nothing selected, the list on its own. */
    fun closeEditor()
}

/**
 * Shows and edits one parameter, or creates one when [initial] is null.
 *
 * The key and group of an existing parameter are fixed: changing either would
 * write a second parameter rather than move the original, which is a rename
 * Firebase does not offer.
 *
 * Lives for as long as one parameter is selected. Updates to that parameter —
 * after a push, or when the batch changes — arrive through [sync], which moves
 * the baseline without touching what the user has typed.
 */
internal class ParameterEditor(
    private val session: RmcPushSession,
    initial: ParamItem?,
    private val host: EditorHost
) : JPanel(BorderLayout()) {

    private var item: ParamItem? = initial
    private val isCreate = initial == null

    val key: String? get() = item?.key

    // ---- create-only fields
    private val keyField = JBTextField().apply { emptyText.text = "e.g. enable_new_home" }
    private val keyError = errorLabel()
    private val groupCombo = ComboBox((listOf("") + host.groupNames()).toTypedArray()).apply {
        isEditable = true
        selectedItem = ""
    }
    private val groupError = errorLabel()
    private val typeChips = LinkedHashMap<String, Chip>()

    // ---- value
    private var valueType: String = initial?.valueType ?: "STRING"
    private val valueInput = ValueInput { refresh() }
    private val valueError = errorLabel()
    private val typeBadge = Badge(typeLabel(valueType), dropdown = true)
    private val revertLink = ActionLink("Revert") { revert() }
    private val formatLink = ActionLink("Format") { formatJson() }

    // ---- preview
    private val preview = JTextPane().apply {
        isEditable = false
        isOpaque = false
        font = monospaceFont()
        border = JBUI.Borders.empty()
    }

    // ---- header pieces that change with the batch
    private val stagedNote = JBLabel().apply {
        font = JBFont.small()
        foreground = Palette.ACCENT_TEXT
    }

    // ---- actions
    private val pushButton = AccentButton("Push to Firebase", AllIcons.Vcs.Push)
    private val batchButton = OutlineButton("Add to batch")
    private val status = JBLabel().apply {
        font = JBFont.small()
        setCopyable(true)
    }

    /** True once the user has tried to submit; from then on every error shows. */
    private var attempted = false
    private var busy = false

    private enum class BatchAction(val label: String) {
        ADD("Add to batch"), UPDATE("Update in batch"), REMOVE("Remove from batch")
    }

    private var batchAction = BatchAction.ADD

    init {
        isOpaque = false

        val content = JPanel(VerticalLayout(JBUI.scale(14))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(14, 14, 8, 14)
            add(header())
            if (isCreate) add(createFields())
            add(valueSection())
            add(previewSection())
        }

        add(JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }, BorderLayout.CENTER)
        add(actions(), BorderLayout.SOUTH)

        // The New parameter page has its own Back; the details panel needs one
        // too, to close it and return to the list alone.
        if (!isCreate) {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(Palette.OUTLINE, 0, 0, 1, 0),
                    JBUI.Borders.empty(6, 10)
                )
                add(ActionLink("Back") { host.closeEditor() }.apply {
                    icon = AllIcons.Actions.Back
                    toolTipText = "Close and return to the list"
                }, BorderLayout.WEST)
            }, BorderLayout.NORTH)
        }

        valueInput.set(valueType, initial?.value ?: "")
        typeBadge.addClickListener { chooseType() }
        pushButton.addClickListener { push() }
        batchButton.addClickListener { toggleBatch() }

        keyField.document.addDocumentListener(onEdit())
        (groupCombo.editor.editorComponent as? JTextComponent)?.document?.addDocumentListener(onEdit())
        groupCombo.addActionListener { refresh() }

        refresh()
    }

    /**
     * Takes a newer copy of the same parameter. The typed value is kept: a
     * refresh landing mid-edit must not throw the user's work away.
     */
    fun sync(updated: ParamItem) {
        item = updated
        refresh()
    }

    fun showStatus(message: String, color: Color) {
        status.text = message
        status.foreground = color
    }

    fun focusFirstField() {
        if (isCreate) keyField.requestFocusInWindow() else valueInput.focusField()
    }

    // ---------- layout ----------

    private fun header(): JComponent {
        val panel = JPanel(VerticalLayout(JBUI.scale(4))).apply { isOpaque = false }
        val current = item
        if (current == null) {
            panel.add(JBLabel("New parameter").apply { font = JBFont.h3().asBold() })
            panel.add(muted("Fill in the details, then push it now or add it to the batch."))
            return panel
        }

        panel.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(JBLabel(current.key).apply {
                font = JBFont.h3().asBold()
                setCopyable(true)
                border = JBUI.Borders.emptyRight(8)
            })
            add(typeBadge)
        })

        val remote = current.remote
        remote?.description?.let { panel.add(muted(it)) }
        panel.add(muted(current.group?.let { "In group \"$it\"" } ?: "Root parameter"))
        if (remote != null && remote.conditionCount > 0) {
            panel.add(muted("${remote.conditionCount} conditional value(s) — kept as they are; edit them in the Firebase console."))
        }
        if (remote?.usesInAppDefault == true) {
            panel.add(muted("Currently uses the in-app default. Pushing sets an explicit default value."))
        }
        panel.add(stagedNote)
        return panel
    }

    private fun createFields(): JComponent = JPanel(VerticalLayout(JBUI.scale(4))).apply {
        isOpaque = false
        add(sectionLabel("Key"))
        add(keyField)
        add(keyError)
        add(sectionLabel("Group").apply { border = JBUI.Borders.emptyTop(6) })
        add(groupCombo)
        add(muted("Leave blank for a root parameter."))
        add(groupError)
        add(sectionLabel("Type").apply { border = JBUI.Borders.emptyTop(6) })
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(-6)
            VALUE_TYPES.forEach { type ->
                val chip = Chip(typeLabel(type)).onClick { setType(type) }
                typeChips[type] = chip
                add(chip)
            }
        })
    }

    private fun valueSection(): JComponent = JPanel(VerticalLayout(JBUI.scale(6))).apply {
        isOpaque = false
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(sectionLabel("Value"), BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
                isOpaque = false
                add(formatLink)
                add(revertLink)
            }, BorderLayout.EAST)
        })
        add(valueInput)
        add(valueError)
    }

    private fun previewSection(): JComponent = JPanel(VerticalLayout(JBUI.scale(6))).apply {
        isOpaque = false
        add(sectionLabel("JSON Preview"))
        add(RoundedPanel(BorderLayout(), fill = { Palette.SURFACE_SUNKEN }).apply {
            border = JBUI.Borders.empty(10, 12)
            add(preview, BorderLayout.CENTER)
        })
        add(muted("The default value written to the template. Other fields on the parameter are kept."))
    }

    private fun actions(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(Palette.OUTLINE, 1, 0, 0, 0),
            JBUI.Borders.empty(10, 14, 12, 14)
        )
        add(status, BorderLayout.NORTH)
        add(JPanel(GridLayout(2, 1, 0, JBUI.scale(6))).apply {
            isOpaque = false
            add(pushButton)
            add(batchButton)
        }, BorderLayout.CENTER)
    }

    // ---------- value control ----------

    private fun currentValue(): String = valueInput.value

    private fun setType(type: String) {
        if (type == valueType) return
        valueType = type
        typeBadge.text = typeLabel(type)
        valueInput.retype(type)
        refresh()
    }

    private fun chooseType() {
        val labels = VALUE_TYPES.map(::typeLabel)
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels)
            .setSelectedValue(typeLabel(valueType), true)
            .setItemChosenCallback { label -> setType(VALUE_TYPES[labels.indexOf(label)]) }
            .createPopup()
            .showUnderneathOf(typeBadge)
    }

    private fun revert() {
        val remote = item?.remote ?: return
        valueType = remote.valueType
        typeBadge.text = typeLabel(valueType)
        valueInput.set(valueType, remote.value)
        refresh()
    }

    private fun formatJson() {
        valueInput.formatJson()
        refresh()
    }

    // ---------- state ----------

    private fun currentKey(): String = item?.key ?: keyField.text.trim()

    private fun currentGroup(): String? = if (isCreate) {
        val typed = (groupCombo.editor.editorComponent as? JTextComponent)?.text ?: groupCombo.selectedItem as? String
        typed?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        item?.group
    }

    private fun currentChange() = ParameterChange(
        key = currentKey(),
        value = normalizeValue(currentValue(), valueType),
        valueType = valueType,
        group = currentGroup(),
        isNew = item?.isNew ?: true
    )

    private fun firstError(): ValidationError? {
        val change = currentChange()
        val invalid = validatePush(change.key, currentValue(), change.valueType, change.group)
        // A taken key is a key problem, so it outranks anything wrong further
        // down the form — otherwise it stays hidden until a value is typed.
        if (isCreate && invalid?.field != Field.KEY) {
            validateNewKey(change.key, host.takenKeys())?.let { return it }
        }
        return invalid
    }

    /** True when the editor differs from what Firebase holds — or the parameter is not in Firebase yet. */
    private fun differsFromRemote(): Boolean {
        val remote = item?.remote ?: return true
        val change = currentChange()
        return change.valueType != remote.valueType || change.value != remote.value
    }

    private fun onEdit() = object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = refresh()
    }

    /** Recomputes everything derived from the inputs: errors, preview, buttons. */
    private fun refresh() {
        val error = firstError()
        val change = currentChange()

        keyError.text = ""
        groupError.text = ""
        valueError.text = ""
        if (error != null) {
            val target = when (error.field) {
                Field.KEY -> keyError
                Field.GROUP -> groupError
                Field.VALUE -> valueError
            }
            // Before a first submit, stay quiet about fields still empty —
            // "Key is required" on a blank form is noise, not help.
            val fieldIsEmpty = when (error.field) {
                Field.KEY -> change.key.isEmpty()
                Field.GROUP -> change.group == null
                Field.VALUE -> currentValue().isEmpty()
            }
            if (attempted || !fieldIsEmpty) target.text = error.message
        }

        typeChips.forEach { (type, chip) -> chip.selected = type == valueType }
        formatLink.isVisible = valueType == "JSON"
        revertLink.isVisible = item?.remote != null && differsFromRemote()

        highlightJson(
            preview.styledDocument,
            PRETTY.toJson(
                RemoteConfigClient.mergeParameter(
                    JsonObject(),
                    change.key.ifEmpty { "parameter_key" },
                    change.value,
                    change.valueType,
                    change.group
                )
            )
        )

        val staged = item?.staged
        stagedNote.text = when {
            staged == null -> ""
            item?.isNew == true -> "● New — waiting in the batch"
            else -> "● Staged — waiting in the batch"
        }
        stagedNote.isVisible = staged != null

        val dirty = differsFromRemote()
        pushButton.isEnabled = !busy && dirty
        pushButton.toolTipText = if (dirty) null else "No changes to push"

        val matchesStaged = staged != null && staged == change.copy(isNew = staged.isNew)
        batchAction = when {
            staged != null && (matchesStaged || !dirty) -> BatchAction.REMOVE
            staged != null -> BatchAction.UPDATE
            else -> BatchAction.ADD
        }
        batchButton.text = batchAction.label
        batchButton.isEnabled = !busy && (staged != null || dirty)
    }

    // ---------- actions ----------

    private fun push() {
        if (!checkValid()) return
        val change = currentChange()
        busy = true
        refresh()
        showStatus("Pushing…", Palette.MUTED)
        session.push(change) { failure ->
            busy = false
            refresh()
            if (failure == null) {
                val where = change.group?.let { "group \"$it\"" } ?: "root parameters"
                val message = "Pushed \"${change.key}\" to $where."
                if (isCreate) host.reveal(change.key, message)
                showStatus(message, Palette.SUCCESS)
            } else {
                showStatus(failure, Palette.ERROR)
            }
        }
    }

    private fun toggleBatch() {
        val staged = item?.staged
        val change = currentChange()
        if (staged != null && batchAction == BatchAction.REMOVE) {
            session.unstage(staged.key)
            showStatus("Removed \"${staged.key}\" from the batch.", Palette.MUTED)
            return
        }
        if (!checkValid()) return
        if (isCreate) {
            // The new parameter is listed (and gets its own editor) once staged.
            host.reveal(change.key, "Added \"${change.key}\" to the batch.")
            session.stage(change)
        } else {
            session.stage(change)
            showStatus("${if (staged == null) "Added" else "Updated"} \"${change.key}\" in the batch.", Palette.MUTED)
        }
    }

    /** Shows every error and focuses the first bad field. */
    private fun checkValid(): Boolean {
        attempted = true
        refresh()
        val error = firstError() ?: return true
        showStatus(error.message, Palette.ERROR)
        when (error.field) {
            Field.KEY -> keyField.requestFocusInWindow()
            Field.GROUP -> groupCombo.requestFocusInWindow()
            Field.VALUE -> valueInput.focusField()
        }
        return false
    }

    // ---------- bits ----------

    private fun sectionLabel(text: String) = JBLabel(text).apply { font = JBFont.label().asBold() }

    private fun muted(text: String) = JBLabel("<html>${escapeHtml(text)}</html>").apply {
        font = JBFont.small()
        foreground = Palette.MUTED
    }

    private fun errorLabel() = JBLabel().apply {
        font = JBFont.small()
        foreground = Palette.ERROR
    }

    private companion object {
        val PRETTY = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    }
}

/** For text rendered as HTML (to wrap it), which must not carry markup. */
internal fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
