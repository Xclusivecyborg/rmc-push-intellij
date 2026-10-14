package com.xclusivecyborg.rmcpush.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.xclusivecyborg.rmcpush.model.ConfigEntry
import com.xclusivecyborg.rmcpush.model.ImportResult
import com.xclusivecyborg.rmcpush.model.ParameterChange
import com.xclusivecyborg.rmcpush.model.parseBatchJson
import com.xclusivecyborg.rmcpush.session.RmcPushSession
import com.xclusivecyborg.rmcpush.validation.Field
import com.xclusivecyborg.rmcpush.validation.VALUE_TYPES
import com.xclusivecyborg.rmcpush.validation.normalizeValue
import com.xclusivecyborg.rmcpush.validation.validatePush
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/** What the Batch Push view needs from the screen around it. */
internal interface ComposerHost {
    /** Every parameter Firebase holds, to tell new keys from updates. */
    fun remoteEntries(): List<ConfigEntry>

    fun groupNames(): List<String>

    /** Opens [key] in the parameter editor. */
    fun open(key: String)
}

/**
 * The Batch Push view: a target group, then either one entry at a time or a
 * JSON import, and the list of what is in the batch so far. Nothing is sent
 * from here — the batch bar's Review & push does that.
 *
 * Kept once built, so leaving it to look at a parameter and coming back does
 * not lose a half-typed entry or import. Updates arrive through [sync].
 */
internal class BatchComposer(
    private val project: Project,
    private val session: RmcPushSession,
    private val host: ComposerHost
) : JPanel(BorderLayout()) {

    private enum class Tab { SINGLE, JSON }

    private var tab = Tab.SINGLE
    private var valueType = "STRING"

    /** True once Add was tried; from then on every error shows. */
    private var attempted = false

    // ---- target group
    private val groupCombo = ComboBox<String>().apply { isEditable = true }
    private val groupEditor get() = groupCombo.editor.editorComponent as? JTextComponent

    // ---- tabs
    private val singleTab = Chip("Add one by one").onClick { setTab(Tab.SINGLE) }
    private val jsonTab = Chip("Import JSON").onClick { setTab(Tab.JSON) }

    // ---- one by one
    private val keyField = JBTextField().apply { emptyText.text = "e.g. enable_new_home" }
    private val keyNote = note()
    private val keyError = errorLabel()
    private val typeChips = LinkedHashMap<String, Chip>()
    private val valueInput = ValueInput { refreshSingle() }
    private val valueError = errorLabel()
    private val addButton = AccentButton("Add to batch", AllIcons.General.Add).onClick { addSingle() }
    private val singlePane = JPanel(VerticalLayout(JBUI.scale(6))).apply { isOpaque = false }

    // ---- import JSON
    private val jsonInput = JBTextArea(9, 20).apply {
        font = monospaceFont()
        lineWrap = true
        emptyText.text = "{ \"enable_new_home\": true, \"app_min_version\": 2.4 }"
    }
    private val fileName = JBLabel().apply {
        font = JBFont.small()
        foreground = Palette.MUTED
    }
    private val previewPanel = JPanel(VerticalLayout(JBUI.scale(6))).apply { isOpaque = false }
    private val importButton = AccentButton("Add to batch", AllIcons.General.Add).onClick { addImported() }
    private val jsonPane = JPanel(VerticalLayout(JBUI.scale(6))).apply { isOpaque = false }
    private var preview: ImportResult? = null

    /** Re-parses shortly after typing stops, so a large paste is parsed once. */
    private val previewTimer = Timer(200) { refreshPreview() }.apply { isRepeats = false }

    // ---- status and batch contents
    private val status = JBLabel().apply {
        font = JBFont.small()
        setCopyable(true)
    }
    private val stagedTitle = JBLabel().apply { font = JBFont.label().asBold() }
    private val stagedList = JPanel(VerticalLayout(JBUI.scale(6))).apply { isOpaque = false }

    init {
        isOpaque = false

        singlePane.apply {
            add(sectionLabel("Key"))
            add(keyField)
            add(keyNote)
            add(keyError)
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
            add(sectionLabel("Value").apply { border = JBUI.Borders.emptyTop(6) })
            add(valueInput)
            add(valueError)
            add(addButton.apply { border = JBUI.Borders.emptyTop(4) })
        }

        jsonPane.apply {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(sectionLabel("JSON"), BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
                    isOpaque = false
                    add(fileName)
                    add(ActionLink("Upload .json…") { uploadJson() })
                }, BorderLayout.EAST)
            })
            add(muted(
                "Paste {\"key\": value, …} — the type comes from each value and new keys go into the " +
                    "target group — or a Firebase template export, which keeps its own groups and types."
            ))
            add(JBScrollPane(jsonInput))
            add(previewPanel)
            add(importButton)
        }

        val content = JPanel(VerticalLayout(JBUI.scale(14))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(14, 14, 14, 14)
            add(JPanel(VerticalLayout(JBUI.scale(4))).apply {
                isOpaque = false
                add(JBLabel("Batch push").apply { font = JBFont.h3().asBold() })
                add(muted("Add parameters one at a time or import JSON. Nothing is sent until you review and push the batch below."))
            })
            add(JPanel(VerticalLayout(JBUI.scale(4))).apply {
                isOpaque = false
                add(sectionLabel("Target group"))
                add(groupCombo)
                add(muted("Leave blank for root parameters. New parameters go here; existing ones are updated in the group they already belong to."))
            })
            add(JPanel(GridLayout(1, 2, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(singleTab)
                add(jsonTab)
            })
            add(singlePane)
            add(jsonPane)
            add(status)
            add(JPanel(VerticalLayout(JBUI.scale(6))).apply {
                isOpaque = false
                add(stagedTitle)
                add(stagedList)
            })
        }

        add(JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }, BorderLayout.CENTER)

        keyField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onKeyEdited()
        })
        keyField.onKey("ENTER") { addSingle() }
        groupEditor?.document?.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                refreshSingle()
                previewTimer.restart()
            }
        })
        jsonInput.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = previewTimer.restart()
        })

        valueInput.set(valueType, "")
        setTab(Tab.SINGLE)
        sync()
    }

    /** Picks up new groups, the current batch, and re-checks keys against the live template. */
    fun sync() {
        val typed = groupEditor?.text ?: ""
        groupCombo.model = DefaultComboBoxModel((listOf("") + host.groupNames()).toTypedArray())
        groupEditor?.text = typed
        renderStaged()
        refreshSingle()
        if (jsonInput.text.isNotBlank()) refreshPreview()
    }

    fun focusFirstField() {
        (if (tab == Tab.SINGLE) keyField else jsonInput).requestFocusInWindow()
    }

    // ---------- tabs and type ----------

    private fun setTab(next: Tab) {
        tab = next
        singleTab.selected = next == Tab.SINGLE
        jsonTab.selected = next == Tab.JSON
        singlePane.isVisible = next == Tab.SINGLE
        jsonPane.isVisible = next == Tab.JSON
        showStatus("", Palette.MUTED)
        revalidate()
        repaint()
    }

    private fun setType(type: String) {
        if (type == valueType) return
        valueType = type
        valueInput.retype(type)
        refreshSingle()
    }

    // ---------- one by one ----------

    private fun targetGroup(): String? = groupEditor?.text?.trim()?.takeIf { it.isNotEmpty() }

    private fun remoteFor(key: String): ConfigEntry? = host.remoteEntries().firstOrNull { it.key == key }

    /** An existing key takes on its current type, so a batch never flips one by accident. */
    private fun onKeyEdited() {
        remoteFor(keyField.text.trim())?.let { if (it.valueType != valueType) setType(it.valueType) }
        refreshSingle()
    }

    private fun singleChange(): ParameterChange {
        val key = keyField.text.trim()
        val remote = remoteFor(key)
        return ParameterChange(
            key = key,
            value = normalizeValue(valueInput.value, valueType),
            valueType = valueType,
            group = if (remote != null) remote.group else targetGroup(),
            isNew = remote == null
        )
    }

    private fun refreshSingle() {
        typeChips.forEach { (type, chip) -> chip.selected = type == valueType }
        val change = singleChange()
        val remote = remoteFor(change.key)
        keyNote.text = when {
            remote != null ->
                "Exists ${remote.group?.let { "in group \"$it\"" } ?: "in root parameters"} — its value will be updated there."

            session.stagedChange(change.key) != null -> "Already in the batch — adding replaces it."
            else -> ""
        }

        keyError.text = ""
        valueError.text = ""
        val error = validatePush(change.key, valueInput.value, valueType, change.group) ?: return
        // Quiet about empty fields until the first Add, as in the editor.
        val empty = when (error.field) {
            Field.KEY -> change.key.isEmpty()
            Field.VALUE -> valueInput.value.isEmpty()
            Field.GROUP -> false
        }
        if (attempted || !empty) {
            when (error.field) {
                Field.VALUE -> valueError.text = error.message
                // A bad target group is reported under the key: it is what the Add acts on.
                else -> keyError.text = error.message
            }
        }
    }

    private fun addSingle() {
        attempted = true
        refreshSingle()
        val change = singleChange()
        val error = validatePush(change.key, valueInput.value, valueType, change.group)
        if (error != null) {
            showStatus(error.message, Palette.ERROR)
            when (error.field) {
                Field.VALUE -> valueInput.focusField()
                Field.GROUP -> groupCombo.requestFocusInWindow()
                Field.KEY -> keyField.requestFocusInWindow()
            }
            return
        }
        val failure = session.stageAll(listOf(change))
        if (failure != null) {
            showStatus(failure, Palette.ERROR)
            return
        }
        // Ready for the next one: key and value clear, group and type stay.
        attempted = false
        keyField.text = ""
        valueInput.set(valueType, "")
        refreshSingle()
        showStatus("Added \"${change.key}\" to the batch.", Palette.SUCCESS)
        keyField.requestFocusInWindow()
    }

    // ---------- import JSON ----------

    private fun uploadJson() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Choose a JSON File to Add to the Batch")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        // Firebase's own template limit; anything larger cannot be pushed anyway.
        if (file.length > MAX_IMPORT_BYTES) {
            showStatus("That file is over 1 MB — larger than Firebase allows a template to be.", Palette.ERROR)
            return
        }
        setTab(Tab.JSON)
        jsonInput.text = VfsUtilCore.loadText(file)
        jsonInput.caretPosition = 0
        fileName.text = file.name
        refreshPreview()
    }

    private fun refreshPreview() {
        previewTimer.stop()
        val result = parseBatchJson(jsonInput.text, targetGroup(), host.remoteEntries())
        preview = result
        if (jsonInput.text.isBlank()) fileName.text = ""

        val ready = result.ready.size
        importButton.text = if (ready > 0) "Add $ready to batch" else "Add to batch"
        importButton.isEnabled = ready > 0

        previewPanel.removeAll()
        when {
            result.error != null -> previewPanel.add(errorLabel().apply { text = result.error })
            result.rows.isNotEmpty() -> {
                val bad = result.rows.size - ready
                val kind = if (result.format == ImportResult.Format.TEMPLATE) "Firebase template" else "Key/value map"
                previewPanel.add(muted("$kind · $ready ready" + if (bad > 0) " · $bad with problems" else ""))
                result.warnings.forEach { previewPanel.add(warning(it)) }
                result.rows.forEach { row ->
                    previewPanel.add(card(
                        title = row.key,
                        pill = when {
                            row.error != null -> "Skipped"
                            row.isNew -> "New"
                            else -> "Update"
                        },
                        pillColor = if (row.error != null) Palette.ERROR else if (row.isNew) Palette.ACCENT_TEXT else Palette.MUTED,
                        detail = "${typeLabel(row.valueType)} · ${row.group ?: "Root"} · ${oneLine(row.value)}",
                        extra = row.error?.let { errorLabel().apply { text = it } } ?: row.note?.let { note().apply { text = it } },
                        outline = if (row.error != null) Palette.ERROR else Palette.OUTLINE
                    ))
                }
            }
        }
        previewPanel.revalidate()
        previewPanel.repaint()
    }

    private fun addImported() {
        val rows = preview?.ready.orEmpty()
        if (rows.isEmpty()) return
        val failure = session.stageAll(rows.map { it.toChange() })
        if (failure != null) {
            showStatus(failure, Palette.ERROR)
            return
        }
        showStatus("Added ${rows.size} parameter${if (rows.size == 1) "" else "s"} to the batch.", Palette.SUCCESS)
        jsonInput.text = ""
        fileName.text = ""
        refreshPreview()
    }

    // ---------- batch contents ----------

    private fun renderStaged() {
        val staged = session.stagedChanges()
        stagedTitle.text = if (staged.isEmpty()) "Batch is empty" else "In this batch (${staged.size})"
        stagedList.removeAll()
        staged.forEach { change ->
            val remove = IconButton(AllIcons.Actions.Close, "Remove \"${change.key}\" from the batch")
                .onClick { session.unstage(change.key) }
            stagedList.add(card(
                title = change.key,
                pill = if (change.isNew) "New" else "Update",
                pillColor = if (change.isNew) Palette.ACCENT_TEXT else Palette.MUTED,
                detail = "${typeLabel(change.valueType)} · ${change.group ?: "Root"} · ${oneLine(change.value)}",
                trailing = remove,
                onOpen = { host.open(change.key) }
            ))
        }
        stagedList.revalidate()
        stagedList.repaint()
    }

    /** A small rounded card: a title with a status word, a detail line, and optionally more. */
    private fun card(
        title: String,
        pill: String,
        pillColor: Color,
        detail: String,
        extra: JComponent? = null,
        trailing: JComponent? = null,
        outline: Color = Palette.OUTLINE,
        onOpen: (() -> Unit)? = null
    ): JComponent = RoundedPanel(BorderLayout(JBUI.scale(8), 0), outline = { outline }, arc = Radius.CONTROL).apply {
        border = JBUI.Borders.empty(7, 10)
        val text = JPanel(VerticalLayout(JBUI.scale(2))).apply {
            isOpaque = false
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JBLabel(title).apply { font = JBFont.label().asBold() }, BorderLayout.CENTER)
                add(JBLabel(pill).apply {
                    font = JBFont.small()
                    foreground = pillColor
                }, BorderLayout.EAST)
            })
            add(JBLabel(detail).apply {
                font = JBFont.small()
                foreground = Palette.MUTED
            })
            extra?.let { add(it) }
        }
        add(text, BorderLayout.CENTER)
        trailing?.let { add(it, BorderLayout.EAST) }
        if (onOpen != null) {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Open in the editor"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onOpen()
            })
        }
    }

    // ---------- bits ----------

    private fun showStatus(message: String, color: Color) {
        status.text = message
        status.foreground = color
        status.isVisible = message.isNotEmpty()
    }

    private fun sectionLabel(text: String) = JBLabel(text).apply { font = JBFont.label().asBold() }

    private fun muted(text: String) = JBLabel("<html>${escapeHtml(text)}</html>").apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun note() = JBLabel().apply {
        font = JBFont.small()
        foreground = Palette.ACCENT_TEXT
    }

    private fun warning(text: String) = JBLabel("<html>${escapeHtml(text)}</html>").apply {
        font = JBFont.small()
        foreground = Palette.WARNING
    }

    private fun errorLabel() = JBLabel().apply {
        font = JBFont.small()
        foreground = Palette.ERROR
    }

    private companion object {
        /** Firebase rejects templates over 1 MB, so a larger import could never be pushed. */
        const val MAX_IMPORT_BYTES = 1024L * 1024L

        fun oneLine(value: String): String {
            val flat = value.replace(Regex("\\s*\\n\\s*"), " ").trim()
            return if (flat.length > 48) flat.take(47) + "…" else flat
        }
    }
}
