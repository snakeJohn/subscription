package com.substat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.substat.app.data.AppRelease
import com.substat.app.data.Subscription
import com.substat.app.data.SubscriptionPayload

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, ui: UiState) {
    val p = LocalPalette.current
    val snack = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Subscription?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Subscription?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var formPrefill by remember { mutableStateOf<SubscriptionPayload?>(null) }

    /* toast / error 统一走 Snackbar */
    LaunchedEffect(ui.toast, ui.error) {
        val msg = ui.error ?: ui.toast
        if (msg != null) {
            snack.showSnackbar(msg)
            vm.clearToast()
        }
    }

    Scaffold(
        containerColor = p.paper,
        snackbarHost = { SnackbarHost(snack) },
        topBar = { Masthead(vm, ui) },
        bottomBar = { BottomTabs(vm, ui) },
        floatingActionButton = {
            if (ui.tab == Tab.Dash || ui.tab == Tab.List) {
                FloatingActionButton(
                    onClick = { showPicker = true },
                    containerColor = p.red,
                    contentColor = Color.White,
                    shape = RectangleShape,
                ) { Icon(Icons.Filled.Add, contentDescription = "添加订阅") }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (ui.tab) {
                Tab.Dash -> DashboardTab(vm, ui) { sub -> editing = sub; showForm = true }
                Tab.List -> ListTab(
                    vm, ui,
                    onEdit = { sub -> editing = sub; showForm = true },
                    onDelete = { sub -> confirmDelete = sub },
                )
                Tab.Calendar -> CalendarTab(vm, ui) { sub -> editing = sub; showForm = true }
                Tab.Settings -> SettingsTab(vm, ui)
            }
        }
    }

    if (showPicker) {
        CatalogPicker(
            vm = vm, ui = ui,
            onManual = { showPicker = false; editing = null; formPrefill = null; showForm = true },
            onPick = { payload ->
                showPicker = false; editing = null; formPrefill = payload; showForm = true
            },
            onDismiss = { showPicker = false },
        )
    }
    if (showForm) {
        SubscriptionForm(
            vm = vm, ui = ui, existing = editing, prefill = formPrefill,
            onDismiss = { showForm = false; editing = null; formPrefill = null },
            onDelete = { sub ->
                showForm = false; editing = null; formPrefill = null; confirmDelete = sub
            },
        )
    }
    confirmDelete?.let { sub ->
        ConfirmDialog(
            title = "删除订阅",
            message = "确认删除「${sub.name}」？此操作不可撤销。",
            confirmText = "删除",
            onConfirm = { vm.delete(sub); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
    val ctx = LocalContext.current
    ui.update?.let { rel ->
        UpdateDialog(
            rel = rel,
            downloading = ui.updateDownloading,
            progress = ui.updateProgress,
            onConfirm = { vm.downloadAndInstall(ctx) },
            onDismiss = { if (!ui.updateDownloading) vm.dismissUpdate() },
        )
    }
}

/** 发现新版本时的更新对话框：展示更新说明 + 下载进度 */
@Composable
private fun UpdateDialog(
    rel: AppRelease,
    downloading: Boolean,
    progress: Float,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = p.paper,
        title = {
            Text("发现新版本 v${rel.versionName}", fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold, color = p.ink)
        },
        text = {
            Column {
                if (rel.notes.isNotBlank()) {
                    Text(rel.notes, fontSize = 13.sp, color = p.ink2, lineHeight = 19.sp)
                }
                if (downloading) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = p.red, trackColor = p.paper2,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("下载中 ${(progress * 100).toInt()}%", fontSize = 11.sp,
                        color = p.ink3, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !downloading) {
                Text(if (downloading) "下载中…" else "立即更新", color = p.red,
                    fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) {
                Text("以后", color = p.ink3)
            }
        },
    )
}

/** 顶部报头：刊名 + 汇率 + 币种切换 */
@Composable
private fun Masthead(vm: MainViewModel, ui: UiState) {
    val p = LocalPalette.current
    /* 自定义顶栏要自己处理状态栏 inset：背景先铺满（含状态栏下方），内容再让位 */
    Column(Modifier.background(p.paper).statusBarsPadding()
        .padding(start = 16.dp, end = 8.dp, top = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SubStat", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = 22.sp, color = p.ink)
            Spacer(Modifier.size(8.dp))
            Text(
                "USD/CNY ${"%.4f".format(ui.prefs.rate).trimEnd('0').trimEnd('.')}",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = p.ink3,
            )
            IconButton(onClick = { vm.refreshRate() }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Refresh, "刷新汇率", tint = p.ink3,
                    modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.weight(1f))
            CurrencyToggle(ui.prefs.cur) { vm.setCur(it) }
        }
        if (ui.fromCache) {
            Spacer(Modifier.height(4.dp))
            Text("离线数据", fontSize = 10.sp, color = p.warn,
                fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink))
    }
}

@Composable
private fun CurrencyToggle(cur: String, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    Row {
        listOf("CNY" to "¥", "USD" to "$").forEach { (code, sym) ->
            val on = cur == code
            Box(
                Modifier
                    .background(if (on) p.ink else Color.Transparent)
                    .clickable { onPick(code) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text("$sym $code", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = if (on) p.paper else p.ink3,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun BottomTabs(vm: MainViewModel, ui: UiState) {
    val p = LocalPalette.current
    NavigationBar(containerColor = p.card, tonalElevation = 0.dp) {
        data class T(val tab: Tab, val label: String,
                     val icon: androidx.compose.ui.graphics.vector.ImageVector)
        listOf(
            T(Tab.Dash, "总览", Icons.Filled.Star),
            T(Tab.List, "明细", Icons.AutoMirrored.Filled.List),
            T(Tab.Calendar, "日历", Icons.Filled.DateRange),
            T(Tab.Settings, "设置", Icons.Filled.Settings),
        ).forEach { t ->
            NavigationBarItem(
                selected = ui.tab == t.tab,
                onClick = { vm.selectTab(t.tab) },
                icon = { Icon(t.icon, t.label, modifier = Modifier.size(20.dp)) },
                label = { Text(t.label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = p.red,
                    selectedTextColor = p.red,
                    unselectedIconColor = p.ink4,
                    unselectedTextColor = p.ink4,
                    indicatorColor = p.paper2,
                ),
            )
        }
    }
}
