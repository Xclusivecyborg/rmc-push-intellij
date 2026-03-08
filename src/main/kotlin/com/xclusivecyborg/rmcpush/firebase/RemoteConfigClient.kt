package com.xclusivecyborg.rmcpush.firebase

import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class RemoteConfigParameterValue(
    val value: String? = null,
    val useInAppDefault: Boolean? = null
)

data class RemoteConfigParameter(
    val defaultValue: RemoteConfigParameterValue? = null,
    val valueType: String? = null
)

data class RemoteConfigParameterGroup(
    val description: String? = null,
    val parameters: Map<String, RemoteConfigParameter> = emptyMap()
)

data class RemoteConfigTemplate(
    val parameters: Map<String, RemoteConfigParameter> = emptyMap(),
    val conditions: List<Any>? = null,
    val parameterGroups: Map<String, RemoteConfigParameterGroup> = emptyMap(),
    val version: Map<String, Any>? = null
)

object RemoteConfigClient {
    private val gson = Gson()
    private val httpClient = HttpClient.newHttpClient()

    private fun apiUrl(projectId: String) =
        "https://firebaseremoteconfig.googleapis.com/v1/projects/$projectId/remoteConfig"

    /** Fetches the current Remote Config template and its ETag. */
    fun fetch(projectId: String, accessToken: String): Pair<RemoteConfigTemplate, String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl(projectId)))
            .header("Authorization", "Bearer $accessToken")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw Exception("Failed to fetch Remote Config (HTTP ${response.statusCode()}): ${response.body()}")
        }

        val etag = response.headers().firstValue("etag").orElse("*")
        val template = gson.fromJson(response.body(), RemoteConfigTemplate::class.java)
        return Pair(template, etag)
    }

    /** Pure function — merges a single parameter into an existing template. */
    fun mergeParameter(
        template: RemoteConfigTemplate,
        key: String,
        value: String,
        type: String,
        group: String?
    ): RemoteConfigTemplate {
        val newParam = RemoteConfigParameter(
            defaultValue = RemoteConfigParameterValue(value = value),
            valueType = type
        )
        return if (group != null) {
            val existingGroup = template.parameterGroups[group] ?: RemoteConfigParameterGroup()
            val updatedGroup = existingGroup.copy(parameters = existingGroup.parameters + (key to newParam))
            template.copy(parameterGroups = template.parameterGroups + (group to updatedGroup))
        } else {
            template.copy(parameters = template.parameters + (key to newParam))
        }
    }

    /** PUTs the merged template back to Firebase using the ETag for optimistic concurrency. */
    fun push(projectId: String, accessToken: String, template: RemoteConfigTemplate, etag: String) {
        // Exclude the read-only version field from the PUT body
        val body = mapOf(
            "parameters" to template.parameters,
            "conditions" to (template.conditions ?: emptyList<Any>()),
            "parameterGroups" to template.parameterGroups
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl(projectId)))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("If-Match", etag)
            .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw Exception("Failed to update Remote Config (HTTP ${response.statusCode()}): ${response.body()}")
        }
    }
}
