package com.zapret.droid.proxy

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

object ListRepositoryUpdater {
    private const val BASE =
        "https://raw.githubusercontent.com/Flowseal/zapret-discord-youtube/main/lists/"

    fun update(context: Context): Int {
        val files = mapOf(
            "updated_general_domains" to "list-general.txt",
            "updated_google_domains" to "list-google.txt",
            "updated_exclude_domains" to "list-exclude.txt",
            "updated_ipset" to "ipset-all.txt",
            "updated_ipset_exclude" to "ipset-exclude.txt"
        )
        val prefs = context.getSharedPreferences("zapret_prefs", Context.MODE_PRIVATE)
        var downloaded = 0
        prefs.edit().apply {
            files.forEach { (key, file) ->
                val content = download(file) ?: return@forEach
                putString(key, content)
                downloaded++
            }
            apply()
        }
        if (downloaded > 0) DomainLists.load(context)
        return downloaded
    }

    private fun download(file: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(BASE + file).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
