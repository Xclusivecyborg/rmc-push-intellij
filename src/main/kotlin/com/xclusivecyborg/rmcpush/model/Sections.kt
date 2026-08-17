package com.xclusivecyborg.rmcpush.model

import com.google.gson.JsonObject

/** One parameter, flattened for display. */
data class ConfigEntry(
    val key: String,
    val value: String,
    val valueType: String,
    /** null for a root parameter. */
    val group: String?,
    val conditionCount: Int,
    val usesInAppDefault: Boolean
)

/** Root parameters, or one parameter group. */
data class ConfigSection(
    val group: String?,
    val description: String?,
    val entries: List<ConfigEntry>
)

/**
 * Flattens a Remote Config template into the sections the panel renders: root
 * parameters first, then each group.
 *
 * Ordering is case-insensitive to match the Firebase console, which sorts
 * "config" before "Tester" rather than the other way round as a raw ASCII
 * comparison would.
 */
fun toSections(template: JsonObject): List<ConfigSection> {
    val rootSection = ConfigSection(
        group = null,
        description = null,
        entries = entriesOf(template.objectOrNull("parameters"), group = null)
    )

    val groupSections = (template.objectOrNull("parameterGroups")?.entrySet() ?: emptySet())
        .mapNotNull { (name, raw) -> (raw as? JsonObject)?.let { name to it } }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
        .map { (name, groupJson) ->
            ConfigSection(
                group = name,
                description = groupJson.stringOrNull("description"),
                entries = entriesOf(groupJson.objectOrNull("parameters"), group = name)
            )
        }

    return listOf(rootSection) + groupSections
}

private fun entriesOf(parameters: JsonObject?, group: String?): List<ConfigEntry> =
    (parameters?.entrySet() ?: emptySet())
        .mapNotNull { (key, raw) -> (raw as? JsonObject)?.let { entryOf(key, it, group) } }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })

private fun entryOf(key: String, parameter: JsonObject, group: String?): ConfigEntry {
    val defaultValue = parameter.objectOrNull("defaultValue")
    return ConfigEntry(
        key = key,
        value = defaultValue?.stringOrNull("value") ?: "",
        // Firebase omits valueType on parameters created before it existed.
        valueType = parameter.stringOrNull("valueType") ?: "STRING",
        group = group,
        conditionCount = parameter.objectOrNull("conditionalValues")?.size() ?: 0,
        usesInAppDefault = defaultValue?.get("useInAppDefault")?.let {
            it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
        } ?: false
    )
}

private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString
