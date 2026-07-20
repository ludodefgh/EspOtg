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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.espotg.app.ui.AppViewModel
import com.espotg.app.ui.components.BackIcon
import com.espotg.app.ui.components.MenuIcon
import com.espotg.core.github.GitHubAsset
import com.espotg.core.github.GitHubRelease

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleasesScreen(appViewModel: AppViewModel, onOpenDrawer: () -> Unit, onBack: () -> Unit) {
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
                navigationIcon = { BackIcon(onBack) },
                title = {
                    Column {
                        Text("Linked repo")
                        repo?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                },
                actions = {
                    if (repo != null) {
                        IconButton(onClick = { appViewModel.fetchReleases() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    MenuIcon(onOpenDrawer)
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (repo == null) {
                BindRepoSection(
                    error = error,
                    onBind = { appViewModel.bindRepo(it) },
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Releases", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = { appViewModel.unbindRepo() }) { Text("Unlink") }
            }
            Spacer(Modifier.height(8.dp))

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
                releases.isEmpty() -> Text("No releases found for this repo.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(releases, key = { it.tagName }) { release ->
                        ReleaseCard(
                            release = release,
                            downloadingAsset = downloading,
                            onDownload = { asset -> appViewModel.downloadAndAddAsset(release, asset) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BindRepoSection(error: String?, onBind: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Text("Link this device to a GitHub repo", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Connect and identify a device first, then link it to a GitHub repo to browse and flash its releases.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text("owner/repo or github.com URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = { onBind(input) }, enabled = input.isNotBlank()) { Text("Link repo") }
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
