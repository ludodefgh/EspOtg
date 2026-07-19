package com.espotg.app.ui.releases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.espotg.app.ui.AppViewModel
import com.espotg.core.github.GitHubAsset
import com.espotg.core.github.GitHubRelease

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleasesScreen(appViewModel: AppViewModel, onBack: () -> Unit) {
    val repo by appViewModel.boundRepo.collectAsStateWithLifecycle()
    val releases by appViewModel.releases.collectAsStateWithLifecycle()
    val loading by appViewModel.releasesLoading.collectAsStateWithLifecycle()
    val error by appViewModel.releasesError.collectAsStateWithLifecycle()
    val downloading by appViewModel.downloadingAsset.collectAsStateWithLifecycle()

    LaunchedEffect(repo) {
        if (repo != null && releases.isEmpty()) appViewModel.fetchReleases()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Releases")
                        repo?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
                releases.isEmpty() -> Text("No releases found for this repo.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(releases, key = { it.tagName }) { release ->
                        ReleaseCard(
                            release = release,
                            downloadingAsset = downloading,
                            onDownload = { asset -> appViewModel.downloadAndAddAsset(asset) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseCard(
    release: GitHubRelease,
    downloadingAsset: String?,
    onDownload: (GitHubAsset) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(release.name?.takeIf { it.isNotBlank() } ?: release.tagName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                if (release.prerelease) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                        Text("pre-release", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Text(release.tagName, style = MaterialTheme.typography.labelSmall)
            release.publishedAt?.let { Text(it.take(10), style = MaterialTheme.typography.labelSmall) }

            Spacer(Modifier.height(8.dp))
            val bins = release.binAssets
            if (bins.isEmpty()) {
                Text("No .bin assets in this release.", style = MaterialTheme.typography.bodySmall)
            } else {
                bins.forEach { asset ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(asset.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${asset.size / 1024} KB", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (downloadingAsset == asset.name) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Button(onClick = { onDownload(asset) }, enabled = downloadingAsset == null) {
                                Text("Add")
                            }
                        }
                    }
                }
            }
        }
    }
}
