package com.xclusivecyborg.rmcpush.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.xclusivecyborg.rmcpush.model.ConfigEntry
import com.xclusivecyborg.rmcpush.model.ParameterChange
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * Pinned under the panel while anything is staged: how many changes are
 * waiting, and the way to push or discard them.
 */
internal class BatchBar(
    onReview: () -> Unit,
    onDiscard: () -> Unit
) : RoundedPanel(BorderLayout(), fill = { Palette.ACCENT_SOFT }, outline = { Palette.ACCENT }) {

    private val summary = JBLabel().apply { font = JBFont.label().asBold() }
    private val result = JBLabel().apply { font = JBFont.small() }
    private val discard = ActionLink("Discard") { onDiscard() }
    private val review = AccentButton("Review & push").onClick(onReview)

    private var count = 0

    init {
        border = JBUI.Borders.empty(8, 12)
        add(JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(summary, BorderLayout.NORTH)
            add(result, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(discard)
            add(review)
        }, BorderLayout.EAST)
        update(0)
    }

    fun update(staged: Int) {
        // A new change after a push starts a new batch; the old result is stale.
        if (staged > count) result.text = ""
        count = staged
        summary.text = "● $staged change${if (staged == 1) "" else "s"} in batch"
        summary.foreground = Palette.ACCENT_TEXT
        summary.isVisible = staged > 0
        discard.isVisible = staged > 0
        review.isVisible = staged > 0
        isVisible = staged > 0 || result.text.isNotEmpty()
    }

    fun setBusy(busy: Boolean) {
        review.isEnabled = !busy
        discard.isEnabled = !busy
        if (busy) showResult("Pushing…", Palette.MUTED)
    }

    fun showResult(message: String, color: Color) {
        result.text = message
        result.foreground = color
        result.toolTipText = message
        isVisible = true
    }

    fun clearResult() {
        result.text = ""
        update(count)
    }
}

/**
 * Lists everything staged, current value beside new, and lets the user untick
 * any they want to hold back. OK pushes the ticked ones as one template version.
 */
internal class BatchReviewDialog(
    project: Project,
    changes: List<ParameterChange>,
    remoteByKey: Map<String, ConfigEntry>
) : DialogWrapper(project) {

    private val rows = changes.map { Row(it, remoteByKey[it.key], include = true) }
    private val model = ReviewModel()

    val selected: List<ParameterChange> get() = rows.filter { it.include }.map { it.change }

    init {
        title = "Push Batch to Firebase"
        init()
        updateOk()
    }

    override fun createCenterPanel(): JComponent {
        val table = JBTable(model).apply {
            setShowGrid(false)
            rowHeight = JBUI.scale(26)
            columnModel.getColumn(0).apply {
                maxWidth = JBUI.scale(36)
                minWidth = JBUI.scale(36)
            }
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(
                JBLabel(
                    "These changes are written together as one new template version — " +
                        "either all of them land or none do."
                ).apply { foreground = Palette.MUTED },
                BorderLayout.NORTH
            )
            add(JBScrollPane(table).apply { preferredSize = Dimension(JBUI.scale(640), JBUI.scale(260)) })
        }
    }

    private fun updateOk() {
        val n = rows.count { it.include }
        setOKButtonText(if (n == 0) "Push" else "Push $n change${if (n == 1) "" else "s"}")
        isOKActionEnabled = n > 0
    }

    private class Row(val change: ParameterChange, val remote: ConfigEntry?, var include: Boolean)

    private inner class ReviewModel : AbstractTableModel() {
        private val columns = listOf("", "Parameter", "Group", "Type", "Current value", "New value")

        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(column: Int): Class<*> =
            if (column == 0) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int) = column == 0

        override fun getValueAt(row: Int, column: Int): Any {
            val r = rows[row]
            return when (column) {
                0 -> r.include
                1 -> r.change.key + if (r.change.isNew) "  (new)" else ""
                2 -> r.change.group ?: "—"
                3 -> typeLabel(r.change.valueType) +
                    if (r.remote != null && r.remote.valueType != r.change.valueType) {
                        " (was ${typeLabel(r.remote.valueType)})"
                    } else ""

                4 -> r.remote?.value?.oneLine() ?: "—"
                else -> r.change.value.oneLine()
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (column != 0) return
            rows[row].include = value == true
            fireTableCellUpdated(row, column)
            updateOk()
        }
    }
}

private fun String.oneLine() = replace('\n', ' ').trim()
