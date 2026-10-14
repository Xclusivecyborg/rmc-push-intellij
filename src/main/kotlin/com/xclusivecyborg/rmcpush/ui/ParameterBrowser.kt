package com.xclusivecyborg.rmcpush.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.xclusivecyborg.rmcpush.model.ConfigEntry
import com.xclusivecyborg.rmcpush.model.ConfigSection
import com.xclusivecyborg.rmcpush.model.ParameterChange
import com.xclusivecyborg.rmcpush.validation.VALUE_TYPES
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Graphics
import java.awt.geom.RoundRectangle2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * A parameter as the panel shows it: what Firebase holds, overlaid with
 * whatever is waiting in the batch. [remote] is null for a parameter that only
 * exists in the batch so far.
 */
internal data class ParamItem(val remote: ConfigEntry?, val staged: ParameterChange?) {
    init {
        require(remote != null || staged != null)
    }

    val key: String get() = remote?.key ?: staged!!.key
    val group: String? get() = remote?.group ?: staged?.group
    val valueType: String get() = staged?.valueType ?: remote!!.valueType
    val value: String get() = staged?.value ?: remote!!.value
    val isNew: Boolean get() = remote == null
}

/**
 * Root parameters first, then each group, as [sections] orders them — with
 * staged edits laid over their parameter and staged new keys appended.
 */
internal fun itemsOf(sections: List<ConfigSection>, staged: List<ParameterChange>): List<ParamItem> {
    val stagedByKey = staged.associateBy { it.key }
    val remote = sections.flatMap { it.entries }
    val remoteKeys = remote.mapTo(HashSet()) { it.key }
    return remote.map { ParamItem(it, stagedByKey[it.key]) } +
        staged.filter { it.key !in remoteKeys }.map { ParamItem(null, it) }
}

/**
 * A row in the parameter list: a group's foldable header, or one parameter.
 * [Header.group] is null for the root parameters.
 */
internal sealed class Row {
    data class Header(val group: String?, val description: String?, val count: Int, val expanded: Boolean) : Row()
    /** [first] and [last] mark the rows that open and close the group's card. */
    data class Param(val item: ParamItem, val first: Boolean = false, val last: Boolean = false) : Row()
}

/**
 * Lays [items] out under their group headers: root parameters first, then each
 * group in [sections] order, then any group that so far only exists in the
 * batch.
 *
 * While [filtering], groups without a match are dropped and every remaining
 * group is open regardless of [collapsed] — otherwise a hit could hide inside
 * a closed group. Without a filter, empty groups stay, so they can be seen.
 */
internal fun rowsOf(
    sections: List<ConfigSection>,
    items: List<ParamItem>,
    collapsed: Set<String>,
    filtering: Boolean
): List<Row> {
    val byGroup = items.groupBy { it.group ?: "" }
    val order = LinkedHashMap<String, String?>()
    sections.forEach { order[it.group ?: ""] = it.description }
    byGroup.keys.forEach { if (it !in order) order[it] = null }

    return order.flatMap { (group, description) ->
        val members = byGroup[group].orEmpty()
        if (filtering && members.isEmpty()) return@flatMap emptyList()
        val expanded = filtering || group !in collapsed
        listOf(Row.Header(group.ifEmpty { null }, description, members.size, expanded)) +
            if (expanded) members.mapIndexed { i, item ->
                Row.Param(item, first = i == 0, last = i == members.lastIndex)
            }
            else emptyList()
    }
}

/**
 * The left-hand (or, when narrow, top) pane: search, type filter tabs, and one
 * card per parameter under its group's foldable header.
 *
 * Long-lived, so the search text, the selected tab, the folded groups and the
 * selection survive a refresh.
 */
internal class ParameterBrowser(
    private val onSelect: (ParamItem?) -> Unit,
    private val onCreate: () -> Unit,
    private val onBatch: () -> Unit
) : JPanel(BorderLayout()) {

    private val search = SearchTextField(false)

    private val hint = JBLabel("Select a parameter to view and edit it").apply {
        font = JBFont.small()
        foreground = Palette.MUTED
        border = JBUI.Borders.emptyTop(6)
    }
    private val model = CollectionListModel<Row>()
    private val list = JBList(model)

    /** Keyed by value type; null is "All". */
    private val chips = LinkedHashMap<String?, Chip>()
    private var typeFilter: String? = null

    private var items: List<ParamItem> = emptyList()
    private var sections: List<ConfigSection> = emptyList()

    /** Group names the user has folded; "" is the root section. */
    private val collapsed = mutableSetOf<String>()

    /** Set while the list is repopulated, so that does not read as the user picking something. */
    private var suppressSelection = false

    init {
        isOpaque = false
        (search.textEditor as? JBTextField)?.emptyText?.text = "Search configs…"
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refilter()
        })
        // Down from the search box moves into the results, as in Search Everywhere.
        search.textEditor.onKey("DOWN") {
            if (model.size > 0) {
                if (list.selectedIndex < 0) list.selectedIndex = 0
                list.requestFocusInWindow()
            }
        }

        // Full width, straight under the search box, so creating one parameter
        // or many is always in plain sight rather than tucked into a small icon.
        val buttons = JPanel(GridLayout(1, 2, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(AccentButton("New parameter", AllIcons.General.Add).onClick { onCreate() })
            add(OutlineButton("Batch Push", AllIcons.Nodes.Folder).onClick { onBatch() })
        }

        val searchRow = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(search, BorderLayout.NORTH)
            add(buttons, BorderLayout.CENTER)
        }

        val chipRow = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(6))).apply {
            isOpaque = false
            // WrapLayout pads the outer edge by its gap; cancel it so the
            // chips line up with the search box.
            border = JBUI.Borders.empty(2, -6, 0, 0)
            (listOf<String?>(null) + VALUE_TYPES).forEach { type ->
                val chip = Chip(type?.let(::typeLabel) ?: "All").onClick { setTypeFilter(type) }
                chips[type] = chip
                add(chip)
            }
        }
        chips[null]?.selected = true

        val top = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 10, 4, 10)
            add(searchRow, BorderLayout.NORTH)
            add(chipRow, BorderLayout.CENTER)
            add(hint, BorderLayout.SOUTH)
        }

        list.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = CardRenderer()
            isOpaque = false
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(0, 10, 10, 10)
            setExpandableItemsEnabled(false)
            addListSelectionListener { e ->
                if (e.valueIsAdjusting || suppressSelection) return@addListSelectionListener
                // Landing on a header (arrow keys pass through them) keeps the
                // editor on whatever it was showing.
                when (val row = selectedValue) {
                    is Row.Param -> onSelect(row.item)
                    is Row.Header -> Unit
                    null -> onSelect(null)
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index < 0 || getCellBounds(index, index)?.contains(e.point) != true) return
                    (model.getElementAt(index) as? Row.Header)?.let { toggle(it) }
                }
            })
            // On a header, Enter/Space fold it and Left/Right close/open it, as in a tree.
            onKey("ENTER") { selectedHeader()?.let { toggle(it) } }
            onKey("SPACE") { selectedHeader()?.let { toggle(it) } }
            onKey("LEFT") { selectedHeader()?.takeIf { it.expanded }?.let { toggle(it) } }
            onKey("RIGHT") { selectedHeader()?.takeIf { !it.expanded }?.let { toggle(it) } }
        }

        val scroller = JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            viewport.background = UIUtil.getPanelBackground()
        }

        add(top, BorderLayout.NORTH)
        add(scroller, BorderLayout.CENTER)
    }

    /** Replaces the contents, keeping the selection on [selectedKey] when it is still shown. */
    fun setItems(items: List<ParamItem>, sections: List<ConfigSection>, selectedKey: String?) {
        this.items = items
        this.sections = sections
        refilter(selectedKey)
    }

    /** Shown while nothing is selected, under the type tabs. */
    fun setHintVisible(visible: Boolean) {
        hint.isVisible = visible
    }

    /** Selects [key] without reporting it back through onSelect. */
    fun select(key: String?) {
        withoutSelectionEvents {
            val index = if (key == null) -1 else model.items.indexOfFirst { it is Row.Param && it.item.key == key }
            if (index < 0) list.clearSelection()
            else {
                list.selectedIndex = index
                list.ensureIndexIsVisible(index)
            }
        }
    }

    private fun setTypeFilter(type: String?) {
        typeFilter = type
        chips.forEach { (chipType, chip) -> chip.selected = chipType == type }
        refilter()
    }

    private fun selectedHeader(): Row.Header? = list.selectedValue as? Row.Header

    private fun toggle(header: Row.Header) {
        val group = header.group ?: ""
        if (!collapsed.remove(group)) collapsed.add(group)
        refilter()
        // Keep keyboard focus on the header just folded, not the top of the list.
        withoutSelectionEvents {
            val index = model.items.indexOfFirst { it is Row.Header && it.group == header.group }
            if (index >= 0) list.selectedIndex = index
        }
    }

    private fun refilter(selectedKey: String? = (list.selectedValue as? Row.Param)?.item?.key) {
        val needle = search.text.trim().lowercase()
        val visible = items.filter { item ->
            (typeFilter == null || item.valueType == typeFilter) &&
                (needle.isEmpty() ||
                    item.key.lowercase().contains(needle) ||
                    item.group?.lowercase()?.contains(needle) == true)
        }

        chips.forEach { (type, chip) ->
            val count = if (type == null) items.size else items.count { it.valueType == type }
            chip.toolTipText = "$count parameter${if (count == 1) "" else "s"}"
        }

        list.emptyText.clear()
        if (items.isEmpty()) {
            list.emptyText.text = "This project has no parameters yet"
            list.emptyText.appendSecondaryText("Create one", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { onCreate() }
        } else {
            list.emptyText.text = "No parameters match"
        }

        val filtering = needle.isNotEmpty() || typeFilter != null
        val rows = if (filtering && visible.isEmpty()) emptyList()
        else rowsOf(sections, visible, collapsed, filtering)
        withoutSelectionEvents { model.replaceAll(rows) }
        select(selectedKey)
    }

    private inline fun withoutSelectionEvents(block: () -> Unit) {
        suppressSelection = true
        try {
            block()
        } finally {
            suppressSelection = false
        }
    }

    /**
     * Draws each group as one card, as before: a tinted header with the accent
     * edge, then the group's parameters as rows. A list cannot nest components,
     * so every row paints its own slice of the card — the header its top, the
     * middle rows its sides, the last row its rounded bottom.
     *
     * A renderer rather than real components, so a project with hundreds of
     * parameters stays cheap and keyboard navigation comes from the list.
     */
    private class CardRenderer : ListCellRenderer<Row> {

        private val keyLabel = JBLabel().apply { font = JBFont.label().asBold() }
        private val subtitle = JBLabel().apply { font = JBFont.small() }
        private val marker = JBLabel().apply {
            font = JBFont.small()
            border = JBUI.Borders.emptyLeft(6)
        }

        private var selected = false
        private var firstInGroup = false
        private var lastInGroup = false

        private val paramRow = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                g.antialiased { g2 ->
                    // The group's outline: the middle of the card runs past
                    // both edges so only its sides show; the last row rounds
                    // its bottom corners. Unfilled, so the cards stand out.
                    val arc = JBUI.scale(Radius.CARD).toFloat()
                    val outlineBottom = if (lastInGroup) (height - 1).toFloat() else height + arc
                    g2.color = Palette.OUTLINE
                    g2.draw(RoundRectangle2D.Float(0f, -arc, (width - 1).toFloat(), outlineBottom + arc, arc, arc))

                    // The parameter's own card, inset inside the group.
                    val inset = JBUI.scale(CARD_INSET)
                    val cardArc = JBUI.scale(Radius.CONTROL)
                    // Cards are CARD_GAP apart from each other, and CARD_INSET
                    // from the group's divider above and outline below.
                    val top = if (firstInGroup) inset else JBUI.scale(CARD_GAP)
                    val bottom = height - (if (lastInGroup) inset else JBUI.scale(CARD_GAP))
                    val cw = width - 2 * inset - 1
                    val ch = bottom - top - 1
                    g2.color = if (selected) Palette.ACCENT_SOFT else Palette.SURFACE
                    g2.fillRoundRect(inset, top, cw, ch, cardArc, cardArc)
                    g2.color = if (selected) Palette.ACCENT else Palette.OUTLINE
                    g2.drawRoundRect(inset, top, cw, ch, cardArc, cardArc)
                }
            }
        }.apply {
            isOpaque = false
            add(JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                isOpaque = false
                add(keyLabel, BorderLayout.NORTH)
                add(subtitle, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(marker, BorderLayout.EAST)
        }

        private var expanded = false

        private val chevron = JBLabel().apply { border = JBUI.Borders.emptyRight(4) }
        private val groupName = JBLabel().apply { font = JBFont.label().asBold() }
        private val groupCount = CountPill()

        private val header = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                g.antialiased { g2 ->
                    val arc = JBUI.scale(Radius.CARD).toFloat()
                    val top = JBUI.scale(GROUP_GAP).toFloat()
                    val w = (width - 1).toFloat()
                    // Open, the card continues into the rows below, so only the
                    // top corners round. Folded, the header is the whole card.
                    val h = if (expanded) height - top + arc else height - top - 1
                    val shape = RoundRectangle2D.Float(0f, top, w, h, arc, arc)

                    g2.color = Palette.SURFACE
                    g2.fill(shape)
                    val clip = g2.clip
                    g2.clip(shape)
                    g2.color = if (selected) Palette.ACCENT_SOFT else Palette.HOVER
                    g2.fillRect(0, 0, width, height)
                    // The accent edge is the card's only strong colour — enough
                    // to tell one group from the next without competing with
                    // the values inside it.
                    g2.color = Palette.ACCENT
                    g2.fillRect(0, 0, JBUI.scale(EDGE), height)
                    g2.clip = clip
                    g2.color = Palette.OUTLINE
                    g2.draw(shape)
                    if (expanded) g2.drawLine(0, height - 1, width - 1, height - 1)
                }
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(GROUP_GAP + 8, 12, 8, 10)
            add(chevron, BorderLayout.WEST)
            add(groupName, BorderLayout.CENTER)
            add(groupCount, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out Row>,
            value: Row,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            selected = isSelected
            return when (value) {
                is Row.Header -> {
                    expanded = value.expanded && value.count > 0
                    chevron.icon = if (value.expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
                    groupName.text = value.group ?: "Parameters"
                    groupName.foreground = UIUtil.getLabelForeground()
                    groupCount.text = value.count.toString()
                    header.toolTipText = value.description
                    header
                }

                is Row.Param -> {
                    firstInGroup = value.first
                    lastInGroup = value.last
                    // Card padding plus the inset or gap the card is drawn with.
                    val topPad = if (value.first) CARD_INSET else CARD_GAP
                    val bottomPad = if (value.last) CARD_INSET else CARD_GAP
                    paramRow.border = JBUI.Borders.empty(
                        topPad + 8, CARD_INSET + 12, bottomPad + 8, CARD_INSET + 10
                    )
                    renderParam(value.item)
                }
            }
        }

        private fun renderParam(item: ParamItem): Component {
            keyLabel.text = item.key
            keyLabel.foreground = UIUtil.getLabelForeground()
            // The group header above already names the group. The value trails
            // the type, and the label ellipsises it when the card is narrow.
            val preview = previewOf(item)
            subtitle.text = if (preview.isEmpty()) typeLabel(item.valueType) else "${typeLabel(item.valueType)}  ·  $preview"
            subtitle.foreground = Palette.MUTED

            val conditions = item.remote?.conditionCount ?: 0
            marker.text = when {
                item.isNew -> "● New"
                item.staged != null -> "● Staged"
                conditions > 0 -> "+$conditions cond."
                else -> ""
            }
            marker.foreground = if (item.staged != null) Palette.ACCENT_TEXT else Palette.MUTED
            return paramRow
        }

        private companion object {
            /** Space above each group's card, so cards do not touch. */
            const val GROUP_GAP = 8

            /** Width of the accent edge on group headers. */
            const val EDGE = 3

            /** Space between a parameter card and the group outline around it. */
            const val CARD_INSET = 8

            /** Half the space between two parameter cards in a group. */
            const val CARD_GAP = 3
        }
    }
}

/**
 * The value shown on a card: what is staged, else what Firebase holds, on one
 * line. Values are free-form and may contain newlines, which would otherwise
 * break the card into an unreadable blob.
 */
internal fun previewOf(item: ParamItem): String =
    if (item.staged == null && item.remote?.usesInAppDefault == true) "(in-app default)"
    else item.value.replace(Regex("\\s*\\n\\s*"), " ").trim()

/** A group's parameter count, on an accent-tinted pill. */
private class CountPill : JBLabel() {
    init {
        isOpaque = false
        font = JBFont.small()
        foreground = Palette.ACCENT_TEXT
        horizontalAlignment = CENTER
        border = JBUI.Borders.empty(1, 7)
    }

    override fun paintComponent(g: Graphics) {
        g.antialiased { g2 ->
            g2.color = Palette.ACCENT_SOFT
            g2.fillRoundRect(0, 0, width, height, height, height)
        }
        super.paintComponent(g)
    }
}
