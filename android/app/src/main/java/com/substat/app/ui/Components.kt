package com.substat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 小节标题：标题 + 右侧注解 + 下划线，对应 Web 的 .sec-h */
@Composable
fun SectionHeader(title: String, note: String? = null) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp, color = p.ink, modifier = Modifier.weight(1f))
            if (note != null) Text(note, fontSize = 10.sp, letterSpacing = 1.sp,
                color = p.ink4, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.ink))
    }
}

/** 眉标：小号朱红大写字，对应 .kicker */
@Composable
fun Kicker(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        color = LocalPalette.current.red)
}

/** 细分隔线 */
@Composable
fun Hairline(color: Color? = null) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(color ?: p.hair))
}

/** 方框标签，对应 .tg */
@Composable
fun Tag(text: String, color: Color? = null) {
    val p = LocalPalette.current
    val c = color ?: p.ink3
    Box(
        Modifier.border(1.dp, c).padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, fontSize = 9.sp, color = c, fontFamily = FontFamily.Monospace,
            maxLines = 1)
    }
}

/** 品牌字母块：与 Web 端同款稳定色相派生 */
@Composable
fun BrandMark(name: String, size: Int = 26) {
    val ch = name.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        Modifier.size(size.dp).background(brandColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(ch, color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = (size * 0.42f).sp)
    }
}

/** 与 ui-kit.js strColor 同算法，保证两端同名同色 */
fun brandColor(s: String): Color {
    var h = 0
    for (c in s) h = (h * 31 + c.code) % 360
    return hslToColor(h.toFloat(), 0.42f, 0.42f)
}

private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1 - kotlin.math.abs(2 * l - 1)) * s
    val hp = h / 60f
    val x = c * (1 - kotlin.math.abs(hp % 2 - 1))
    val (r1, g1, b1) = when {
        hp < 1 -> Triple(c, x, 0f)
        hp < 2 -> Triple(x, c, 0f)
        hp < 3 -> Triple(0f, c, x)
        hp < 4 -> Triple(0f, x, c)
        hp < 5 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2
    return Color(r1 + m, g1 + m, b1 + m)
}

/** 水平进度条 */
@Composable
fun Track(fraction: Float, color: Color, height: Int = 5) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(height.dp).background(p.paper2)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height.dp)
                .background(color)
        )
    }
}

/** 方角按钮，朱红实心或描边 */
@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val bg = when {
        !enabled -> p.paper2
        primary -> p.red
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> p.ink4
        primary -> Color.White
        danger -> p.bad
        else -> p.ink
    }
    val border = when {
        !enabled -> p.hair
        primary -> p.red
        danger -> p.bad
        else -> p.ink
    }
    Box(
        modifier
            .background(bg)
            .border(1.dp, border)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 12.5.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

/** 分段选择器：一排方格，选中反色 */
@Composable
fun <T> SegmentedPick(
    options: List<Pair<T, String>>,
    selected: T,
    columns: Int = 4,
    onPick: (T) -> Unit,
) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { (value, label) ->
                    val on = value == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (on) p.ink else Color.Transparent)
                            .border(1.dp, if (on) p.ink else p.hair)
                            .clickable { onPick(value) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                            color = if (on) p.paper else p.ink3,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                /* 补齐末行空位，避免最后一行被拉宽 */
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 空态 */
@Composable
fun EmptyState(title: String, hint: String, action: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontFamily = FontFamily.Serif, fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold, color = p.ink)
        Spacer(Modifier.height(7.dp))
        Text(hint, fontSize = 12.5.sp, color = p.ink3)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确认",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = p.paper,
        title = { Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold) },
        text = { Text(message, fontSize = 13.5.sp, color = p.ink2) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = p.bad, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = p.ink3) }
        },
    )
}
