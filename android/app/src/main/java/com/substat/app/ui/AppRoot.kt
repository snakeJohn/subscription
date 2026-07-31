package com.substat.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun AppRoot(vm: MainViewModel, ui: UiState) {
    Surface(modifier = Modifier.fillMaxSize()) {
        when (ui.phase) {
            Phase.Booting -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            Phase.Setup -> SetupScreen(vm, ui)
            Phase.Login -> LoginScreen(vm, ui)
            Phase.Ready -> HomeScreen(vm, ui)
        }
    }
}
