package com.xclusivecyborg.rmcpush.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.xclusivecyborg.rmcpush.model.ViewState
import com.xclusivecyborg.rmcpush.session.RmcPushSession
import com.xclusivecyborg.rmcpush.settings.PluginSettingsConfigurable
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GridBagLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The tool window's content: a function of the session's [ViewState] plus the
 * batch the session holds.
 *
 * Holds only navigation — what is selected, what the search says — so the IDE
 * can drop and rebuild this component without losing the connection or the
 * batch behind it.
 */
class RmcPushPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private val session = RmcPushSession.getInstance(project)

    private val spinner = AsyncProcessIcon("rmcPushLoading")

    /** Kept across Busy/Error so the search, tab and selection survive a reload. */
    private val workspace = Workspace()

    init {
        Disposer.register(parent, spinner)
        session.addListener(parent) { state -> render(state) }
        render(session.getState())

        // First open of the tool window. If a previous panel already connected,
        // the session is past NoAccount and there is nothing to redo.
        if (session.getState() is ViewState.NoAccount) {
            session.connect()
        }
    }

    private fun render(state: ViewState) {
        removeAll()
        when (state) {
            is ViewState.NoAccount -> add(
                placeholder(
                    "Select a Firebase service account JSON file to connect this project " +
                        "to a Remote Config project.",
                    primaryText = "Select service account…",
                    primaryAction = { session.selectAccount() }
                ),
                BorderLayout.CENTER
            )

            is ViewState.Busy -> add(busyPane(state.message), BorderLayout.CENTER)

            is ViewState.Error -> add(
                placeholder(
                    state.message,
                    primaryText = "Retry",
                    primaryAction = { session.refresh() },
                    secondaryText = if (state.hasAccount) "Use a different service account"
                    else "Select a service account",
                    secondaryAction = { session.selectAccount() },
                    messageColor = Palette.ERROR
                ),
                BorderLayout.CENTER
            )

            is ViewState.Ready -> {
                workspace.update(state)
                add(workspace, BorderLayout.CENTER)
            }
        }
        revalidate()
        repaint()
    }

    /**
     * The connected screen: project header, the parameter list beside (or,
     * when narrow, above) the editor, and the batch bar.
     */
    private inner class Workspace : JPanel(BorderLayout()), EditorHost, ComposerHost {

        private val projectChip = ProjectChip()

        private val browser = ParameterBrowser(
            onSelect = { item -> if (item == null) showEmpty() else openEditor(item) },
            onCreate = { openCreate() },
            onBatch = { openComposer() }
        )

        private val editorHost = JPanel(BorderLayout()).apply { isOpaque = false }

        private val splitter = JBSplitter(false, 0.42f).apply {
            firstComponent = browser
            secondComponent = editorHost
            dividerWidth = JBUI.scale(1)
        }

        /** New parameter and Batch Push open here, over the list, with a way back. */
        private val pageHost = JPanel(BorderLayout()).apply { isOpaque = false }
        private val pageView = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(Palette.OUTLINE, 0, 0, 1, 0),
                    JBUI.Borders.empty(6, 10)
                )
                add(ActionLink("Back") { closePage() }.apply {
                    icon = AllIcons.Actions.Back
                    toolTipText = "Back to the parameter list (Esc)"
                }, BorderLayout.WEST)
            }, BorderLayout.NORTH)
            add(pageHost, BorderLayout.CENTER)
        }

        /** Swaps between the list (with its editor) and an open page. */
        private val screens = CardLayout()
        private val screenHost = JPanel(screens).apply {
            isOpaque = false
            add(splitter, LIST_SCREEN)
            add(pageView, PAGE_SCREEN)
        }

        private val batchBar = BatchBar(onReview = { reviewBatch() }, onDiscard = { discardBatch() })

        /** The editor beside the list, or null while nothing is selected. */
        private var editor: ParameterEditor? = null

        /** The open page, or null while the list is showing. */
        private var page: Page? = null

        /** The New parameter form while that page is open. */
        private var createForm: ParameterEditor? = null

        /** What was selected when a page opened, so Back returns to it. */
        private var keyBeforePage: String? = null

        /**
         * The Batch Push view. Kept once built, so leaving it to look at a
         * parameter and coming back does not lose a half-typed entry or import.
         */
        private val composer by lazy { BatchComposer(project, session, this) }
        private var items: List<ParamItem> = emptyList()
        private var groups: List<String> = emptyList()

        /** Set by [reveal]; consumed by the first update whose items include the key. */
        private var pendingReveal: Pair<String, String>? = null

        init {
            add(header(), BorderLayout.NORTH)
            add(screenHost, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 10, 10, 10)
                add(batchBar, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)

            showEmpty()
            pageView.onKey("ESCAPE", JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT) { closePage() }

            // Side by side when there is room, as in a wide tool window or an
            // undocked one; stacked in the usual narrow right-hand dock.
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    val stacked = width < JBUI.scale(SIDE_BY_SIDE_MIN_WIDTH)
                    if (splitter.orientation != stacked) splitter.orientation = stacked
                }
            })
        }

        fun update(state: ViewState.Ready) {
            projectChip.projectId = state.projectId
            projectChip.toolTipText = state.accountPath

            items = itemsOf(state.sections, session.stagedChanges())
            groups = state.sections.mapNotNull { it.group }

            val reveal = pendingReveal?.takeIf { (key, _) -> items.any { it.key == key } }
            if (reveal != null) {
                // A new parameter was just pushed or staged from its page:
                // back to the list, on that parameter.
                pendingReveal = null
                val (key, message) = reveal
                closePage(reopen = false)
                browser.setItems(items, state.sections, key)
                openEditor(items.first { it.key == key })
                editor?.showStatus(message, Palette.SUCCESS)
            } else if (page != null) {
                browser.setItems(items, state.sections, keyBeforePage)
                if (page == Page.BATCH) composer.sync()
            } else {
                val selected = editor?.key
                browser.setItems(items, state.sections, selected)
                if (selected != null) {
                    val current = items.firstOrNull { it.key == selected }
                    // Gone upstream, or unstaged before it was ever pushed.
                    if (current == null) showEmpty() else editor?.sync(current)
                }
            }

            batchBar.update(session.stagedChanges().size)
        }

        override fun takenKeys(): Set<String> = items.mapTo(HashSet()) { it.key }

        override fun groupNames(): List<String> = groups

        override fun reveal(key: String, message: String) {
            pendingReveal = key to message
        }

        override fun closeEditor() {
            browser.select(null)
            showEmpty()
        }

        override fun remoteEntries() = items.mapNotNull { it.remote }

        override fun open(key: String) {
            val item = items.firstOrNull { it.key == key } ?: return
            closePage(reopen = false)
            browser.select(key)
            openEditor(item)
        }

        private fun openCreate() {
            val form = ParameterEditor(session, null, this)
            createForm = form
            showPage(Page.CREATE, form)
            SwingUtilities.invokeLater { form.focusFirstField() }
        }

        private fun openComposer() {
            showPage(Page.BATCH, composer)
            composer.sync()
            SwingUtilities.invokeLater { composer.focusFirstField() }
        }

        private fun showPage(next: Page, content: JComponent) {
            if (page == null) keyBeforePage = editor?.key
            page = next
            if (next != Page.CREATE) createForm = null
            pageHost.removeAll()
            pageHost.add(content, BorderLayout.CENTER)
            screens.show(screenHost, PAGE_SCREEN)
            screenHost.revalidate()
            screenHost.repaint()
        }

        /**
         * Back to the list. With [reopen], the parameter that was selected
         * before the page opened gets its editor back.
         */
        private fun closePage(reopen: Boolean = true) {
            if (page == null) return
            page = null
            createForm = null
            pageHost.removeAll()
            screens.show(screenHost, LIST_SCREEN)
            if (reopen) {
                val item = keyBeforePage?.let { key -> items.firstOrNull { it.key == key } }
                browser.select(item?.key)
                if (item != null) openEditor(item) else showEmpty()
            }
            keyBeforePage = null
        }

        private fun openEditor(item: ParamItem) {
            val next = ParameterEditor(session, item, this)
            editor = next
            editorHost.removeAll()
            editorHost.add(next, BorderLayout.CENTER)
            splitter.secondComponent = editorHost
            browser.setHintVisible(false)
            splitter.revalidate()
            splitter.repaint()
        }

        /** Nothing selected: the list takes the whole height and says how to start. */
        private fun showEmpty() {
            editor = null
            editorHost.removeAll()
            splitter.secondComponent = null
            browser.setHintVisible(true)
            splitter.revalidate()
            splitter.repaint()
        }

        private fun reviewBatch() {
            val staged = session.stagedChanges()
            if (staged.isEmpty()) return
            val remote = items.mapNotNull { it.remote }.associateBy { it.key }
            val dialog = BatchReviewDialog(project, staged, remote)
            if (!dialog.showAndGet()) return

            val chosen = dialog.selected
            batchBar.setBusy(true)
            session.pushStaged(chosen) { failure ->
                batchBar.setBusy(false)
                if (failure == null) {
                    batchBar.showResult(
                        "Pushed ${chosen.size} change${if (chosen.size == 1) "" else "s"} to Firebase.",
                        Palette.SUCCESS
                    )
                } else {
                    batchBar.showResult(failure, Palette.ERROR)
                }
            }
        }

        private fun discardBatch() {
            val n = session.stagedChanges().size
            val answer = Messages.showYesNoDialog(
                project,
                "Discard $n staged change${if (n == 1) "" else "s"}? Nothing has been pushed yet.",
                "Discard Batch",
                "Discard",
                "Keep",
                null
            )
            if (answer == Messages.YES) {
                batchBar.clearResult()
                session.discardStaged()
            }
        }

        private fun header(): JComponent = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(Palette.OUTLINE, 0, 0, 1, 0),
                JBUI.Borders.empty(10)
            )
            add(JBLabel("Active project").apply {
                font = JBFont.small()
                foreground = Palette.MUTED
            }, BorderLayout.WEST)
            add(projectChip, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(IconButton(AllIcons.Actions.Refresh, "Reload Remote Config").onClick { session.refresh() })
                add(IconButton(AllIcons.General.GearPlain, "Firebase Push settings").onClick {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable::class.java)
                })
            }, BorderLayout.EAST)
        }
    }

    /**
     * The connected project, shown like a selector. There is one project per
     * service account, so "switching" means choosing a different account.
     */
    private inner class ProjectChip : Clickable() {

        var projectId: String = ""
            set(value) {
                field = value
                setAccessibleName("Active project: $value")
                repaint()
            }

        init {
            font = JBFont.label()
            addClickListener { showMenu() }
        }

        private fun showMenu() {
            val change = "Use a different service account…"
            val disconnect = "Disconnect"
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(listOf(change, disconnect))
                .setItemChosenCallback { choice ->
                    when (choice) {
                        change -> session.selectAccount()
                        disconnect -> session.clearAccount()
                    }
                }
                .createPopup()
                .showUnderneathOf(this)
        }

        override fun getPreferredSize() = Dimension(JBUI.scale(160), JBUI.scale(30))

        override fun getMinimumSize() = Dimension(JBUI.scale(60), JBUI.scale(30))

        override fun paintComponent(g: Graphics) {
            g.antialiased { g2 ->
                val arc = JBUI.scale(Radius.CONTROL)
                g2.color = if (hovered) Palette.HOVER else Palette.SURFACE
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = if (hovered) Palette.ACCENT else Palette.OUTLINE
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                paintFocusRing(g2, arc)

                val dot = JBUI.scale(8)
                val left = JBUI.scale(10)
                g2.color = Palette.SUCCESS
                g2.fillOval(left, (height - dot) / 2, dot, dot)

                // Chevron at the right edge.
                val cx = width - JBUI.scale(16)
                val cy = height / 2 - JBUI.scale(1)
                val s = JBUI.scale(3)
                g2.color = Palette.MUTED
                g2.stroke = java.awt.BasicStroke(JBUI.scale(1.4f))
                g2.drawLine(cx, cy, cx + s, cy + s)
                g2.drawLine(cx + s, cy + s, cx + 2 * s, cy)

                g2.font = font
                g2.color = UIUtil.getLabelForeground()
                val fm = g2.fontMetrics
                val textX = left + dot + JBUI.scale(8)
                val room = cx - JBUI.scale(8) - textX
                var text = projectId
                if (fm.stringWidth(text) > room) {
                    while (text.isNotEmpty() && fm.stringWidth("$text…") > room) text = text.dropLast(1)
                    text = "$text…"
                }
                g2.drawString(text, textX, (height - fm.height) / 2 + fm.ascent)
            }
        }
    }

    private fun busyPane(message: String): JComponent = centered(
        listOf(
            spinner.apply { alignmentX = Component.CENTER_ALIGNMENT },
            Box.createVerticalStrut(8),
            JBLabel(message).apply { alignmentX = Component.CENTER_ALIGNMENT }
        )
    )

    private fun placeholder(
        message: String,
        primaryText: String,
        primaryAction: () -> Unit,
        secondaryText: String? = null,
        secondaryAction: (() -> Unit)? = null,
        messageColor: java.awt.Color? = null
    ): JComponent {
        val rows = mutableListOf<Component>(
            // JBLabel wraps only when told to; API errors can be long.
            JBLabel("<html><body style='text-align:center'>${escapeHtml(message)}</body></html>").apply {
                alignmentX = Component.CENTER_ALIGNMENT
                messageColor?.let { foreground = it }
            },
            Box.createVerticalStrut(12),
            AccentButton(primaryText).onClick(primaryAction).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                maximumSize = preferredSize
            }
        )
        if (secondaryText != null && secondaryAction != null) {
            rows += Box.createVerticalStrut(10)
            rows += ActionLink(secondaryText) { secondaryAction() }.apply {
                alignmentX = Component.CENTER_ALIGNMENT
            }
        }
        return centered(rows)
    }

    private fun centered(rows: List<Component>): JComponent {
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(20, 16)
            rows.forEach { add(it) }
        }
        // GridBagLayout with no constraints centres its single child.
        return JPanel(GridBagLayout()).apply { add(column) }
    }

    private companion object {
        const val LIST_SCREEN = "list"
        const val PAGE_SCREEN = "page"

        /** Below this width the list and editor stack instead of sitting side by side. */
        const val SIDE_BY_SIDE_MIN_WIDTH = 620
    }
}

/** A full-page view over the parameter list. */
private enum class Page { CREATE, BATCH }
