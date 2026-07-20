package com.espotg.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

/** Hamburger button that opens the app's navigation drawer. */
@Composable
fun MenuIcon(onOpenDrawer: () -> Unit) {
    IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
}

/** Context-aware back arrow (the caller decides the target). */
@Composable
fun BackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}
