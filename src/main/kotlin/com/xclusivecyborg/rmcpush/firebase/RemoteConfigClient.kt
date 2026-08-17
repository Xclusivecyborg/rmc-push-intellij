package com.xclusivecyborg.rmcpush.firebase

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** A fetched template together with the ETag it arrived with. */
data class FetchedTemplate(val json: JsonObject, val etag: String)

/**
 * Talks to the Firebase Remote Config REST API.
 *
 * The template travels as the raw JSON tree Firebase returned rather than
 * mapped onto Kotlin data classes. A parameter carries fields this plugin has
 * no interest in — `conditionalValues`, `description`, and whatever Firebase
 * adds next — and a typed model drops every one of them at parse time. Because
 * a push writes the *whole* template back, a lossy model does not just lose the
 * edited parameter's extras: it erases them from every parameter in the
 * project. Keeping the tree intact makes that failure impossible rather than
 * merely unlikely.
 */
object RemoteConfigClient {
    private val gson = Gson()
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    private fun apiUrl(projectId: String) =
        "https://firebaseremoteconfig.googleapis.com/v1/projects/$projectId/remoteConfig"

    /** Fetches the current template and its ETag. */
    fun fetch(projectId: String, accessToken: String): FetchedTemplate {
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
        val json = try {
            JsonParser.parseString(response.body()) as? JsonObject
                ?: throw Exception("Remote Config returned an unexpected response shape.")
        } catch (ex: JsonSyntaxException) {
            throw Exception("Could not parse the Remote Config response: ${ex.message}")
        }
        return FetchedTemplate(json, etag)
    }

    /**
     * Pure function — returns a copy of [template] with one parameter's default
     * value replaced.
     *
     * Anything already on that parameter other than `defaultValue`/`valueType`
     * is carried over untouched, so editing a parameter that has conditional
     * values keeps them.
     */
    fun mergeParameter(
        template: JsonObject,
        key: String,
        value: String,
        valueType: String,
        group: String?
    ): JsonObject {
        val updated = template.deepCopy()
        val container = if (group == null) {
            updated.child("parameters")
        } else {
            updated.child("parameterGroups").child(group).child("parameters")
        }

        val parameter = (container.get(key) as? JsonObject)?.deepCopy() ?: JsonObject()
        parameter.add("defaultValue", JsonObject().apply { addProperty("value", value) })
        parameter.addProperty("valueType", valueType)
        container.add(key, parameter)

        return updated
    }

    /**
     * The body to PUT for [template]: everything it holds except the
     * server-owned `version`, which Firebase rejects when echoed back.
     */
    fun requestBodyFor(template: JsonObject): JsonObject =
        template.deepCopy().apply { remove("version") }

    /** PUTs the template back, using the ETag for optimistic concurrency. */
    fun push(projectId: String, accessToken: String, template: JsonObject, etag: String) {
        val body = requestBodyFor(template)

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

    /**
     * Returns the child object at [name], creating it when absent. Also replaces
     * a non-object (including JSON null) rather than throwing, so a malformed
     * template cannot crash a push.
     */
    private fun JsonObject.child(name: String): JsonObject {
        (get(name) as? JsonObject)?.let { return it }
        return JsonObject().also { add(name, it) }
    }
}
