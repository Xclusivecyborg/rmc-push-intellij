package com.xclusivecyborg.rmcpush.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.image.RGBImageFilter
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * The panel's palette. Everything is a [JBColor] so light and dark themes each
 * get a value chosen for them rather than one colour that is wrong in one.
 *
 * The accent is the plugin's brand violet rather than the IDE's link blue, so
 * the panel reads as one product; everything else defers to the theme.
 */
internal object Palette {
    val ACCENT = JBColor(Color(0x6D4AFF), Color(0x8B6CFF))
    val ON_ACCENT: Color = JBColor(Color.WHITE, Color.WHITE)
    val ACCENT_SOFT = JBColor(Color(0x6D, 0x4A, 0xFF, 28), Color(0x8B, 0x6C, 0xFF, 46))
    val ACCENT_TEXT = JBColor(Color(0x5A37E6), Color(0xB9A6FF))

    /** Cards and inset fields sit one step off the panel background. */
    val SURFACE = JBColor(Color(0xFFFFFF), Color(0x2B2D30))
    val SURFACE_SUNKEN = JBColor(Color(0xF4F4F8), Color(0x1E1F22))
    val OUTLINE = JBColor(Color(0, 0, 0, 30), Color(255, 255, 255, 26))
    val HOVER = JBColor(Color(0, 0, 0, 12), Color(255, 255, 255, 14))

    val SUCCESS = JBColor(Color(0x1F9D55), Color(0x4CC38A))
    val WARNING = JBColor(Color(0x9A6700), Color(0xE0B341))
    val ERROR: Color = JBColor.namedColor("Label.errorForeground", JBColor(Color(0xD92D20), Color(0xF97066)))
    val MUTED: Color get() = UIUtil.getContextHelpForeground()

    val CODE_KEY = JBColor(Color(0x6D4AFF), Color(0xB9A6FF))
    val CODE_STRING = JBColor(Color(0xC2410C), Color(0xF59E7A))
    val CODE_LITERAL = JBColor(Color(0x0E7490), Color(0x67D4E5))
}

/** Arc diameters, unscaled. */
internal object Radius {
    const val CARD = 12
    const val CONTROL = 10
}

/** Display name for a Firebase value type: "BOOLEAN" → "Boolean". */
internal fun typeLabel(valueType: String): String =
    if (valueType == "JSON") "JSON" else valueType.lowercase().replaceFirstChar { it.uppercase() }

internal fun monospaceFont(): Font {
    val editor = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
    return JBFont.create(Font(editor.family, Font.PLAIN, JBFont.label().size))
}

internal inline fun Graphics.antialiased(block: (Graphics2D) -> Unit) {
    val g2 = create() as Graphics2D
    try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        block(g2)
    } finally {
        g2.dispose()
    }
}

/** A rounded, optionally outlined box. Used for cards, the value field and the preview. */
internal open class RoundedPanel(
    layout: LayoutManager,
    private val fill: () -> Color? = { Palette.SURFACE },
    private val outline: () -> Color? = { Palette.OUTLINE },
    private val arc: Int = Radius.CARD
) : JPanel(layout) {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        g.antialiased { g2 ->
            val a = JBUI.scale(arc)
            fill()?.let {
                g2.color = it
                g2.fillRoundRect(0, 0, width - 1, height - 1, a, a)
            }
            outline()?.let {
                g2.color = it
                g2.drawRoundRect(0, 0, width - 1, height - 1, a, a)
            }
        }
    }
}

/**
 * Base for the custom-painted controls: tracks hover and press, fires on click
 * or Space/Enter, and repaints its focus ring. Subclasses only paint.
 */
internal abstract class Clickable : JComponent() {

    protected var hovered = false
        private set
    protected var pressed = false
        private set

    private val listeners = mutableListOf<() -> Unit>()

    fun addClickListener(listener: () -> Unit) {
        listeners += listener
    }

    init {
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                pressed = false
                repaint()
            }

            override fun mousePressed(e: MouseEvent) {
                if (!isEnabled) return
                pressed = true
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                val fire = pressed && isEnabled && contains(e.point)
                pressed = false
                repaint()
                if (fire) fire()
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
        onKey("SPACE") { if (isEnabled) fire() }
        onKey("ENTER") { if (isEnabled) fire() }
    }

    protected fun fire() = listeners.toList().forEach { it() }

    /** Clicks it, as a mouse or Space/Enter would. */
    fun doClick() {
        if (isEnabled) fire()
    }

    /**
     * A bare JComponent has no accessible context — getAccessibleContext()
     * returns null — so these controls make their own, as buttons. Without
     * it, naming one for screen readers throws.
     */
    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) {
            accessibleContext = object : AccessibleJComponent() {
                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PUSH_BUTTON
            }
        }
        return accessibleContext
    }

    /** The name a screen reader announces. */
    fun setAccessibleName(name: String) {
        getAccessibleContext().accessibleName = name
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        cursor = Cursor.getPredefinedCursor(if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        repaint()
    }

    /** Draws the keyboard focus ring just inside the bounds. */
    protected fun paintFocusRing(g2: Graphics2D, arc: Int) {
        if (!isFocusOwner) return
        g2.color = JBUI.CurrentTheme.Focus.focusColor()
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
        val inset = JBUI.scale(1)
        g2.drawRoundRect(inset, inset, width - 1 - 2 * inset, height - 1 - 2 * inset, arc, arc)
    }
}

/** Registers [listener] and returns the control itself, for use in builders. */
internal fun <T : Clickable> T.onClick(listener: () -> Unit): T = apply { addClickListener(listener) }

/** A filter tab: "All", "String", … Filled with the accent when selected. */
internal class Chip(val text: String) : Clickable() {

    var selected = false
        set(value) {
            field = value
            repaint()
        }

    init {
        font = JBFont.small()
        toolTipText = null
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text) + JBUI.scale(20), fm.height + JBUI.scale(10))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        g.antialiased { g2 ->
            val arc = JBUI.scale(Radius.CONTROL)
            when {
                selected -> {
                    g2.color = Palette.ACCENT
                    g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                }

                hovered -> {
                    g2.color = Palette.HOVER
                    g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                }
            }
            if (!selected) {
                g2.color = Palette.OUTLINE
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
            paintFocusRing(g2, arc)
            g2.font = font
            g2.color = if (selected) Palette.ON_ACCENT else UIUtil.getLabelForeground()
            val fm = g2.fontMetrics
            g2.drawString(text, (width - fm.stringWidth(text)) / 2, (height - fm.height) / 2 + fm.ascent)
        }
    }
}

/**
 * A small tinted label — a parameter's type. With [dropdown] it shows a chevron
 * and is clickable, which is how an existing parameter's type is changed.
 */
internal class Badge(text: String, private val dropdown: Boolean = false) : Clickable() {

    var text: String = text
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    init {
        font = JBFont.small()
        if (!dropdown) {
            isFocusable = false
            cursor = Cursor.getDefaultCursor()
        }
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val chevron = if (dropdown) JBUI.scale(12) else 0
        return Dimension(fm.stringWidth(text) + JBUI.scale(14) + chevron, fm.height + JBUI.scale(6))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        g.antialiased { g2 ->
            val arc = JBUI.scale(8)
            g2.color = Palette.ACCENT_SOFT
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            if (dropdown && hovered) {
                g2.color = Palette.ACCENT
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
            paintFocusRing(g2, arc)
            g2.font = font
            g2.color = Palette.ACCENT_TEXT
            val fm = g2.fontMetrics
            val baseline = (height - fm.height) / 2 + fm.ascent
            g2.drawString(text, JBUI.scale(7), baseline)
            if (dropdown) {
                val x = width - JBUI.scale(12)
                val y = height / 2 - JBUI.scale(1)
                val s = JBUI.scale(3)
                g2.stroke = BasicStroke(JBUI.scale(1.4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(x, y, x + s, y + s)
                g2.drawLine(x + s, y + s, x + 2 * s, y)
            }
        }
    }
}

/** An on/off switch, for BOOLEAN values. */
internal class ToggleSwitch(on: Boolean) : Clickable() {

    var isOn: Boolean = on
        set(value) {
            field = value
            repaint()
        }

    private val changeListeners = mutableListOf<() -> Unit>()

    fun onToggle(listener: () -> Unit) = apply { changeListeners += listener }

    init {
        addClickListener {
            isOn = !isOn
            changeListeners.forEach { it() }
        }
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(40), JBUI.scale(22))

    override fun getMinimumSize() = preferredSize

    override fun getMaximumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        g.antialiased { g2 ->
            val h = height
            g2.color = if (isOn) Palette.ACCENT else Palette.OUTLINE
            g2.fillRoundRect(0, 0, width, h, h, h)
            paintFocusRing(g2, h)
            val pad = JBUI.scale(3)
            val d = h - 2 * pad
            g2.color = Color.WHITE
            g2.fillOval(if (isOn) width - pad - d else pad, pad, d, d)
        }
    }
}

/** The primary call to action: a filled accent button, full width where it sits. */
internal class AccentButton(text: String, icon: Icon? = null) : Clickable() {

    /**
     * The icon in the label's colour: the IDE's own icons are drawn for a plain
     * background and read as grey on the accent fill. Each pixel keeps its
     * alpha — the icon's shape — and takes the label colour. (IconUtil.colorize
     * would not do: it keeps each pixel's brightness, so white comes out grey.)
     */
    private val icon: Icon? = icon?.let { source ->
        IconUtil.filterIcon(source, { SolidColorFilter(Palette.ON_ACCENT.rgb) }, null)
    }
    private val disabledIcon: Icon? = icon?.let { IconLoader.getDisabledIcon(it) }

    var text: String = text
        set(value) {
            field = value
            repaint()
        }

    init {
        font = JBFont.label().asBold()
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val iconWidth = icon?.let { it.iconWidth + JBUI.scale(8) } ?: 0
        return Dimension(fm.stringWidth(text) + iconWidth + JBUI.scale(32), JBUI.scale(36))
    }

    override fun paintComponent(g: Graphics) {
        g.antialiased { g2 ->
            val arc = JBUI.scale(Radius.CONTROL)
            g2.color = when {
                !isEnabled -> Palette.OUTLINE
                pressed -> Palette.ACCENT.darker()
                hovered -> Palette.ACCENT.brighter()
                else -> Palette.ACCENT
            }
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            paintFocusRing(g2, arc)

            g2.font = font
            val fm = g2.fontMetrics
            val iconWidth = icon?.let { it.iconWidth + JBUI.scale(8) } ?: 0
            var x = (width - fm.stringWidth(text) - iconWidth) / 2
            (if (isEnabled) icon else disabledIcon)?.let {
                it.paintIcon(this, g2, x, (height - it.iconHeight) / 2)
                x += iconWidth
            }
            g2.color = if (isEnabled) Palette.ON_ACCENT else Palette.MUTED
            g2.drawString(text, x, (height - fm.height) / 2 + fm.ascent)
        }
    }
}

/** Paints every pixel [rgb], keeping its alpha — an icon recoloured to one colour. */
private class SolidColorFilter(rgb: Int) : RGBImageFilter() {
    private val colour = rgb and 0xFFFFFF

    init {
        canFilterIndexColorModel = true
    }

    override fun filterRGB(x: Int, y: Int, argb: Int): Int = (argb and 0xFF000000.toInt()) or colour
}

/** A secondary button: outlined, theme-coloured text, optionally with an icon. */
internal class OutlineButton(text: String, private val icon: Icon? = null) : Clickable() {

    var text: String = text
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    init {
        font = JBFont.label()
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val iconWidth = icon?.let { it.iconWidth + JBUI.scale(8) } ?: 0
        return Dimension(fm.stringWidth(text) + iconWidth + JBUI.scale(28), JBUI.scale(36))
    }

    override fun paintComponent(g: Graphics) {
        g.antialiased { g2 ->
            val arc = JBUI.scale(Radius.CONTROL)
            if (isEnabled && (hovered || pressed)) {
                g2.color = Palette.HOVER
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
            g2.color = if (isEnabled && hovered) Palette.ACCENT else Palette.OUTLINE
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            paintFocusRing(g2, arc)
            g2.font = font
            val fm = g2.fontMetrics
            val iconWidth = icon?.let { it.iconWidth + JBUI.scale(8) } ?: 0
            var x = (width - fm.stringWidth(text) - iconWidth) / 2
            icon?.let {
                it.paintIcon(this, g2, x, (height - it.iconHeight) / 2)
                x += iconWidth
            }
            g2.color = if (isEnabled) UIUtil.getLabelForeground() else Palette.MUTED
            g2.drawString(text, x, (height - fm.height) / 2 + fm.ascent)
        }
    }
}

/** A square, outlined icon button — refresh, settings, new parameter. */
internal class IconButton(private val icon: Icon, tooltip: String) : Clickable() {

    init {
        toolTipText = tooltip
        setAccessibleName(tooltip)
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(30), JBUI.scale(30))

    override fun getMinimumSize() = preferredSize

    override fun getMaximumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        g.antialiased { g2 ->
            val arc = JBUI.scale(Radius.CONTROL)
            g2.color = when {
                pressed -> Palette.ACCENT_SOFT
                hovered -> Palette.HOVER
                else -> Palette.SURFACE
            }
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = if (hovered) Palette.ACCENT else Palette.OUTLINE
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            paintFocusRing(g2, arc)
            icon.paintIcon(this, g2, (width - icon.iconWidth) / 2, (height - icon.iconHeight) / 2)
        }
    }
}

/**
 * Colours pretty-printed JSON in [doc]: keys, strings, and number/boolean/null
 * literals. A regex pass is enough — the input is always Gson's own output.
 */
internal fun highlightJson(doc: StyledDocument, json: String) {
    doc.remove(0, doc.length)
    doc.insertString(0, json, SimpleAttributeSet())
    val token = Regex(""""(?:\\.|[^"\\])*"(\s*:)?|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?""")
    for (match in token.findAll(json)) {
        val color = when {
            match.groupValues[1].isNotEmpty() -> Palette.CODE_KEY
            match.value.startsWith("\"") -> Palette.CODE_STRING
            else -> Palette.CODE_LITERAL
        }
        val length = if (match.groupValues[1].isNotEmpty()) match.value.length - match.groupValues[1].length
        else match.value.length
        val style = SimpleAttributeSet().apply { StyleConstants.setForeground(this, color) }
        doc.setCharacterAttributes(match.range.first, length, style, false)
    }
}

/**
 * Binds [key] to [action] — by default while this component has focus;
 * [condition] widens that, e.g. to while anything inside it has focus.
 */
internal fun JComponent.onKey(key: String, condition: Int = JComponent.WHEN_FOCUSED, action: () -> Unit) {
    val id = "rmcPush.$key"
    getInputMap(condition).put(KeyStroke.getKeyStroke(key), id)
    actionMap.put(id, object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) = action()
    })
}
