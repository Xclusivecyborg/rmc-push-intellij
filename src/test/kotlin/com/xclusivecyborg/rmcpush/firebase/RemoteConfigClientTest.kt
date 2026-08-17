package com.xclusivecyborg.rmcpush.firebase

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These guard the reason the template is carried as raw JSON. A push rewrites
 * the whole template, so anything dropped on the way in is deleted from the
 * user's project on the way out.
 */
class RemoteConfigClientTest {

    private fun template(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private val richTemplate = template(
        """
        {
          "conditions": [ { "name": "android", "expression": "device.os == 'android'" } ],
          "parameters": {
            "welcome_title": {
              "defaultValue": { "value": "Hello" },
              "conditionalValues": { "android": { "value": "Hello, Android" } },
              "description": "Shown on the home screen",
              "valueType": "STRING"
            },
            "untouched_flag": {
              "defaultValue": { "value": "true" },
              "conditionalValues": { "android": { "value": "false" } },
              "valueType": "BOOLEAN"
            }
          },
          "parameterGroups": {
            "Feature Flags": {
              "description": "Rolled out gradually",
              "parameters": {
                "promo_banner": {
                  "defaultValue": { "value": "false" },
                  "conditionalValues": { "android": { "value": "true" } },
                  "valueType": "BOOLEAN"
                }
              }
            }
          },
          "version": { "versionNumber": "42" }
        }
        """.trimIndent()
    )

    @Test
    fun `editing a parameter keeps its conditional values and description`() {
        val merged = RemoteConfigClient.mergeParameter(
            richTemplate, "welcome_title", "Hi there", "STRING", null
        )

        val parameter = merged.getAsJsonObject("parameters").getAsJsonObject("welcome_title")
        assertEquals("Hi there", parameter.getAsJsonObject("defaultValue").get("value").asString)
        assertEquals(
            "Hello, Android",
            parameter.getAsJsonObject("conditionalValues").getAsJsonObject("android").get("value").asString
        )
        assertEquals("Shown on the home screen", parameter.get("description").asString)
    }

    @Test
    fun `editing one parameter leaves the others byte-for-byte alone`() {
        val merged = RemoteConfigClient.mergeParameter(
            richTemplate, "welcome_title", "Hi there", "STRING", null
        )

        assertEquals(
            richTemplate.getAsJsonObject("parameters").getAsJsonObject("untouched_flag"),
            merged.getAsJsonObject("parameters").getAsJsonObject("untouched_flag")
        )
        assertEquals(richTemplate.getAsJsonArray("conditions"), merged.getAsJsonArray("conditions"))
        assertEquals(
            richTemplate.getAsJsonObject("parameterGroups"),
            merged.getAsJsonObject("parameterGroups")
        )
    }

    @Test
    fun `editing a grouped parameter keeps the group description and conditions`() {
        val merged = RemoteConfigClient.mergeParameter(
            richTemplate, "promo_banner", "true", "BOOLEAN", "Feature Flags"
        )

        val group = merged.getAsJsonObject("parameterGroups").getAsJsonObject("Feature Flags")
        assertEquals("Rolled out gradually", group.get("description").asString)

        val parameter = group.getAsJsonObject("parameters").getAsJsonObject("promo_banner")
        assertEquals("true", parameter.getAsJsonObject("defaultValue").get("value").asString)
        assertEquals(
            "true",
            parameter.getAsJsonObject("conditionalValues").getAsJsonObject("android").get("value").asString
        )
    }

    @Test
    fun `merging does not mutate the template it was given`() {
        val before = richTemplate.deepCopy()
        RemoteConfigClient.mergeParameter(richTemplate, "welcome_title", "Changed", "STRING", null)
        assertEquals(before, richTemplate)
    }

    @Test
    fun `a new parameter group is created when it does not exist yet`() {
        val merged = RemoteConfigClient.mergeParameter(
            richTemplate, "payment_timeout_ms", "8000", "NUMBER", "checkout"
        )

        val parameter = merged.getAsJsonObject("parameterGroups")
            .getAsJsonObject("checkout")
            .getAsJsonObject("parameters")
            .getAsJsonObject("payment_timeout_ms")

        assertEquals("8000", parameter.getAsJsonObject("defaultValue").get("value").asString)
        assertEquals("NUMBER", parameter.get("valueType").asString)
    }

    @Test
    fun `a new parameter can be added to a template with no parameters at all`() {
        val merged = RemoteConfigClient.mergeParameter(
            template("{}"), "first_flag", "true", "BOOLEAN", null
        )

        assertEquals(
            "true",
            merged.getAsJsonObject("parameters")
                .getAsJsonObject("first_flag")
                .getAsJsonObject("defaultValue")
                .get("value").asString
        )
    }

    @Test
    fun `changing the type replaces the old value type`() {
        val merged = RemoteConfigClient.mergeParameter(
            richTemplate, "welcome_title", "42", "NUMBER", null
        )

        assertEquals(
            "NUMBER",
            merged.getAsJsonObject("parameters").getAsJsonObject("welcome_title").get("valueType").asString
        )
    }

    @Test
    fun `the request body drops version but keeps everything else`() {
        val body = RemoteConfigClient.requestBodyFor(richTemplate)

        assertNull(body.get("version"))
        assertTrue(body.has("conditions"))
        assertEquals(richTemplate.getAsJsonObject("parameters"), body.getAsJsonObject("parameters"))
        assertEquals(richTemplate.getAsJsonObject("parameterGroups"), body.getAsJsonObject("parameterGroups"))
    }
}
