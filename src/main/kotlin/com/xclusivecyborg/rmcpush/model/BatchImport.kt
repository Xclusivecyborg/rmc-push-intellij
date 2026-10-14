package com.xclusivecyborg.rmcpush.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.xclusivecyborg.rmcpush.validation.VALUE_TYPES
import com.xclusivecyborg.rmcpush.validation.parseStrictJson
import com.xclusivecyborg.rmcpush.validation.validatePush

/** Firebase's cap on parameters per template. */
const val MAX_PARAMETERS = 2000

/** One parameter an import would stage, or why it cannot. */
data class ImportRow(
    val key: String,
    val value: String,
    val valueType: String,
    /** Where it will be written; null for root parameters. */
    val group: String?,
    /** False when the key already exists and this updates it. */
    val isNew: Boolean,
    /** Something the user should know that does not block the row. */
    val note: String? = null,
    /** Why the row cannot be staged. */
    val error: String? = null
) {
    fun toChange() = ParameterChange(key, value, valueType, group, isNew)
}

data class ImportResult(
    val format: Format,
    val rows: List<ImportRow>,
    /** The whole input is unusable — nothing to preview. */
    val error: String? = null,
    val warnings: List<String> = emptyList()
) {
    enum class Format { EMPTY, MAP, TEMPLATE }

    val ready: List<ImportRow> get() = rows.filter { it.error == null }
}

/** Top-level keys a Firebase template can carry. */
private val TEMPLATE_KEYS = setOf("conditions", "parameters", "parameterGroups", "version", "etag")

/**
 * Turns pasted or uploaded JSON into rows the user previews before anything is
 * staged. Mirrors src/batchImport.ts in the VS Code extension.
 *
 * Two shapes are accepted and told apart automatically: a simple map —
 * `{"enable_new_home": true, "app_min_version": 2.4}` — whose types come from
 * the JSON values and which lands in [targetGroup]; and a Firebase template, as
 * the console's Download produces, whose groups and types come from the file.
 *
 * An existing key is updated where it lives, even when that is not
 * [targetGroup]: keys are unique across the whole template, so writing it
 * elsewhere would create a duplicate.
 */
fun parseBatchJson(text: String, targetGroup: String?, existing: List<ConfigEntry>): ImportResult {
    if (text.isBlank()) return ImportResult(ImportResult.Format.EMPTY, emptyList())

    val json = try {
        parseStrictJson(text)
    } catch (ex: Exception) {
        return ImportResult(ImportResult.Format.EMPTY, emptyList(), error = "Invalid JSON: ${ex.cause?.message ?: ex.message}")
    }
    if (json !is JsonObject) {
        return ImportResult(
            ImportResult.Format.EMPTY, emptyList(),
            error = "Expected a JSON object: {\"key\": value, …} or a Firebase template"
        )
    }

    val target = targetGroup?.trim()?.takeIf { it.isNotEmpty() }
    val warnings = mutableListOf<String>()
    val format: ImportResult.Format
    val raw: List<RawRow>

    if (isTemplate(json)) {
        format = ImportResult.Format.TEMPLATE
        val groups = (json.get("parameterGroups") as? JsonObject)?.entrySet().orEmpty()
        raw = fromTemplateParameters(json.get("parameters"), null) +
            groups.flatMap { (name, group) -> fromTemplateParameters((group as? JsonObject)?.get("parameters"), name) }
        val hasConditions = (json.get("conditions")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0) > 0
        if (hasConditions || hasConditionalValues(json)) {
            warnings += "Conditions and conditional values in the file are not imported — only default values."
        }
    } else {
        format = ImportResult.Format.MAP
        raw = json.entrySet().map { (key, value) -> inferred(key, value, target) }
    }

    val byKey = existing.associateBy { it.key }
    val seen = mutableSetOf<String>()
    val rows = raw.map { row ->
        val current = byKey[row.key]
        val group = if (current != null) current.group else row.group
        val note = if (current != null && (current.group ?: "") != (row.group ?: "")) {
            "Already in ${current.group?.let { "group \"$it\"" } ?: "root parameters"} — updated there"
        } else null
        val error = row.error
            ?: (if (row.key in seen) "Appears more than once in the file" else null)
            ?: validatePush(row.key, row.value, row.valueType, group)?.message
        seen += row.key
        ImportRow(row.key, row.value, row.valueType, group, isNew = current == null, note = note, error = error)
    }

    val added = rows.count { it.isNew && it.error == null }
    if (existing.size + added > MAX_PARAMETERS) {
        warnings += "This would bring the project to ${existing.size + added} parameters; Firebase allows at most $MAX_PARAMETERS."
    }
    return ImportResult(format, rows, warnings = warnings)
}

private data class RawRow(
    val key: String,
    val value: String,
    val valueType: String,
    val group: String?,
    val error: String? = null
)

/**
 * A template is recognised by its shape, not by one key: every top-level key
 * must be one a template has, and its parameters must look like parameters. A
 * simple map with a key called "parameters" holding plain data stays a map.
 */
private fun isTemplate(json: JsonObject): Boolean {
    val keys = json.keySet()
    if ("parameters" !in keys && "parameterGroups" !in keys) return false
    if (!keys.all { it in TEMPLATE_KEYS }) return false

    fun looksLikeParameters(value: JsonElement?): Boolean =
        value == null || (value is JsonObject && value.entrySet().all { (_, p) ->
            p is JsonObject && (p.has("defaultValue") || p.has("valueType") || p.has("conditionalValues"))
        })

    val groups = json.get("parameterGroups")
    return looksLikeParameters(json.get("parameters")) &&
        (groups == null || (groups is JsonObject && groups.entrySet().all { (_, g) ->
            g is JsonObject && looksLikeParameters(g.get("parameters"))
        }))
}

/** A simple-map value → the string Firebase stores and the type it implies. */
private fun inferred(key: String, value: JsonElement, group: String?): RawRow = when {
    value.isJsonNull -> RawRow(key, "", "STRING", group, error = "null is not a value Remote Config can store")
    value is JsonPrimitive && value.isBoolean -> RawRow(key, value.asBoolean.toString(), "BOOLEAN", group)
    value is JsonPrimitive && value.isNumber -> RawRow(key, value.asString, "NUMBER", group)
    value is JsonPrimitive -> RawRow(key, value.asString, "STRING", group)
    else -> RawRow(key, value.toString(), "JSON", group)
}

private fun fromTemplateParameters(parameters: JsonElement?, group: String?): List<RawRow> {
    if (parameters !is JsonObject) return emptyList()
    return parameters.entrySet().map { (key, raw) ->
        val param = raw as? JsonObject ?: JsonObject()
        val type = param.get("valueType")?.takeIf { it.isJsonPrimitive }?.asString ?: "STRING"
        val valueType = if (type in VALUE_TYPES) type else "STRING"
        val value = (param.get("defaultValue") as? JsonObject)?.get("value")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        when {
            value == null -> RawRow(key, "", valueType, group, error = "No default value in the file (it uses the in-app default)")
            type !in VALUE_TYPES -> RawRow(key, value, valueType, group, error = "Unknown value type \"$type\"")
            else -> RawRow(key, value, valueType, group)
        }
    }
}

/** True when any parameter in the template carries conditional values. */
private fun hasConditionalValues(template: JsonObject): Boolean {
    val groups = (template.get("parameterGroups") as? JsonObject)?.entrySet().orEmpty()
    val sets = listOf(template.get("parameters")) + groups.map { (_, g) -> (g as? JsonObject)?.get("parameters") }
    return sets.any { set ->
        set is JsonObject && set.entrySet().any { (_, p) ->
            ((p as? JsonObject)?.get("conditionalValues") as? JsonObject)?.size()?.let { it > 0 } == true
        }
    }
}
