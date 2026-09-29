/**
 * 明细页 —— ListScreen
 *
 * 这个页面干什么：
 *   把当前账本里的记录按日期倒着列出来，能按时间段、分类、关键字筛，还能改、删、标已还。
 *   上面一屏是筛选 + 汇总，下面就是一条条的明细。
 *
 * 怎么用（签名跟 MainActivity 里接的那一个参数一致，别改）：
 *   ListScreen(onEdit = { id -> 切到记账页去改这一条 })
 *
 * 页面里都有什么：
 *   - 时间筛选：本月 / 上月 / 近三月 / 今年 / 全部 / 自定义（自定义是先选开始、再选结束）
 *   - 汇总条：这个区间里的 收入 / 支出 / 结余 / 笔数，外加「垫付未还」还有多少
 *   - 按日期分组，每组一个小标题（今天 / 昨天 / 9月12日 周五）和当天小计
 *   - 每条：分类（二级分类用 · 跟在后面）、商户或备注、账户、金额；垫付的写「垫 · 张三（已还）」
 *   - 点一条 = 去记账页改它；长按 = 弹菜单（编辑 / 删除 / 标记已还 / 取消已还）
 *   - 搜索框按商户、备注、分类、二级分类模糊筛；分类可以单独筛
 *
 * 约定：
 *   1. 数据只读 Store.transactions()，改（删除、标已还）走数据层再 Store.save()。
 *   2. 订阅 Store.version，数据一变自己重画。
 *   3. 金额颜色：支出 / 收入取主题里的 LocalExtraColors（它已经跟着设置里的
 *      「支出红、收入绿」走），本文件不写死色值。
 */
package com.moneytracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneytracker.data.Store
import com.moneytracker.data.Transaction
import com.moneytracker.data.nowIso
import com.moneytracker.ui.theme.LocalExtraColors
import com.moneytracker.util.Dates
import com.moneytracker.util.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val RANGE_KEYS = listOf("本月", "上月", "近三月", "今年", "全部", "自定义")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListScreen(onEdit: (String) -> Unit) {

    val version by Store.version.collectAsState()
    val extra = LocalExtraColors.current
    val currency = Store.data.settings.currency

    // ==================== 筛选状态 ====================
    var rangeKey by remember { mutableStateOf("本月") }
    var customFrom by remember { mutableStateOf("") }
    var customTo by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var catFilter by remember { mutableStateOf("") }   // 空串 = 全部分类

    // 自定义区间的两步：1 = 先选开始，2 = 再选结束
    var dateStage by remember { mutableStateOf(0) }
    var pickFrom by remember { mutableStateOf("") }

    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Transaction?>(null) }
    var tip by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun showTip(msg: String) {
        tip = msg
        scope.launch {
            delay(1600)
            if (tip == msg) tip = ""
        }
    }

    // ==================== 取数 ====================
    val today = Dates.today()
    val thisYm = Dates.yearMonth(today)
    val thisYear = thisYm.substring(0, 4)

    val range: Pair<String, String> = when (rangeKey) {
        "本月" -> Store.monthRange(thisYm)
        "上月" -> Store.monthRange(Dates.shiftMonth(thisYm, -1))
        "近三月" -> Store.monthRange(Dates.shiftMonth(thisYm, -2)).first to Store.monthRange(thisYm).second
        "今年" -> "$thisYear-01-01" to "$thisYear-12-31"
        "全部" -> "" to "9999-12-31"
        else -> if (customFrom.isNotEmpty() && customTo.isNotEmpty()) customFrom to customTo else Store.monthRange(thisYm)
    }
    val from = range.first
    val to = range.second

    val all = remember(version) { Store.transactions() }
    val ranged = remember(version, from, to) { Store.inRange(from, to, all) }
    val catOptions = remember(version, from, to) {
        ranged.map { it.category.ifEmpty { "其他支出" } }.distinct().sorted()
    }
    val shown = remember(version, from, to, query, catFilter) {
        var list = ranged
        if (catFilter.isNotEmpty()) {
            list = list.filter { it.category.ifEmpty { "其他支出" } == catFilter }
        }
        val q = query.trim()
        if (q.isNotEmpty()) {
            list = list.filter {
                it.merchant.contains(q, true) || it.note.contains(q, true) ||
                    it.category.contains(q, true) || it.subcategory.contains(q, true)
            }
        }
        list
    }

    val sum = Store.summary(shown)
    val owed = shown
        .filter { it.type == "expense" && it.isAdvance && !it.isReimbursed }
        .sumOf { it.amount }
    val groups = shown.groupBy { it.date }.entries.sortedByDescending { it.key }

    // ==================== 改数据 ====================

    /** 标已还 / 取消已还：只动那一笔，然后叫数据层存 */
    fun setReimbursed(t: Transaction, done: Boolean) {
        val live = Store.data.transactions.firstOrNull { it.id == t.id } ?: return
        live.isReimbursed = done
        live.updatedAt = nowIso()
        Store.save()
        showTip(if (done) "标成已还了" else "取消已还了")
    }

    // ==================== 界面 ====================
    Column(Modifier.fillMaxSize()) {

        // 时间段
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RANGE_KEYS.forEach { k ->
                val label = if (k == "自定义" && customFrom.isNotEmpty() && customTo.isNotEmpty()) {
                    "${shortDate(customFrom)}~${shortDate(customTo)}"
                } else k
                FilterChip(
                    selected = rangeKey == k,
                    onClick = {
                        if (k == "自定义") {
                            pickFrom = if (customFrom.isNotEmpty()) customFrom else today
                            dateStage = 1
                        } else {
                            rangeKey = k
                        }
                    },
                    label = { Text(label) }
                )
            }
        }

        // 搜索
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜商户 / 备注 / 分类") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // 分类筛选
        if (catOptions.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = catFilter.isEmpty(),
                    onClick = { catFilter = "" },
                    label = { Text("全部分类") }
                )
                catOptions.forEach { c ->
                    FilterChip(
                        selected = catFilter == c,
                        onClick = { catFilter = if (catFilter == c) "" else c },
                        label = { Text(c) }
                    )
                }
            }
        }

        // 汇总条
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    SumCell("收入", Money.yuan(sum.income, currency), extra.income, Modifier.weight(1f))
                    SumCell("支出", Money.yuan(sum.expense, currency), extra.expense, Modifier.weight(1f))
                    SumCell(
                        "结余",
                        Money.yuan(sum.net, currency),
                        if (sum.net < 0) extra.expense else extra.income,
                        Modifier.weight(1f)
                    )
                    SumCell("笔数", "${sum.count}", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                }
                if (owed > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "垫付还没还的：${Money.yuan(owed, currency)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (tip.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(
                    text = tip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // 明细
        if (shown.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "这段时间里没有记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "换个时间段，或者去「记账」那边记一笔",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                groups.forEach { entry ->
                    val day = entry.key
                    val dayList = entry.value
                    item(key = "head-$day") {
                        val inc = dayList.filter { it.type == "income" }.sumOf { it.amount }
                        val exp = dayList.filter { it.type == "expense" }.sumOf { it.amount }
                        val bits = mutableListOf<String>()
                        if (exp > 0) bits.add("支出 -${Money.yuan(exp, currency)}")
                        if (inc > 0) bits.add("收入 +${Money.yuan(inc, currency)}")
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = friendlyDate(day),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = bits.joinToString("　"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                    items(dayList, key = { it.id }) { t ->
                        Box {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onEdit(t.id) },
                                        onLongClick = { menuFor = t.id }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = rowTitle(t),
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sub = rowSub(t)
                                    if (sub.isNotEmpty()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = sub,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = rowAmount(t, currency),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.End,
                                    color = when (t.type) {
                                        "income" -> extra.income
                                        "expense" -> extra.expense
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            DropdownMenu(
                                expanded = menuFor == t.id,
                                onDismissRequest = { menuFor = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("编辑") },
                                    onClick = {
                                        menuFor = null
                                        onEdit(t.id)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除") },
                                    onClick = {
                                        menuFor = null
                                        pendingDelete = t
                                    }
                                )
                                if (t.isAdvance) {
                                    DropdownMenuItem(
                                        text = { Text(if (t.isReimbursed) "取消已还" else "标记已还") },
                                        onClick = {
                                            menuFor = null
                                            setReimbursed(t, !t.isReimbursed)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "tail") { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    // 自定义区间：先选开始，再选结束
    if (dateStage == 1) {
        DatePickDialog(
            initial = pickFrom,
            title = "选开始日期",
            onCancel = { dateStage = 0 },
            onPick = {
                pickFrom = it
                dateStage = 2
            }
        )
    } else if (dateStage == 2) {
        DatePickDialog(
            initial = pickFrom,
            title = "选结束日期",
            onCancel = { dateStage = 0 },
            onPick = {
                // 万一用户把结束选在开始前面，就顺手换过来
                if (it < pickFrom) {
                    customFrom = it
                    customTo = pickFrom
                } else {
                    customFrom = pickFrom
                    customTo = it
                }
                rangeKey = "自定义"
                dateStage = 0
            }
        )
    }

    // 删除前问一句
    pendingDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删掉这一笔？") },
            text = {
                Text(
                    "${t.date}　${rowAmount(t, currency)}　" +
                        t.merchant.ifEmpty { t.note.ifEmpty { rowTitle(t) } }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.delete(t.id)
                    pendingDelete = null
                    showTip("删掉了")
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

// ==================== 小组件 ====================

@Composable
private fun SumCell(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(
    initial: String,
    title: String,
    onCancel: () -> Unit,
    onPick: (String) -> Unit
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = dateToMillis(initial))
    DatePickerDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis?.let { millisToDate(it) }
                if (picked != null) onPick(picked) else onCancel()
            }) { Text("好") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    ) {
        DatePicker(state = state, title = { Text(title, modifier = Modifier.padding(16.dp)) })
    }
}

// ==================== 一条记录怎么显示 ====================

private fun rowTitle(t: Transaction): String = when {
    t.type == "transfer" -> "转账"
    t.subcategory.isNotEmpty() -> "${t.category} · ${t.subcategory}"
    t.category.isNotEmpty() -> t.category
    else -> "未分类"
}

private fun rowSub(t: Transaction): String {
    val bits = mutableListOf<String>()
    t.merchant.takeIf { it.isNotEmpty() }?.let { bits.add(it) }
    t.note.takeIf { it.isNotEmpty() && it != t.merchant }?.let { bits.add(it) }
    if (t.type == "transfer") {
        bits.add("${t.account} → ${t.toAccount.ifEmpty { "？" }}")
    } else {
        t.account.takeIf { it.isNotEmpty() }?.let { bits.add(it) }
    }
    if (t.isAdvance) {
        val who = t.forWhom.ifEmpty { "某人" }
        bits.add("垫 · $who" + if (t.isReimbursed) "（已还）" else "")
    }
    return bits.joinToString(" · ")
}

private fun rowAmount(t: Transaction, currency: String): String = when (t.type) {
    "income" -> "+" + Money.yuan(t.amount, currency)
    "expense" -> "-" + Money.yuan(t.amount, currency)
    else -> Money.yuan(t.amount, currency)
}

/** 「今天 / 昨天 / 9月12日 周五」 */
private fun friendlyDate(day: String): String {
    val today = Dates.today()
    if (day == today) return "今天"
    if (day == shiftDay(today, -1)) return "昨天"
    val p = day.split("-")
    if (p.size != 3) return day
    val m = p[1].toIntOrNull() ?: return day
    val d = p[2].toIntOrNull() ?: return day
    val w = weekdayOf(day)
    return if (w.isEmpty()) "${m}月${d}日" else "${m}月${d}日 $w"
}

/** 「2026-09-01」→「9/1」，筛选条上位置紧，写短的 */
private fun shortDate(day: String): String {
    val p = day.split("-")
    if (p.size != 3) return day
    val m = p[1].toIntOrNull() ?: return day
    val d = p[2].toIntOrNull() ?: return day
    return "$m/$d"
}

private fun weekdayOf(day: String): String {
    val d = Dates.parse(day) ?: return ""
    val cal = Calendar.getInstance()
    cal.time = d
    val week = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    val idx = cal.get(Calendar.DAY_OF_WEEK) - 1
    return if (idx in week.indices) week[idx] else ""
}

private fun shiftDay(day: String, delta: Int): String {
    val d = Dates.parse(day) ?: return day
    val cal = Calendar.getInstance()
    cal.time = d
    cal.add(Calendar.DAY_OF_MONTH, delta)
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
}

/** 日期选择器用的是 UTC 零点毫秒，跟着它换算，免得多算或少算一天 */
private fun millisToDate(ms: Long): String {
    val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.US)
    cal.timeInMillis = ms
    return String.format(
        Locale.US, "%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
    )
}

private fun dateToMillis(day: String): Long? {
    val p = day.split("-")
    if (p.size != 3) return null
    val y = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    val d = p[2].toIntOrNull() ?: return null
    val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.US)
    cal.clear()
    cal.set(y, m - 1, d)
    return cal.timeInMillis
}