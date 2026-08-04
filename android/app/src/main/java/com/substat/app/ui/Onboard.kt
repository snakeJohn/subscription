package com.substat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 报头：刊名 + 副标 + 双线，与 Web 端一致 */
@Composable
private fun Masthead(sub: String) {
    val p = LocalPalette.current
    Column {
        Text("SubStat", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
            fontSize = 40.sp, color = p.ink)
        Spacer(Modifier.height(6.dp))
        Text(sub, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp, color = p.red)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(p.ink))
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.ink))
    }
}

@Composable
fun SetupScreen(vm: MainViewModel, ui: UiState) {
    val p = LocalPalette.current
    var url by rememberSaveable { mutableStateOf("https://") }
    Box(
        Modifier.fillMaxSize().background(p.paper).systemBarsPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Masthead("首次配置")
            Spacer(Modifier.height(22.dp))
            Text("填入你部署的 Worker 地址，例如 https://substat.你的子域.workers.dev",
                fontSize = 13.sp, color = p.ink3)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("服务器地址") },
                placeholder = { Text("https://substat.example.workers.dev") },
                singleLine = true,
                isError = ui.setupError != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { vm.saveBaseUrl(url) }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (ui.setupError != null) {
                Spacer(Modifier.height(8.dp))
                Text(ui.setupError, color = p.bad, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { vm.saveBaseUrl(url) },
                enabled = !ui.busy,
                shape = androidx.compose.ui.graphics.RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = p.red),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (ui.busy) CircularProgressIndicator(
                    modifier = Modifier.height(16.dp), strokeWidth = 2.dp,
                    color = androidx.compose.ui.graphics.Color.White,
                ) else Text("连接", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "地址需可公网访问且已部署 SubStat Worker。会校验 /api/health 后再保存。",
                fontSize = 11.sp, color = p.ink4,
            )
        }
    }
}

@Composable
fun LoginScreen(vm: MainViewModel, ui: UiState) {
    val p = LocalPalette.current
    var mode by rememberSaveable { mutableStateOf("login") }   // login | register
    var username by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val reg = mode == "register"
    val submit = { if (reg) vm.register(username, pw, code) else vm.login(username, pw) }
    Box(
        Modifier.fillMaxSize().background(p.paper).systemBarsPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 400.dp).padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Masthead(if (reg) "注册新账号" else "订阅计费统计")
            Spacer(Modifier.height(22.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                isError = ui.loginError != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = ui.loginError != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (reg) ImeAction.Next else ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (reg) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("注册码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ui.loginError != null) {
                Spacer(Modifier.height(8.dp))
                Text(ui.loginError, color = p.bad, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { submit() },
                enabled = !ui.busy,
                shape = androidx.compose.ui.graphics.RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = p.red),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (ui.busy) CircularProgressIndicator(
                    modifier = Modifier.height(16.dp), strokeWidth = 2.dp,
                    color = androidx.compose.ui.graphics.Color.White,
                ) else Text(if (reg) "注 册" else "登 录",
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            if (ui.registerOpen) {
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = { mode = if (reg) "login" else "register" }) {
                        Text(if (reg) "已有账号？去登录" else "没有账号？注册",
                            fontSize = 12.sp, color = p.red)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = { vm.resetServer() }) {
                    Text("更换服务器地址", fontSize = 12.sp, color = p.ink3)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "数据存储在你自己的 Cloudflare D1，各账号数据互相隔离。",
                fontSize = 11.sp, color = p.ink4,
            )
        }
    }
}
