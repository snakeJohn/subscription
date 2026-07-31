package com.substat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.substat.app.ui.AppRoot
import com.substat.app.ui.MainViewModel
import com.substat.app.ui.SubStatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SubStatApp
        setContent {
            val vm: MainViewModel = viewModel(factory = MainViewModel.factory(app))
            val ui by vm.state.collectAsState()
            SubStatTheme(themePref = ui.prefs.theme) {
                AppRoot(vm = vm, ui = ui)
            }
        }
    }
}
