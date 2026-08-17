package com.xclusivecyborg.rmcpush.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors src/test/suite/validation.test.ts in the VS Code extension. */
class ValidationTest {

    private fun error(key: String, value: String, type: String, group: String? = null) =
        validatePush(key, value, type, group)

    @Test
    fun `a plain string parameter is accepted`() {
        assertNull(error("welcome_title", "Hello", "STRING"))
    }

    @Test
    fun `an empty key is rejected`() {
        assertEquals(Field.KEY, error("", "x", "STRING")?.field)
        assertEquals(Field.KEY, error("   ", "x", "STRING")?.field)
    }

    @Test
    fun `keys reject anything but letters, numbers and underscores`() {
        assertEquals(Field.KEY, error("has-hyphen", "x", "STRING")?.field)
        assertEquals(Field.KEY, error("has space", "x", "STRING")?.field)
        assertEquals(Field.KEY, error("has.dot", "x", "STRING")?.field)
        assertNull(error("Mixed_Case_123", "x", "STRING"))
    }

    @Test
    fun `group names accept spaces, because they are console labels not identifiers`() {
        assertNull(error("k", "v", "STRING", "Feature Flags"))
        assertNull(error("k", "v", "STRING", "A B C"))
        assertNull(error("k", "v", "STRING", "kebab-case"))
        assertNull(error("k", "v", "STRING", "  Feature Flags  "))
    }

    @Test
    fun `group names reject leading spaces and stray punctuation`() {
        assertEquals(Field.GROUP, error("k", "v", "STRING", "-leading")?.field)
        assertEquals(Field.GROUP, error("k", "v", "STRING", "has.dot")?.field)
        assertEquals(Field.GROUP, error("k", "v", "STRING", "slash/es")?.field)
    }

    @Test
    fun `a blank group means root parameters, not an error`() {
        assertNull(error("k", "v", "STRING", ""))
        assertNull(error("k", "v", "STRING", "   "))
        assertNull(error("k", "v", "STRING", null))
    }

    @Test
    fun `an empty value is rejected`() {
        assertEquals(Field.VALUE, error("k", "", "STRING")?.field)
    }

    @Test
    fun `numbers must parse`() {
        assertNull(error("k", "42", "NUMBER"))
        assertNull(error("k", "-3.5", "NUMBER"))
        assertEquals(Field.VALUE, error("k", "twelve", "NUMBER")?.field)
    }

    @Test
    fun `booleans must be true or false in any casing`() {
        assertNull(error("k", "true", "BOOLEAN"))
        assertNull(error("k", "TRUE", "BOOLEAN"))
        assertNull(error("k", " false ", "BOOLEAN"))
        assertEquals(Field.VALUE, error("k", "yes", "BOOLEAN")?.field)
    }

    @Test
    fun `json must parse`() {
        assertNull(error("k", """{"enabled":true}""", "JSON"))
        assertNull(error("k", "[1,2,3]", "JSON"))
        assertEquals(Field.VALUE, error("k", "{ not json", "JSON")?.field)
    }

    @Test
    fun `booleans are stored lowercase and trimmed`() {
        assertEquals("true", normalizeValue(" TRUE ", "BOOLEAN"))
        assertEquals("false", normalizeValue("False", "BOOLEAN"))
    }

    @Test
    fun `other types are stored exactly as typed`() {
        assertEquals("  Hello  ", normalizeValue("  Hello  ", "STRING"))
        assertEquals("TRUE", normalizeValue("TRUE", "STRING"))
    }
}
