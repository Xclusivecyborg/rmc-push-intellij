package com.xclusivecyborg.rmcpush.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.xclusivecyborg.rmcpush.model.ConfigEntry
import com.xclusivecyborg.rmcpush.model.ConfigSection
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * The browse screen: create button, filter, and one card per parameter group.
 *
 * The card — not an indent — is what shows where a group starts and ends. This
 * was a [com.intellij.ui.treeStructure.Tree] originally, but a tree draws every
 * row at one height on one flat background, so groups and parameters read as the
 * same kind of thing and a long list is hard to scan. Cards cost the tree's
 * built-in speed search, which the filter field above already covers.
 *
 * Held as a single long-lived instance rather than rebuilt per render, so the
 * filter text and which groups are collapsed survive a refresh.
 */
class ParameterListPanel(
    private val onCreate: () -> Unit,
    private val onOpen: (ConfigEntry) -> Unit
) : JPanel(BorderLayout()) {

    private val tiles = JPanel(VerticalLayout(JBUI.scale(TILE_GAP))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 8, 8)
    }

    private val filterField = SearchTextField()

    private var sections: List<ConfigSection> = emptyList()

    /** Group names the user has collapsed; "" is the root section. */
    private val collapsedGroups = mutableSetOf<String>()

    /**
     * Group whose header should regain focus after the next rebuild. Toggling a
     * card throws away the component that was focused, so keyboard users would
     * otherwise be dropped back to the top of the panel on every open and close.
     */
    private var refocusGroup: String? = null

    init {
        filterField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuild()
        })

        val newParameter = JButton("+ New parameter").apply {
            addActionListener { onCreate() }
        }

        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 8, 4, 8)
            // Above the filter, so creating a parameter is the first thing offered.
            add(newParameter, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(6)
                add(filterField, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }

        // NORTH, so the cards keep their own heights instead of being stretched
        // to fill the viewport when there are only one or two of them.
        val scrollable = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(tiles, BorderLayout.NORTH)
        }

        val scroller = JBScrollPane(scrollable).apply {
            border = JBUI.Borders.empty()
            viewport.background = UIUtil.getPanelBackground()
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }

        add(top, BorderLayout.NORTH)
        add(scroller, BorderLayout.CENTER)
    }

    fun setSections(sections: List<ConfigSection>) {
        this.sections = sections
        rebuild()
    }

    private fun rebuild() {
        val needle = filterField.text.trim().lowercase()
        val filtering = needle.isNotEmpty()

        val visible = sections.mapNotNull { section ->
            val entries =
                if (!filtering) section.entries
                else section.entries.filter { it.key.lowercase().contains(needle) }
            // A filter that matches nothing in a group hides the group entirely.
            if (filtering && entries.isEmpty()) null else section.copy(entries = entries)
        }

        tiles.removeAll()

        if (visible.isEmpty()) {
            tiles.add(
                JBLabel(
                    if (sections.all { it.entries.isEmpty() }) "This project has no parameters yet"
                    else "No parameters match the filter",
                    SwingConstants.CENTER
                ).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    border = JBUI.Borders.empty(24, 8)
                }
            )
        } else {
            val toFocus = refocusGroup
            refocusGroup = null
            for (section in visible) {
                val key = section.group ?: ""
                // While filtering, show every match regardless of what the user
                // had collapsed — otherwise a hit can hide inside a closed card.
                val tile = GroupTile(section, expanded = filtering || key !in collapsedGroups)
                tiles.add(tile)
                // Deferred: the tile has not been laid out yet, and focus cannot
                // move to a component that is not showing.
                if (key == toFocus) SwingUtilities.invokeLater { tile.focusHeader() }
            }
        }

        tiles.revalidate()
        tiles.repaint()
    }

    private fun toggle(group: String) {
        if (!collapsedGroups.remove(group)) collapsedGroups.add(group)
        refocusGroup = group
        rebuild()
    }

    /** One parameter group: a header that opens and closes it, and its rows. */
    private inner class GroupTile(section: ConfigSection, expanded: Boolean) : JPanel(BorderLayout()) {

        private val head = TileHeader(section, expanded)

        init {
            isOpaque = false
            add(head, BorderLayout.NORTH)

            if (expanded) {
                add(
                    JPanel(VerticalLayout(0)).apply {
                        isOpaque = false
                        border = JBUI.Borders.empty(3, 0)
                        if (section.entries.isEmpty()) {
                            add(JBLabel("No parameters").apply {
                                foreground = UIUtil.getInactiveTextColor()
                                border = JBUI.Borders.empty(4, 12)
                            })
                        } else {
                            section.entries.forEach { add(EntryRow(it)) }
                        }
                    },
                    BorderLayout.CENTER
                )
            }
        }

        fun focusHeader() = head.requestFocusInWindow()

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(ARC)
                g2.color = UIUtil.getTextFieldBackground()
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = JBColor.border()
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * The card's header. Paints its own tinted band and accent edge, rounded to
     * match the card it sits in.
     */
    private inner class TileHeader(section: ConfigSection, private val expanded: Boolean) : JPanel(BorderLayout()) {

        private val group = section.group ?: ""

        init {
            isOpaque = false
            isFocusable = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(7, 10, 7, 8)
            toolTipText = section.description

            add(
                JBLabel(if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight).apply {
                    border = JBUI.Borders.emptyRight(6)
                },
                BorderLayout.WEST
            )
            add(
                JBLabel(section.group ?: "Parameters").apply { font = font.deriveFont(Font.BOLD) },
                BorderLayout.CENTER
            )
            add(Pill(section.entries.size.toString()), BorderLayout.EAST)

            addMouseListenerDeep(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    toggle(group)
                }
            })
            onKey("SPACE") { toggle(group) }
            onKey("ENTER") { toggle(group) }
            onKey("DOWN") { transferFocus() }
            onKey("UP") { transferFocusBackward() }
            repaintOnFocusChange()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(ARC).toFloat()
                // Expanded, the band runs past the bottom edge so only its top
                // corners round and the component's own bounds clip the rest
                // square. Collapsed, the header is the whole card, so it rounds
                // at both ends.
                val shape = RoundRectangle2D.Float(
                    1f, 1f, (width - 2).toFloat(),
                    if (expanded) height + arc else (height - 2).toFloat(),
                    arc, arc
                )
                g2.clip(shape)
                g2.color = if (isFocusOwner) UIUtil.getListSelectionBackground(true) else UIUtil.getPanelBackground()
                g2.fill(shape)
                // The accent edge is the card's only strong colour — enough to
                // tell one card from the next without competing with the values.
                g2.color = JBUI.CurrentTheme.Link.Foreground.ENABLED
                g2.fillRect(1, 1, JBUI.scale(ACCENT_WIDTH), height)
            } finally {
                g2.dispose()
            }
            if (expanded) {
                g.color = JBColor.border()
                g.fillRect(1, height - 1, width - 2, 1)
            }
        }
    }

    /** One parameter: its key, then its type, value and condition count. */
    private inner class EntryRow(entry: ConfigEntry) : JPanel(BorderLayout()) {

        private var hovered = false

        private val keyLabel = JBLabel(entry.key)
        private val valueLabel: JBLabel

        init {
            isOpaque = false
            isFocusable = true
            border = JBUI.Borders.empty(5, 12, 6, 10)

            val preview = if (entry.usesInAppDefault) "(in-app default)" else entry.value
            // Values are free-form and may contain newlines, which would
            // otherwise break the row into an unreadable blob.
            val flattened = preview.replace('\n', ' ').trim()
            valueLabel = JBLabel(flattened).apply {
                foreground = UIUtil.getInactiveTextColor()
                border = JBUI.Borders.emptyLeft(6)
                toolTipText = flattened.ifEmpty { null }
            }

            add(keyLabel, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyTop(2)
                    add(Pill(entry.valueType), BorderLayout.WEST)
                    // CENTER, so a long value is ellipsised by the label rather
                    // than widening the whole tool window.
                    add(valueLabel, BorderLayout.CENTER)
                    if (entry.conditionCount > 0) {
                        add(
                            JBLabel("+${entry.conditionCount} cond.").apply {
                                font = JBUI.Fonts.smallFont()
                                foreground = UIUtil.getInactiveTextColor()
                                border = JBUI.Borders.emptyLeft(6)
                                toolTipText =
                                    "${entry.conditionCount} conditional value(s) — edited in the Firebase console"
                            },
                            BorderLayout.EAST
                        )
                    }
                },
                BorderLayout.CENTER
            )

            addMouseListenerDeep(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) onOpen(entry)
                }

                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    // Children fire an exit as the pointer crosses into them, so
                    // only drop the highlight once it has left the row for good.
                    val point = SwingUtilities.convertPoint(e.component, e.point, this@EntryRow)
                    if (!contains(point)) {
                        hovered = false
                        repaint()
                    }
                }
            })

            onKey("ENTER") { onOpen(entry) }
            onKey("SPACE") { onOpen(entry) }
            onKey("DOWN") { transferFocus() }
            onKey("UP") { transferFocusBackward() }

            addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) {
                    applySelectionColors()
                    scrollRectToVisible(Rectangle(0, 0, width, height))
                    repaint()
                }

                override fun focusLost(e: FocusEvent) {
                    applySelectionColors()
                    repaint()
                }
            })
            applySelectionColors()
        }

        /** Keeps the text legible once the row paints the selection fill. */
        private fun applySelectionColors() {
            val selected = isFocusOwner
            keyLabel.foreground =
                if (selected) UIUtil.getListSelectionForeground(true) else UIUtil.getLabelForeground()
            valueLabel.foreground =
                if (selected) UIUtil.getListSelectionForeground(true) else UIUtil.getInactiveTextColor()
        }

        override fun paintComponent(g: Graphics) {
            val fill = when {
                isFocusOwner -> UIUtil.getListSelectionBackground(true)
                hovered -> JBUI.CurrentTheme.ActionButton.hoverBackground()
                else -> null
            }
            if (fill != null) {
                g.color = fill
                g.fillRect(0, 0, width, height)
            }
        }
    }

    /** A small rounded chip: a parameter's type, or a group's parameter count. */
    private class Pill(text: String) : JBLabel(text) {
        init {
            isOpaque = false
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getInactiveTextColor()
            border = JBUI.Borders.empty(1, 6)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = PILL_BACKGROUND
                g2.fillRoundRect(0, 0, width, height, height, height)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private companion object {
        const val TILE_GAP = 8

        /** Arc diameter, not radius — 12 here is the 6px corner the VS Code sidebar uses. */
        const val ARC = 12
        const val ACCENT_WIDTH = 3

        /**
         * Translucent rather than a named theme colour, so the chip stays legible
         * on both the card body and the tinted header without having to know
         * which of the two it landed on.
         */
        val PILL_BACKGROUND = JBColor(Color(0, 0, 0, 28), Color(255, 255, 255, 28))
    }
}

/** Repaints on focus change, so a focused component can paint itself selected. */
private fun JComponent.repaintOnFocusChange() {
    addFocusListener(object : FocusAdapter() {
        override fun focusGained(e: FocusEvent) = repaint()

        override fun focusLost(e: FocusEvent) = repaint()
    })
}

/**
 * Adds [listener] to this component and every descendant.
 *
 * Swing delivers a mouse event to the deepest component that listens for one,
 * and a label with a tooltip listens — so without this, clicking a parameter's
 * value would land on the label and never reach the row.
 */
private fun JComponent.addMouseListenerDeep(listener: MouseListener) {
    addMouseListener(listener)
    components.forEach { child -> (child as? JComponent)?.addMouseListenerDeep(listener) }
}

/** Binds [key] to [action] while this component has focus. */
private fun JComponent.onKey(key: String, action: () -> Unit) {
    val id = "rmcPush.$key"
    getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), id)
    actionMap.put(id, object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) = action()
    })
}
