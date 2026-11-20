package com.nxuslab.dreymanager.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

fun interface UpdateRepository {
    suspend fun fetchManifest(): UpdateManifest
}

class HttpUpdateRepository(
    private val manifestUrl: String,
) : UpdateRepository {
    override suspend fun fetchManifest(): UpdateManifest = withContext(Dispatchers.IO) {
        val manifestUri = URI(manifestUrl)
        require(manifestUri.scheme == "https") { "O manifesto deve usar HTTPS" }

        val connection = manifestUri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")

            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Servidor respondeu com HTTP ${connection.responseCode}"
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseManifest(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseManifest(body: String): UpdateManifest {
        val json = JSONObject(body)
        val downloadUrl = json.getString("downloadUrl")
        val downloadUri = URI(downloadUrl)
        require(downloadUri.scheme == "https") { "O download deve usar HTTPS" }
        // Um manifesto adulterado não pode mandar o usuário para outro site.
        require(downloadUri.host == URI(manifestUrl).host) { "O download deve vir do mesmo servidor do manifesto" }

        return UpdateManifest(
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            message = json.optString("message", "Uma nova versão está disponível."),
            downloadUrl = downloadUrl,
        )
    }
}

