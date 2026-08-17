package com.xclusivecyborg.rmcpush.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionsTest {

    private fun template(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `root parameters come first, then groups`() {
        val sections = toSections(
            template(
                """
                {
                  "parameters": { "a": { "defaultValue": { "value": "1" }, "valueType": "STRING" } },
                  "parameterGroups": {
                    "checkout": { "parameters": {} }
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals(2, sections.size)
        assertNull(sections[0].group)
        assertEquals("checkout", sections[1].group)
    }

    @Test
    fun `groups sort case-insensitively, matching the Firebase console`() {
        val sections = toSections(
            template(
                """
                {
                  "parameterGroups": {
                    "Tester": { "parameters": {} },
                    "config": { "parameters": {} },
                    "Alpha": { "parameters": {} }
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals(listOf("Alpha", "config", "Tester"), sections.drop(1).map { it.group })
    }

    @Test
    fun `entries sort case-insensitively by key`() {
        val sections = toSections(
            template(
                """
                {
                  "parameters": {
                    "zebra": { "defaultValue": { "value": "1" } },
                    "Apple": { "defaultValue": { "value": "2" } },
                    "mango": { "defaultValue": { "value": "3" } }
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals(listOf("Apple", "mango", "zebra"), sections[0].entries.map { it.key })
    }

    @Test
    fun `conditional values are counted, not shown`() {
        val entry = toSections(
            template(
                """
                {
                  "parameters": {
                    "welcome": {
                      "defaultValue": { "value": "Hello" },
                      "conditionalValues": { "android": { "value": "A" }, "ios": { "value": "B" } }
                    }
                  }
                }
                """.trimIndent()
            )
        )[0].entries.single()

        assertEquals(2, entry.conditionCount)
        assertEquals("Hello", entry.value)
    }

    @Test
    fun `a parameter with no valueType is treated as a string`() {
        val entry = toSections(
            template("""{ "parameters": { "legacy": { "defaultValue": { "value": "x" } } } }""")
        )[0].entries.single()

        assertEquals("STRING", entry.valueType)
        assertEquals(0, entry.conditionCount)
    }

    @Test
    fun `in-app defaults are flagged and carry no value`() {
        val entry = toSections(
            template("""{ "parameters": { "flag": { "defaultValue": { "useInAppDefault": true } } } }""")
        )[0].entries.single()

        assertTrue(entry.usesInAppDefault)
        assertEquals("", entry.value)
    }

    @Test
    fun `grouped entries carry their group name`() {
        val sections = toSections(
            template(
                """
                {
                  "parameterGroups": {
                    "Feature Flags": {
                      "description": "Rolled out gradually",
                      "parameters": { "promo": { "defaultValue": { "value": "true" }, "valueType": "BOOLEAN" } }
                    }
                  }
                }
                """.trimIndent()
            )
        )

        val group = sections[1]
        assertEquals("Feature Flags", group.group)
        assertEquals("Rolled out gradually", group.description)
        assertEquals("Feature Flags", group.entries.single().group)
    }

    @Test
    fun `an empty template still yields a root section`() {
        val sections = toSections(template("{}"))

        assertEquals(1, sections.size)
        assertNull(sections[0].group)
        assertTrue(sections[0].entries.isEmpty())
    }

    @Test
    fun `a root entry has no group`() {
        val entry = toSections(
            template("""{ "parameters": { "a": { "defaultValue": { "value": "1" } } } }""")
        )[0].entries.single()

        assertNull(entry.group)
        assertFalse(entry.usesInAppDefault)
    }
}
