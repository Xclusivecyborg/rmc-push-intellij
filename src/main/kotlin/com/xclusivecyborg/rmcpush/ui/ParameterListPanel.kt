package com.xclusivecyborg.rmcpush.ui

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.xclusivecyborg.rmcpush.model.ConfigEntry
import com.xclusivecyborg.rmcpush.model.ConfigSection
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The browse screen: create button, filter, and the grouped parameter tree.
 *
 * Held as a single long-lived instance rather than rebuilt per render, so the
 * filter text and which groups are collapsed survive a refresh.
 */
class ParameterListPanel(
    private val onCreate: () -> Unit,
    private val onOpen: (ConfigEntry) -> Unit
) : JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val filterField = SearchTextField()

    private var sections: List<ConfigSection> = emptyList()

    /** Group names the user has collapsed; "" is the root section. */
    private val collapsedGroups = mutableSetOf<String>()

    /** Suppresses expansion bookkeeping while the model is being rebuilt. */
    private var rebuilding = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = EntryRenderer()
        tree.emptyText.text = "No parameters"

        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                if (!rebuilding) sectionKeyOf(event.path)?.let(collapsedGroups::remove)
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                if (!rebuilding) sectionKeyOf(event.path)?.let(collapsedGroups::add)
            }
        })

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val entry = selectedEntry() ?: return false
                onOpen(entry)
                return true
            }
        }.installOn(tree)

        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                selectedEntry()?.let(onOpen)
            }
        }.registerCustomShortcutSet(CommonShortcuts.ENTER, tree)

        filterField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuildTree()
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

        add(top, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    fun setSections(sections: List<ConfigSection>) {
        this.sections = sections
        rebuildTree()
    }

    private fun rebuildTree() {
        val needle = filterField.text.trim().lowercase()
        val filtering = needle.isNotEmpty()

        val visible = sections.mapNotNull { section ->
            val entries =
                if (!filtering) section.entries
                else section.entries.filter { it.key.lowercase().contains(needle) }
            // A filter that matches nothing in a group hides the group entirely.
            if (filtering && entries.isEmpty()) null else section.copy(entries = entries)
        }

        rebuilding = true
        try {
            root.removeAllChildren()
            for (section in visible) {
                val sectionNode = DefaultMutableTreeNode(section)
                for (entry in section.entries) {
                    sectionNode.add(DefaultMutableTreeNode(entry))
                }
                root.add(sectionNode)
            }
            treeModel.reload()

            for (i in 0 until root.childCount) {
                val node = root.getChildAt(i) as DefaultMutableTreeNode
                val key = (node.userObject as ConfigSection).group ?: ""
                val path = TreePath(arrayOf<Any>(root, node))
                // While filtering, show every match regardless of what the user
                // had collapsed — otherwise a hit can hide inside a closed group.
                if (filtering || key !in collapsedGroups) tree.expandPath(path) else tree.collapsePath(path)
            }
        } finally {
            rebuilding = false
        }

        tree.emptyText.text = when {
            sections.all { it.entries.isEmpty() } -> "This project has no parameters yet"
            visible.isEmpty() -> "No parameters match the filter"
            else -> "No parameters"
        }
    }

    private fun selectedEntry(): ConfigEntry? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ConfigEntry

    private fun sectionKeyOf(path: TreePath): String? =
        ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ConfigSection)?.let { it.group ?: "" }

    private class EntryRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            when (val payload = (value as? DefaultMutableTreeNode)?.userObject) {
                is ConfigSection -> {
                    append(payload.group ?: "Parameters", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${payload.entries.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = payload.description
                }

                is ConfigEntry -> {
                    append(payload.key)
                    append("  ${payload.valueType}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    val preview = if (payload.usesInAppDefault) "(in-app default)" else payload.value
                    if (preview.isNotEmpty()) {
                        // Values are free-form and may contain newlines, which
                        // would otherwise break the row into an unreadable blob.
                        append("  " + preview.replace('\n', ' ').take(60), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    if (payload.conditionCount > 0) {
                        append("  +${payload.conditionCount} cond.", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        toolTipText = "${payload.conditionCount} conditional value(s) — edited in the Firebase console"
                    }
                }
            }
        }
    }
}
