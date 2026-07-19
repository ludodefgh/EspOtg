package com.espotg.core.github

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Minimal GitHub REST client (releases only), built on HttpURLConnection so the
 * app needs no HTTP dependency. Blocking by design - callers run it off the main
 * thread. Public repos work unauthenticated; an optional token lifts the 60/hour
 * rate limit and reaches private repos.
 */
object GitHubClient {
    private val json = Json { ignoreUnknownKeys = true }

    /** Accepts "owner/repo", "github.com/owner/repo", or a full https URL (with or without trailing path). */
    fun parseRepoRef(input: String): RepoRef? {
        var s = input.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("github.com/")
        s = s.removeSuffix(".git").trim('/')
        val parts = s.split("/").filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return RepoRef(owner = parts[0], repo = parts[1])
    }

    fun fetchReleases(owner: String, repo: String, token: String? = null): List<GitHubRelease> {
        val conn = open("https://api.github.com/repos/$owner/$repo/releases?per_page=50", token)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when (code) {
                in 200..299 -> return json.decodeFromString(ListSerializer(GitHubRelease.serializer()), body)
                    .filterNot { it.draft }
                404 -> throw IOException("Repository not found (or private without a token): $owner/$repo")
                403 -> throw IOException("GitHub rate limit reached - add a token or retry later")
                else -> throw IOException("GitHub API error $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "EspOtg") // GitHub rejects requests without a User-Agent
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            connectTimeout = 15_000
            readTimeout = 20_000
        }
}
