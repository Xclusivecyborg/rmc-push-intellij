package com.xclusivecyborg.rmcpush.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import com.xclusivecyborg.rmcpush.model.ConfigEntry
import com.xclusivecyborg.rmcpush.model.ConfigSection
import com.xclusivecyborg.rmcpush.model.ParameterChange
import com.xclusivecyborg.rmcpush.model.ViewState
import com.xclusivecyborg.rmcpush.session.RmcPushSession
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.JComponent

/**
 * Builds the real tool window inside a headless IDE, walks every screen and
 * paints each one. The logic tests cannot catch a control that throws while
 * it is constructed or painted — this does, and that is what left the tool
 * window saying "Nothing to show".
 */
class ToolWindowSmokeTest : BasePlatformTestCase() {

    private fun entry(key: String, value: String, type: String, group: String? = null, conditions: Int = 0) =
        ConfigEntry(key, value, type, group, conditionCount = conditions, usesInAppDefault = false)

    private val ready = ViewState.Ready(
        projectId = "flutter-benders-app",
        accountPath = "/tmp/service-account.json",
        sections = listOf(
            ConfigSection(null, null, listOf(
                entry("app_min_version", "2.4.0", "NUMBER"),
                entry("enable_new_home", "true", "BOOLEAN"),
                entry("features", "{\"chat\":true}", "JSON"),
                entry("welcome_message", "Welcome back!", "STRING", conditions = 2)
            )),
            ConfigSection("Ops", "Operational switches", listOf(
                entry("maintenance_mode", "false", "BOOLEAN", "Ops")
            )),
            ConfigSection("Empty", null, emptyList())
        )
    )

    private lateinit var panel: RmcPushPanel
    private lateinit var session: RmcPushSession

    override fun setUp() {
        super.setUp()
        session = RmcPushSession.getInstance(project)
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        panel = RmcPushPanel(project, disposable)
        panel.setSize(420, 900)
    }

    /** Lays out and paints everything currently showing, as the IDE would. */
    private fun paint() {
        layOut(panel)
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            panel.paint(g)
        } finally {
            g.dispose()
        }
    }

    private fun layOut(c: Component) {
        if (c is Container) {
            c.doLayout()
            c.components.forEach { layOut(it) }
        }
    }

    private inline fun <reified T : JComponent> all(): List<T> =
        UIUtil.findComponentsOfType(panel, T::class.java).filter { isShowing(it) }

    /** Visible within the panel — CardLayout and hidden panes keep their children. */
    private fun isShowing(c: Component): Boolean {
        var current: Component? = c
        while (current != null && current !== panel) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return current === panel
    }

    private fun accent(text: String) = all<AccentButton>().single { it.text.startsWith(text) }
    private fun outline(text: String) = all<OutlineButton>().single { it.text == text }
    private fun link(text: String) = all<ActionLink>().first { it.text == text }
    private fun parameterList() = all<JBList<*>>().single()

    fun `test every screen builds and paints`() {
        // Not connected, then connected.
        paint()
        session.showForTest(ready)
        paint()

        // Each type tab, then back to All.
        listOf("String", "Number", "Boolean", "JSON", "All").forEach { label ->
            all<Chip>().first { it.text == label }.doClick()
            paint()
        }

        // Fold and unfold a group from the keyboard path the header uses.
        val list = parameterList()
        val header = (0 until list.model.size).first { list.model.getElementAt(it) is Row.Header }
        list.selectedIndex = header
        paint()

        // Open each parameter's editor.
        listOf("app_min_version", "enable_new_home", "features", "welcome_message", "maintenance_mode").forEach { key ->
            val index = (0 until list.model.size).first {
                (list.model.getElementAt(it) as? Row.Param)?.item?.key == key
            }
            list.selectedIndex = index
            paint()
        }

        // Back from the details panel.
        link("Back").doClick()
        paint()

        // New parameter page, and back.
        accent("New parameter").doClick()
        paint()
        link("Back").doClick()
        paint()

        // Batch Push page: both tabs, then staging, then back.
        outline("Batch Push").doClick()
        paint()
        all<Chip>().first { it.text == "Import JSON" }.doClick()
        paint()
        all<Chip>().first { it.text == "Add one by one" }.doClick()
        session.stageAll(listOf(
            ParameterChange("promo_title", "Big sale", "STRING", "Promo", isNew = true),
            ParameterChange("maintenance_mode", "true", "BOOLEAN", "Ops", isNew = false)
        ))
        paint()
        link("Back").doClick()
        paint()

        assertEquals(2, session.stagedChanges().size)
    }

    fun `test icons on the accent buttons are drawn white`() {
        // The test platform swaps the IDE's icons for blank placeholders, so
        // use a grey square standing in for one: grey is what the IDE's icons
        // look like on the accent fill without the recolouring.
        val grey = object : javax.swing.Icon {
            override fun paintIcon(c: Component?, g: java.awt.Graphics, x: Int, y: Int) {
                g.color = java.awt.Color(0x6C707E)
                g.fillRect(x, y, 16, 16)
            }
            override fun getIconWidth() = 16
            override fun getIconHeight() = 16
        }
        // No label, so the only thing painted on the accent fill is the icon.
        val button = AccentButton("", grey).apply { setSize(60, 36) }
        val image = BufferedImage(60, 36, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            button.paint(g)
        } finally {
            g.dispose()
        }

        val centre = image.getRGB(30, 18)
        assertEquals("icon centre should be white", 0xFFFFFF, centre and 0xFFFFFF)
    }
}
