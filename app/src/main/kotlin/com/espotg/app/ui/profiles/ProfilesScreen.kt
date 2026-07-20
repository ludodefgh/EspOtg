package com.espotg.app.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.espotg.app.data.DeviceProfile
import com.espotg.app.ui.AppViewModel
import com.espotg.app.ui.components.BackIcon
import com.espotg.app.ui.components.MenuIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    appViewModel: AppViewModel,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    onSelectProfile: (DeviceProfile) -> Unit,
) {
    val profiles by appViewModel.profileRepository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackIcon(onBack) },
                title = { Text("Saved device profiles") },
                actions = { MenuIcon(onOpenDrawer) },
            )
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No saved profiles yet - flash a device once to remember its settings.")
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(profiles, key = { it.macAddress }) { profile ->
                    ListItem(
                        headlineContent = { Text(profile.label ?: profile.macAddress) },
                        supportingContent = { Text("${profile.chipType} - ${profile.flashEntries.size} binaries") },
                        modifier = Modifier.clickable { onSelectProfile(profile) },
                        trailingContent = {
                            IconButton(onClick = { appViewModel.deleteProfile(profile) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
