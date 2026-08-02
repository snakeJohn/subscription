package com.substat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.substat.app.data.CatalogItem
import com.substat.app.data.CatalogPlan
import com.substat.app.data.Category
import com.substat.app.data.Cycle
import com.substat.app.data.SubscriptionPayload

/**
 * 服务库选择器：点「＋」先开此表，选服务 + 方案即带公开参考价预填订阅表单；
 * 顶部「手动添加」是逃生口，直接开空白表单。
 * 与 SubscriptionForm 同款 ModalBottomSheet 呈现（方角 / 纸底 / 无拖拽把手）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogPicker(
    vm: MainViewModel,
    ui: UiState,
    onManual: () -> Unit,
    onPick: (SubscriptionPayload) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { vm.loadCatalog() }

    var query by remember { mutableStateOf("") }
    var catSel by remember { mutableStateOf<String?>(null) }   // null = 全部
    var expanded by remember { mutableStateOf<String?>(null) } // 展开多方案的条目名

    val showNsfw = ui.prefs.showNsfw
    val q = query.trim().lowercase()
    /* nsfw + 搜索过滤后的基集：决定分类 chip 与列表内容 */
    val base = ui.catalog.filter { item ->
        if (!showNsfw && item.nsfw == 1) return@filter false
        if (q.isEmpty()) return@filter true
        "${item.name} ${item.domain}".lowercase().contains(q)
    }
    /* 出现过的分类（按枚举顺序稳定）；关闭 NSFW 时不出成人分类 chip */
    val cats = Category.entries.filter { c ->
        (showNsfw || c != Category.NSFW) && base.any { it.cat == c.key }
    }
    val visible = base.filter { catSel == null || it.cat == catSel }
    val capped = visible.take(60)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        shape = RectangleShape,
        containerColor = p.paper,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
        ) {
            /* 报头：刊名式标题 + 等宽注解 + 粗墨线 */
            Row(verticalAlignment = Alignment.Bottom) {
                Text("服务库", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp, color = p.ink, modifier = Modifier.weight(1f))
                Text("点选即预填 · 价格为公开参考", fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp, letterSpacing = 1.sp, color = p.ink4)
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink))
            Spacer(Modifier.height(14.dp))

            InkButton("手动添加", onManual, primary = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("搜索服务 / 域名…", fontSize = 13.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            /* 分类横向筛选：仿 SegmentedPick 单元，可横滑 */
            if (cats.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    CatChip("全部", catSel == null) { catSel = null }
                    cats.forEach { c ->
                        CatChip(c.label, catSel == c.key) { catSel = c.key }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            when {
                ui.catalogLoading && ui.catalog.isEmpty() ->
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = p.red, strokeWidth = 2.dp) }

                visible.isEmpty() -> EmptyState("没有匹配的服务", "换个关键词或分类试试")

                else -> {
                    Column(Modifier.fillMaxWidth()) {
                        capped.forEachIndexed { i, item ->
                            if (i > 0) Hairline()
                            CatalogRow(
                                item = item,
                                isExpanded = expanded == item.name,
                                onToggle = {
                                    expanded = if (expanded == item.name) null else item.name
                                },
                                onPick = onPick,
                            )
                        }
                    }
                    /* 截断保护：只渲染前 60 条，其余提示继续筛选，绝不静默丢弃 */
                    if (visible.size > capped.size) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "还有 ${visible.size - capped.size} 项，输入关键词继续筛选",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = p.ink4,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 分类方格：仿 SegmentedPick 单元，选中反色；因横向滚动故按内容宽而非等分 */
@Composable
private fun CatChip(label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .background(if (on) p.ink else Color.Transparent)
            .border(1.dp, if (on) p.ink else p.hair)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
            color = if (on) p.paper else p.ink3,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

/** 服务行：品牌块 + 名称/域名 + 首方案价；单方案直接选，多方案点开逐条列出 */
@Composable
private fun CatalogRow(
    item: CatalogItem,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onPick: (SubscriptionPayload) -> Unit,
) {
    val p = LocalPalette.current
    val single = item.plans.size <= 1
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                val first = item.plans.firstOrNull()
                if (single) { if (first != null) onPick(payloadOf(item, first)) } else onToggle()
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(item.name, 30)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
                fontSize = 14.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.domain.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(item.domain, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                    color = p.ink4, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        item.plans.firstOrNull()?.let { fp ->
            Text(priceLabel(fp), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = p.ink2, maxLines = 1)
        }
    }
    /* 多方案：点开后每个方案独立成行，点行即选 */
    if (isExpanded && item.plans.size > 1) {
        item.plans.forEach { pl ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(payloadOf(item, pl)) }
                    .padding(start = 40.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(pl.plan.ifBlank { "标准" }, fontSize = 13.sp, color = p.ink2,
                    modifier = Modifier.weight(1f))
                Text(priceLabel(pl), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = p.ink)
                Spacer(Modifier.width(8.dp))
                Tag(Cycle.from(pl.cycle).label)
            }
        }
    }
}

/** 由目录条目 + 方案构造预填负载；start/qty 交给表单默认（今日 / 1） */
private fun payloadOf(item: CatalogItem, plan: CatalogPlan) = SubscriptionPayload(
    name = item.name,
    domain = item.domain,
    cat = item.cat,
    plan = plan.plan,
    price = plan.price,
    cur = plan.cur,
    cycle = plan.cycle,
    start = "",
    nsfw = item.nsfw,
)

/** 价格去尾零并附币种：2.99→"2.99 USD"，20.0→"20 CNY" */
private fun priceLabel(pl: CatalogPlan): String {
    val v = pl.price
    val n = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    return "$n ${pl.cur}"
}
