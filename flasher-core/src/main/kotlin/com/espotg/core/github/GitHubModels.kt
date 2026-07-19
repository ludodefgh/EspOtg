package com.espotg.core.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
) {
    /** Assets that look like flashable firmware (.bin), which is what the app cares about. */
    val binAssets: List<GitHubAsset> get() = assets.filter { it.name.endsWith(".bin", ignoreCase = true) }
}

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String? = null,
)

/** A parsed "owner/repo" reference. */
data class RepoRef(val owner: String, val repo: String) {
    val slug: String get() = "$owner/$repo"
}
