package com.xclusivecyborg.rmcpush.auth

import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val SCOPE = "https://www.googleapis.com/auth/firebase.remoteconfig"

private data class TokenResponse(
    val access_token: String?,
    val expires_in: Int?,
    val error: String?
)

private fun generateJwt(serviceAccount: ServiceAccount): String {
    val now = System.currentTimeMillis() / 1000
    val header = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray(Charsets.UTF_8))
    val payload = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(
            """{"iss":"${serviceAccount.client_email}","sub":"${serviceAccount.client_email}","aud":"$TOKEN_URL","scope":"$SCOPE","iat":$now,"exp":${now + 3600}}"""
                .toByteArray(Charsets.UTF_8)
        )
    val signingInput = "$header.$payload"

    val privateKeyPem = serviceAccount.private_key
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("-----BEGIN RSA PRIVATE KEY-----", "")
        .replace("-----END RSA PRIVATE KEY-----", "")
        .replace("\n", "").replace("\r", "").trim()

    val keyBytes = Base64.getDecoder().decode(privateKeyPem)
    val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))

    val sig = Signature.getInstance("SHA256withRSA")
    sig.initSign(privateKey)
    sig.update(signingInput.toByteArray(Charsets.UTF_8))
    val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign())

    return "$signingInput.$signature"
}

/**
 * Exchanges a service account for an OAuth2 access token.
 * Returns (accessToken, expiresAt) where expiresAt is Unix epoch seconds.
 */
fun getAccessToken(serviceAccount: ServiceAccount): Pair<String, Long> {
    val jwt = generateJwt(serviceAccount)
    val body = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$jwt"

    val client = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder()
        .uri(URI.create(TOKEN_URL))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    val data = Gson().fromJson(response.body(), TokenResponse::class.java)

    if (data.access_token == null) {
        throw Exception("Failed to obtain access token: ${data.error ?: "unknown error"}")
    }

    val expiresAt = System.currentTimeMillis() / 1000 + (data.expires_in ?: 3600)
    return Pair(data.access_token, expiresAt)
}
