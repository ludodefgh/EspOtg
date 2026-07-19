package com.espotg.core.github

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubClientTest {

    @Test
    fun `parses owner-repo shorthand`() {
        val ref = GitHubClient.parseRepoRef("ludodefgh/EspOtg")
        assertEquals("ludodefgh", ref?.owner)
        assertEquals("EspOtg", ref?.repo)
    }

    @Test
    fun `parses full github url with trailing path and dot-git`() {
        assertEquals("EspOtg", GitHubClient.parseRepoRef("https://github.com/ludodefgh/EspOtg/releases")?.repo)
        assertEquals("EspOtg", GitHubClient.parseRepoRef("github.com/ludodefgh/EspOtg.git")?.repo)
        assertEquals("ludodefgh", GitHubClient.parseRepoRef("https://github.com/ludodefgh/EspOtg")?.owner)
    }

    @Test
    fun `rejects incomplete refs`() {
        assertNull(GitHubClient.parseRepoRef(""))
        assertNull(GitHubClient.parseRepoRef("ludodefgh"))
        assertNull(GitHubClient.parseRepoRef("https://github.com/ludodefgh"))
    }

    @Test
    fun `parses a releases json payload and filters bin assets`() {
        val payload = """
            [
              {"tag_name":"v0.1.17","name":"Release 17","prerelease":false,"draft":false,
               "assets":[
                 {"name":"app-release.apk","browser_download_url":"https://x/apk","size":10},
                 {"name":"firmware.bin","browser_download_url":"https://x/bin","size":200}
               ]},
              {"tag_name":"v0.1.0-rc","prerelease":true,"draft":true,"assets":[]}
            ]
        """.trimIndent()
        val releases = Json { ignoreUnknownKeys = true }
            .decodeFromString(ListSerializer(GitHubRelease.serializer()), payload)
        assertEquals(2, releases.size)
        assertEquals("v0.1.17", releases[0].tagName)
        assertEquals(1, releases[0].binAssets.size)
        assertEquals("firmware.bin", releases[0].binAssets.first().name)
        assertTrue(releases[1].draft)
    }
}
