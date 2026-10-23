package com.armanc.team

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for https://github.com/ArmancTeam/Fixtok7 */
object GitHubUpdateChecker {
    const val OWNER = "ArmancTeam"
    const val REPOSITORY = "Fixtok7"
    const val NO_RELEASE = "no_release"

    data class Result(val latestTag: String, val releaseUrl: String, val isNewer: Boolean)

    fun isConfigured(): Boolean = OWNER.isNotBlank() && REPOSITORY.isNotBlank()

    fun check(context: Context, callback: (Result?, String?) -> Unit) {
        if (!isConfigured()) {
            callback(null, context.getString(R.string.settings_update_not_configured))
            return
        }
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("User-Agent", "Fixtok-Android-TV")
                }
                if (connection.responseCode == 404) {
                    Handler(Looper.getMainLooper()).post { callback(null, NO_RELEASE) }
                    return@Thread
                }
                if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                val payload = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(payload)
                val tag = json.optString("tag_name").ifBlank { json.optString("name") }
                val url = json.optString("html_url")
                if (tag.isBlank() || url.isBlank()) throw IllegalStateException("Invalid release metadata")
                val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
                val result = Result(tag, url, compareVersions(tag, current) > 0)
                Handler(Looper.getMainLooper()).post { callback(result, null) }
            } catch (t: Throwable) {
                val detail = t.message?.takeIf { it.isNotBlank() } ?: "unknown"
                Handler(Looper.getMainLooper()).post { callback(null, detail) }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun compareVersions(tag: String, current: String): Int {
        fun parts(value: String) = value.trim().removePrefix("v").substringBefore('-')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }
}
