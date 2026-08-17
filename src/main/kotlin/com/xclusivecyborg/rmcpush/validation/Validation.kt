package com.xclusivecyborg.rmcpush.validation

import com.google.gson.JsonParser

/** Parameter keys are read as code identifiers by client SDKs — no spaces. */
val KEY_REGEX = Regex("^[a-zA-Z0-9_]+$")

/**
 * Group names are display labels in the Firebase console, not identifiers, so
 * they routinely contain spaces ("Feature Flags"). Leading and trailing
 * whitespace is trimmed before this is applied.
 */
val GROUP_REGEX = Regex("^[a-zA-Z0-9_][a-zA-Z0-9_ -]*$")

val VALUE_TYPES = listOf("STRING", "NUMBER", "BOOLEAN", "JSON")

enum class Field { KEY, VALUE, GROUP }

data class ValidationError(val field: Field, val message: String)

/**
 * Validates a push. Mirrors src/validation.ts in the VS Code extension so both
 * plugins accept and reject exactly the same input.
 */
fun validatePush(key: String, value: String, valueType: String, group: String?): ValidationError? {
    val trimmedKey = key.trim()
    val trimmedGroup = group?.trim() ?: ""

    if (trimmedKey.isEmpty()) {
        return ValidationError(Field.KEY, "Key is required")
    }
    if (!KEY_REGEX.matches(trimmedKey)) {
        return ValidationError(Field.KEY, "Use only letters, numbers, and underscores")
    }
    if (trimmedGroup.isNotEmpty() && !GROUP_REGEX.matches(trimmedGroup)) {
        return ValidationError(Field.GROUP, "Use letters, numbers, spaces, underscores, and hyphens")
    }
    if (value.isEmpty()) {
        return ValidationError(Field.VALUE, "Value is required")
    }

    when (valueType) {
        "NUMBER" -> if (value.trim().toDoubleOrNull() == null) {
            return ValidationError(Field.VALUE, "Must be a valid number")
        }
        "BOOLEAN" -> if (value.lowercase().trim() !in listOf("true", "false")) {
            return ValidationError(Field.VALUE, "Must be \"true\" or \"false\"")
        }
        "JSON" -> try {
            JsonParser.parseString(value)
        } catch (ex: Exception) {
            return ValidationError(Field.VALUE, "Invalid JSON: ${ex.message}")
        }
    }

    return null
}

/** Booleans are stored lowercase so the value Firebase holds is canonical. */
fun normalizeValue(value: String, valueType: String): String =
    if (valueType == "BOOLEAN") value.lowercase().trim() else value
