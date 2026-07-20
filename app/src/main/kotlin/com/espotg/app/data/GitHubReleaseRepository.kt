package com.espotg.app.data

import com.espotg.core.github.GitHubAsset
import com.espotg.core.github.GitHubClient
import com.espotg.core.github.GitHubRelease
import com.espotg.core.github.RepoRef
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Off-main-thread wrapper over [GitHubClient] plus asset downloading into a cache dir. */
class GitHubReleaseRepository(private val cacheDir: File) {

    fun parseRepoRef(input: String): RepoRef? = GitHubClient.parseRepoRef(input)

    suspend fun fetchReleases(repo: RepoRef, token: String? = null): List<GitHubRelease> =
        withContext(Dispatchers.IO) { GitHubClient.fetchReleases(repo.owner, repo.repo, token) }

    /**
     * Downloads [asset] into the app cache and returns the local file. Files are
     * namespaced by [releaseTag] so a same-named asset (e.g. "firmware.bin") from a
     * different release doesn't overwrite one a saved profile still references.
     */
    suspend fun downloadAsset(asset: GitHubAsset, releaseTag: String, token: String? = null): File = withContext(Dispatchers.IO) {
        val dir = File(File(cacheDir, "downloads"), sanitize(releaseTag)).apply { mkdirs() }
        val dest = File(dir, sanitize(asset.name))
        val conn = (URL(asset.browserDownloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "EspOtg")
            setRequestProperty("Accept", "application/octet-stream")
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("Download failed (HTTP $code) for ${asset.name}")
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        pruneCache(dest)
        dest
    }

    /**
     * Keeps the downloads cache under [MAX_CACHE_BYTES] by deleting the
     * least-recently-modified files first, never touching [keep] (the file just
     * downloaded, which callers/saved profiles are about to reference).
     */
    private fun pruneCache(keep: File) {
        val root = File(cacheDir, "downloads")
        val files = root.walkTopDown().filter { it.isFile }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        files.sortBy { it.lastModified() } // oldest first
        for (f in files) {
            if (total <= MAX_CACHE_BYTES) break
            if (f == keep) continue
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    // Strip path separators and traversal - only keep a flat, safe file/dir name.
    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifEmpty { "asset" }

    private companion object {
        const val MAX_CACHE_BYTES = 200L * 1024 * 1024 // 200 MB
    }
}
