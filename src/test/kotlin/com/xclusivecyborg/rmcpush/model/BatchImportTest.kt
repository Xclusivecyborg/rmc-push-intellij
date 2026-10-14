package com.xclusivecyborg.rmcpush.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors src/test/suite/batchImport.test.ts in the VS Code extension. */
class BatchImportTest {

    private fun entry(key: String, group: String? = null) =
        ConfigEntry(key, "x", "STRING", group, conditionCount = 0, usesInAppDefault = false)

    @Test
    fun `a simple map infers each type from the JSON value`() {
        val result = parseBatchJson(
            """{"flag": true, "min": 2.4, "title": "Hi", "features": {"chat": true}, "list": [1, 2]}""",
            null,
            emptyList()
        )
        assertEquals(ImportResult.Format.MAP, result.format)
        assertEquals(
            listOf(
                Triple("flag", "BOOLEAN", "true"),
                Triple("min", "NUMBER", "2.4"),
                Triple("title", "STRING", "Hi"),
                Triple("features", "JSON", """{"chat":true}"""),
                Triple("list", "JSON", "[1,2]")
            ),
            result.rows.map { Triple(it.key, it.valueType, it.value) }
        )
        assertTrue(result.rows.all { it.isNew && it.error == null })
    }

    @Test
    fun `new keys land in the target group`() {
        assertEquals("Promo", parseBatchJson("""{"a": 1}""", " Promo ", emptyList()).rows.single().group)
    }

    @Test
    fun `an existing key is updated in the group it already lives in`() {
        val (a, b) = parseBatchJson("""{"a": 1, "b": 2}""", "Promo", listOf(entry("a", "Ops"), entry("b", "Promo"))).rows
        assertFalse(a.isNew)
        assertEquals("Ops", a.group)
        assertTrue(a.note!!.contains("group \"Ops\""))
        assertEquals("Promo", b.group)
        assertNull(b.note)
    }

    @Test
    fun `invalid keys and null values are flagged, not dropped`() {
        val rows = parseBatchJson("""{"bad key": 1, "empty": null, "ok": "x"}""", null, emptyList()).rows
        assertNotNull(rows[0].error)
        assertNotNull(rows[1].error)
        assertNull(rows[2].error)
    }

    @Test
    fun `a plain map with a parameters key holding data is still a map`() {
        val result = parseBatchJson("""{"parameters": {"a": 1}}""", null, emptyList())
        assertEquals(ImportResult.Format.MAP, result.format)
        assertEquals("JSON", result.rows.single().valueType)
    }

    private val template = """
        {
          "conditions": [ { "name": "android", "expression": "device.os == 'android'" } ],
          "parameters": {
            "welcome": {
              "defaultValue": { "value": "Hello" },
              "conditionalValues": { "android": { "value": "Hi Android" } },
              "valueType": "STRING"
            },
            "legacy": { "defaultValue": { "useInAppDefault": true } }
          },
          "parameterGroups": {
            "Ops": { "parameters": { "maintenance": { "defaultValue": { "value": "false" }, "valueType": "BOOLEAN" } } }
          },
          "version": { "versionNumber": "12" }
        }
    """.trimIndent()

    @Test
    fun `a template keeps its own groups and types`() {
        val result = parseBatchJson(template, "Ignored", emptyList())
        assertEquals(ImportResult.Format.TEMPLATE, result.format)
        val maintenance = result.rows.single { it.key == "maintenance" }
        assertEquals("Ops", maintenance.group)
        assertEquals("BOOLEAN", maintenance.valueType)
        assertNull(result.rows.single { it.key == "welcome" }.group)
    }

    @Test
    fun `a template parameter without a default value is flagged`() {
        assertNotNull(parseBatchJson(template, null, emptyList()).rows.single { it.key == "legacy" }.error)
    }

    @Test
    fun `a template with conditions warns they are not imported`() {
        assertTrue(parseBatchJson(template, null, emptyList()).warnings.any { it.contains("Conditions") })
    }

    @Test
    fun `a key in both root and a group is flagged the second time`() {
        val rows = parseBatchJson(
            """{"parameters": {"a": {"defaultValue": {"value": "1"}}},
               "parameterGroups": {"G": {"parameters": {"a": {"defaultValue": {"value": "2"}}}}}}""",
            null,
            emptyList()
        ).rows
        assertNull(rows[0].error)
        assertNotNull(rows[1].error)
    }

    @Test
    fun `empty input is not an error, malformed input and non-objects are`() {
        assertNull(parseBatchJson("   ", null, emptyList()).error)
        assertNotNull(parseBatchJson("""{"a": """, null, emptyList()).error)
        assertNotNull(parseBatchJson("[1, 2]", null, emptyList()).error)
    }

    @Test
    fun `warns when the project would exceed the parameter limit`() {
        val existing = (0 until MAX_PARAMETERS).map { entry("k$it") }
        val result = parseBatchJson("""{"one_more": 1}""", null, existing)
        assertTrue(result.warnings.any { it.contains(MAX_PARAMETERS.toString()) })
    }
}
