package com.xclusivecyborg.rmcpush.auth

import com.google.gson.Gson
import java.io.File

data class ServiceAccount(
    val type: String,
    val project_id: String,
    val private_key_id: String,
    val private_key: String,
    val client_email: String,
    val client_id: String,
    val auth_uri: String,
    val token_uri: String
)

fun readServiceAccount(path: String): ServiceAccount {
    val file = File(path)
    if (!file.exists()) throw Exception("Service account file not found: $path")
    val sa = Gson().fromJson(file.readText(), ServiceAccount::class.java)
        ?: throw Exception("Invalid service account file")
    if (sa.project_id.isBlank() || sa.private_key.isBlank() || sa.client_email.isBlank()) {
        throw Exception("Service account file is missing required fields (project_id, private_key, client_email)")
    }
    return sa
}
