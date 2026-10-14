package com.xclusivecyborg.rmcpush.ui

import com.xclusivecyborg.rmcpush.model.ConfigEntry
import com.xclusivecyborg.rmcpush.model.ConfigSection
import com.xclusivecyborg.rmcpush.model.ParameterChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamItemTest {

    private fun entry(key: String, value: String, group: String? = null) =
        ConfigEntry(key, value, "STRING", group, conditionCount = 0, usesInAppDefault = false)

    private val sections = listOf(
        ConfigSection(null, null, listOf(entry("a", "1"))),
        ConfigSection("G", null, listOf(entry("b", "2", "G")))
    )

    @Test
    fun `with nothing staged the list is exactly what Firebase holds`() {
        val items = itemsOf(sections, emptyList())
        assertEquals(listOf("a", "b"), items.map { it.key })
        assertTrue(items.all { it.staged == null })
    }

    @Test
    fun `a staged edit overlays its parameter but keeps its group`() {
        val items = itemsOf(sections, listOf(ParameterChange("b", "9", "NUMBER", "G", isNew = false)))
        val b = items.single { it.key == "b" }
        assertEquals("9", b.value)
        assertEquals("NUMBER", b.valueType)
        assertEquals("G", b.group)
        assertEquals("2", b.remote?.value)
    }

    @Test
    fun `a staged new parameter is listed after the fetched ones`() {
        val items = itemsOf(sections, listOf(ParameterChange("c", "x", "STRING", null, isNew = true)))
        assertEquals(listOf("a", "b", "c"), items.map { it.key })
        assertTrue(items.last().isNew)
        assertNull(items.last().remote)
    }

    private fun shape(rows: List<Row>) = rows.map {
        when (it) {
            is Row.Header -> "[${it.group ?: "root"} ${it.count}${if (it.expanded) "" else " folded"}]"
            is Row.Param -> it.item.key
        }
    }

    @Test
    fun `parameters are listed under their group headers, root first`() {
        val rows = rowsOf(sections, itemsOf(sections, emptyList()), emptySet(), filtering = false)
        assertEquals(listOf("[root 1]", "a", "[G 1]", "b"), shape(rows))
    }

    @Test
    fun `a folded group keeps its header but hides its parameters`() {
        val rows = rowsOf(sections, itemsOf(sections, emptyList()), setOf("G"), filtering = false)
        assertEquals(listOf("[root 1]", "a", "[G 1 folded]"), shape(rows))
    }

    @Test
    fun `while filtering, empty groups drop out and folded ones open`() {
        val matching = itemsOf(sections, emptyList()).filter { it.key == "b" }
        val rows = rowsOf(sections, matching, setOf("G"), filtering = true)
        assertEquals(listOf("[G 1]", "b"), shape(rows))
    }

    @Test
    fun `an empty group still shows when nothing is filtered`() {
        val withEmpty = sections + ConfigSection("Empty", null, emptyList())
        val rows = rowsOf(withEmpty, itemsOf(withEmpty, emptyList()), emptySet(), filtering = false)
        assertEquals("[Empty 0]", shape(rows).last())
    }

    @Test
    fun `a staged parameter in a brand-new group gets its own header`() {
        val items = itemsOf(sections, listOf(ParameterChange("c", "x", "STRING", "Fresh", isNew = true)))
        val rows = rowsOf(sections, items, emptySet(), filtering = false)
        assertEquals(listOf("[Fresh 1]", "c"), shape(rows).takeLast(2))
    }

    @Test
    fun `a card previews the value on one line, staged value first`() {
        val multiLine = ParamItem(entry("j", "{\n  \"a\": 1\n}"), null)
        assertEquals("{ \"a\": 1 }", previewOf(multiLine))

        val staged = ParamItem(entry("a", "1"), ParameterChange("a", "2", "STRING", null, isNew = false))
        assertEquals("2", previewOf(staged))

        val inAppDefault = ParamItem(entry("d", "").copy(usesInAppDefault = true), null)
        assertEquals("(in-app default)", previewOf(inAppDefault))
    }
}
