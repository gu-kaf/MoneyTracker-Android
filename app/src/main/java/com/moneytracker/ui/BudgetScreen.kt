package com.moneytracker.ui

/**
 * 预算页。
 *
 * 干嘛用的：
 *   1. 一张总览卡：总预算、总已花、还剩几天可花（顺带算出每天还能花多少）
 *   2. 每一项预算一行：分类名、上限、已花、剩余、进度条
 *      进度条颜色分三档：不到 80% 用主题色，80% ~ 100% 用橙黄，超了用红色
 *   3. 每项下面一句话：按现在的花法，到期够不够
 *   4. 新增预算（选分类或「总计」，填上限）、点某一项改上限、右边垃圾桶删掉
 *   5. 「按上三月均摊」：算最近三个月每个分类的实际平均花销，一键把所有分类的
 *      预算填上（已经有的就改上限，没有的就新建）
 *
 * 数据读写说明：读用 Store.budgets()，写用 Store.addBudget / updateBudget /
 * deleteBudget（数据层自己会 save），月预算的「已花」直接用 Store.budgetProgress，
 * 界面靠 Store.version 触发重画。数据层那几个文件一个字都没改，这里只是调它
 * 公开的口子。
 *
 * 口径：
 *   - 「已花」只算支出（expense），转账不算，标了「不计入预算」的也不算
 *   - 总览卡按「本月」口径算；周预算 / 年预算在自己的周期里单独核对
 *   - 到期是否够用，用的是「这段时间的日均 × 整个周期的天数」这个估法
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneytracker.data.Budget
import com.moneytracker.data.Categories
import com.moneytracker.data.Store
import com.moneytracker.data.Transaction
import com.moneytracker.ui.theme.LocalExtraColors
import com.moneytracker.util.Dates
import com.moneytracker.util.Money
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 警告用的橙黄：预算进度条 80% ~ 100% 这一档专用。
 *
 * 这是语义常量，故意不跟主题漂：它代表「还没超，但你得注意了」，夹在
 * 「主题色（正常）」和「红（已经超了）」中间，三档必须互相分得开，预警才
 * 有渐进感；两档全红的话，用户就分不出快超了和已经超了。
 * colorScheme 里没有橙黄这一档（几套主题都没定义 tertiary，用它只会掉到
 * Material 默认紫，反而撞不上语义），所以留这一个常量，跟主题里那份
 * chartPalette 是同一个定位：语义色，不跟主题走。
 * 本文件其余颜色一律从 MaterialTheme.colorScheme / LocalExtraColors 取。
 */
private val WarnAmber = Color(0xFFE8A33D)

private val DayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private fun dayOf(d: Date): String = DayFmt.format(d)

/** 两个日期差几天（to - from），解析不了返回 0 */
private fun daysBetween(from: String, to: String): Int {
    val a = Dates.parse(from) ?: return 0
    val b = Dates.parse(to) ?: return 0
    return ((b.time - a.time) / 86400000L).toInt()
}

/** 某个预算周期管的时间段：周一~周日 / 整月 / 整年 */
private fun periodRange(period: String, today: String): Pair<String, String> {
    val d = Dates.parse(today) ?: return Store.monthRange(Dates.yearMonth(today))
    val cal = Calendar.getInstance()
    cal.time = d
    return when (period) {
        "weekly" -> {
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val from = dayOf(cal.time)
            cal.add(Calendar.DAY_OF_MONTH, 6)
            from to dayOf(cal.time)
        }
        "yearly" -> {
            val y = cal.get(Calendar.YEAR)
            String.format(Locale.US, "%04d-01-01", y) to String.format(Locale.US, "%04d-12-31", y)
        }
        else -> Store.monthRange(Dates.yearMonth(today))
    }
}

private fun periodName(period: String): String = when (period) {
    "weekly" -> "周预算"
    "yearly" -> "年预算"
    else -> "月预算"
}

private fun periodEndWord(period: String): String = when (period) {
    "weekly" -> "周末"
    "yearly" -> "年底"
    else -> "月底"
}

/** 这一项预算核算下来的一整行数据 */
private data class BudgetRow(
    val id: String,
    val scope: String,
    val label: String,
    val amount: Long,
    val spent: Long,
    val period: String,
    val verdict: String
) {
    val remaining: Long get() = amount - spent
    val ratio: Float get() = if (amount <= 0L) 0f else spent.toFloat() / amount.toFloat()
}

/** 某个分类/总计在这段时间里花了多少（只算支出，转账和标了不计入预算的都不算） */
private fun spentIn(list: List<Transaction>, scope: String): Long =
    list.filter { it.type == "expense" && !it.excludeFromBudget }
        .filter { scope == "total" || it.category == scope }
        .sumOf { it.amount }

/**
 * 按「这段时间的日均 × 整个周期天数」估一估够不够，然后写成人话。
 * 估法一定写在下面那句小字里，免得用户不知道这数是怎么来的。
 */
private fun verdictOf(amount: Long, spent: Long, period: String, from: String, to: String, today: String, currency: String): String {
    if (amount <= 0L) return "还没设上限，点这一行就能填。"
    // 周期还没开始（比如提前设的下个月预算），没什么好算的
    if (today < from) return "这个周期还没开始。"
    val totalDays = daysBetween(from, to) + 1
    val passed = if (today > to) totalDays else daysBetween(from, today) + 1
    val left = if (today > to) 0 else daysBetween(today, to) + 1
    val endWord = periodEndWord(period)

    if (spent <= 0L) return "${periodName(period)}上限 ${Money.yuan(amount, currency)}，这段时间还一分没花，够。"

    // 日均 × 整个周期天数 = 照这个速度到期的总花销
    val projected = spent * totalDays / passed
    val diff = amount - projected
    val pace = "日均 ${Money.yuan(spent / passed, currency)} × $totalDays 天 ≈ ${Money.yuan(projected, currency)}"

    return when {
        diff >= amount / 5 -> "按现在的花法，${endWord}够用：$pace，离上限还差 ${Money.yuan(diff, currency)}。"
        diff >= 0L -> "按现在的花法，${endWord}刚够线：$pace，上限 ${Money.yuan(amount, currency)}，几乎不剩了。"
        left > 0 -> "按现在的花法，${endWord}要超：$pace，比上限多 ${Money.yuan(-diff, currency)}。还剩 $left 天，每天别超过 ${Money.yuan(maxOf(0L, amount - spent) / left, currency)}。"
        else -> "这个周期已经过完了：实际花了 ${Money.yuan(spent, currency)}，上限 ${Money.yuan(amount, currency)}，超了 ${Money.yuan(spent - amount, currency)}。"
    }
}

/** 把预算列表算成界面要的一行行（按周期口径分别核算） */
private fun buildBudgetRows(today: String, currency: String): List<BudgetRow> {
    return Store.budgets().map { b ->
        val range = periodRange(b.period, today)
        // 月预算直接用数据层给的进度（Store.budgetProgress，口径跟电脑版一致）；
        // 周预算 / 年预算得按各自的周期自己算，那个口子只算本月。
        val spent = if (b.period == "monthly") {
            Store.budgetProgress(b).first
        } else {
            spentIn(Store.inRange(range.first, range.second, Store.transactions()), b.scope)
        }
        BudgetRow(
            id = b.id,
            scope = b.scope,
            label = if (b.scope == "total") "总计" else b.scope,
            amount = b.amount,
            spent = spent,
            period = b.period,
            verdict = verdictOf(b.amount, spent, b.period, range.first, range.second, today, currency)
        )
    }
}

@Composable
fun BudgetScreen() {
    val version = Store.version.collectAsState().value
    val currency = Store.data.settings.currency
    val today = Dates.today()

    val rows = remember(version, today) { buildBudgetRows(today, currency) }
    val monthRows = remember(version, today) { rows.filter { it.period == "monthly" } }
    val monthSpent = remember(version, today) {
        val r = Store.monthRange(Dates.yearMonth(today))
        spentIn(Store.inRange(r.first, r.second), "total")
    }

    var editing by remember { mutableStateOf<BudgetRow?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BudgetOverview(monthRows = monthRows, monthSpent = monthSpent, currency = currency, today = today)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showAdd = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新增预算")
            }
            OutlinedButton(
                onClick = {
                    val n = fillFromLastThreeMonths(today)
                    hint = if (n <= 0) {
                        "最近三个月没查到支出，没东西可以均摊。"
                    } else {
                        "已按最近三个月的实际平均花销填了 $n 项预算。"
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("按上三月均摊")
            }
        }

        if (hint.isNotEmpty()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (rows.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("还没设过预算", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "点上面的「新增预算」挑个分类填个数，或者直接「按上三月均摊」让程序照你最近三个月的实际花销先填一版。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            rows.forEach { r ->
                BudgetCard(
                    row = r,
                    currency = currency,
                    onEdit = { editing = r },
                    onDelete = {
                        // 删预算走数据层的口子，它自己会 save()
                        Store.deleteBudget(r.id)
                        hint = "已删掉「${r.label}」的预算。"
                    }
                )
            }
        }

        Text(
            text = "预算只存在这台手机上，跟电脑版同一份 db.json，导过去也认得。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    if (showAdd) {
        BudgetDialog(
            title = "新增预算",
            initialScope = "total",
            initialAmount = "",
            onDismiss = { showAdd = false },
            onConfirm = { scope, amount ->
                upsertBudget(scope, amount)
                showAdd = false
                hint = "已记下「${if (scope == "total") "总计" else scope}」的月预算 ${Money.yuan(amount, currency)}。"
            }
        )
    }

    editing?.let { row ->
        BudgetDialog(
            title = "改「${row.label}」的上限",
            initialScope = row.scope,
            initialAmount = Money.text(row.amount),
            onDismiss = { editing = null },
            onConfirm = { _, amount ->
                // 按 id 捞出原来那条，改上限之后整条交回数据层
                val old = Store.budgets().firstOrNull { it.id == row.id }
                if (old != null) {
                    Store.updateBudget(old.copy(amount = amount))
                    hint = "「${row.label}」的上限改成 ${Money.yuan(amount, currency)}。"
                }
                editing = null
            }
        )
    }
}

// ==================== 总览 ====================

@Composable
private fun BudgetOverview(monthRows: List<BudgetRow>, monthSpent: Long, currency: String, today: String) {
    val extra = LocalExtraColors.current
    // 有「总计」就用总计的上限当总预算；只有分类预算的话就把分类加起来
    val totalBudget = monthRows.firstOrNull { it.scope == "total" }?.amount
        ?: monthRows.filter { it.scope != "total" }.sumOf { it.amount }
    val range = Store.monthRange(Dates.yearMonth(today))
    val daysLeft = if (today > range.second) 0 else daysBetween(today, range.second) + 1
    val quota = if (daysLeft > 0) maxOf(0L, totalBudget - monthSpent) / daysLeft else 0L

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("本月预算", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OverviewItem("总预算", Money.yuan(totalBudget, currency), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                OverviewItem("总已花", Money.yuan(monthSpent, currency), extra.expense, Modifier.weight(1f))
                OverviewItem("还剩几天", if (daysLeft > 0) "$daysLeft 天" else "0 天", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (totalBudget <= 0L) {
                    "还没设月预算。设一个就能看到每天还能花多少。"
                } else if (daysLeft <= 0) {
                    "这个月已经过完了。"
                } else {
                    "剩下的额度摊到剩下 $daysLeft 天，每天还能花 ${Money.yuan(quota, currency)}。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OverviewItem(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ==================== 一行预算 ====================

@Composable
private fun BudgetCard(row: BudgetRow, currency: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    val extra = LocalExtraColors.current
    val barColor = when {
        row.ratio > 1f -> MaterialTheme.colorScheme.error
        row.ratio >= 0.8f -> WarnAmber
        else -> MaterialTheme.colorScheme.primary
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val remainColor = if (row.remaining < 0) extra.expense else MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                if (row.period != "monthly") {
                    Text(
                        text = periodName(row.period),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "上限 ${Money.yuan(row.amount, currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删掉「${row.label}」的预算",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
            ) {
                val r = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(color = track, cornerRadius = r)
                val frac = if (row.ratio < 0f) 0f else if (row.ratio > 1f) 1f else row.ratio
                if (frac > 0f) {
                    drawRoundRect(
                        color = barColor,
                        size = Size(size.width * frac, size.height),
                        cornerRadius = r
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "已花 ${Money.yuan(row.spent, currency)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = if (row.remaining >= 0) "还剩 ${Money.yuan(row.remaining, currency)}"
                    else "超了 ${Money.yuan(-row.remaining, currency)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = remainColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = row.verdict,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "用了 ${(row.ratio * 100f).toInt()}%（不到 80% 是主题色，80% 起转橙黄，超 100% 变红）",
                style = MaterialTheme.typography.labelSmall,
                color = barColor
            )
        }
    }
}

// ==================== 新增 / 修改 ====================

@Composable
private fun BudgetDialog(
    title: String,
    initialScope: String,
    initialAmount: String,
    onDismiss: () -> Unit,
    onConfirm: (scope: String, amount: Long) -> Unit
) {
    var scope by remember { mutableStateOf(initialScope) }
    var amountText by remember { mutableStateOf(initialAmount) }
    var menuOpen by remember { mutableStateOf(false) }
    val scopeNames = remember { listOf("total") + Categories.names("expense") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Box {
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text(if (scope == "total") "总计（全部支出）" else scope)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        scopeNames.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(if (name == "total") "总计（全部支出）" else name) },
                                onClick = {
                                    scope = name
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("上限（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "填多少元都行，12、12.5、1,234 都认（跟记账页同一套解析）。周期先按月算。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cents = Money.parse(amountText)
                    if (cents != null && cents > 0L) onConfirm(scope, cents)
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("算了") }
        }
    )
}

// ==================== 写回数据层 ====================

/** 同一口径（周期 + 分类）的预算只留一条：有就改上限，没有就新建 */
private fun upsertBudget(scope: String, amount: Long, period: String = "monthly") {
    val existing = Store.budgets().firstOrNull { it.scope == scope && it.period == period }
    if (existing != null) {
        // 有就改上限（整条交回数据层，它自己 save）
        Store.updateBudget(existing.copy(amount = amount))
    } else {
        Store.addBudget(
            Budget(
                period = period,
                scope = scope,
                amount = amount,
                startDate = Dates.today(),
                alertThreshold = 0.8
            )
        )
    }
}

/**
 * 按上三月均摊：取上个月、上上个月、上上上个月三个整月，
 * 每个分类的实际支出加起来除以 3，就是它的月预算参考值。
 * 只填有花钱的分类；金额取整到元，看着不闹心。
 * 已经有「总计」预算的话，顺带把它改成各分类之和。
 * 返回填了几项。
 */
private fun fillFromLastThreeMonths(today: String): Int {
    val ym = Dates.yearMonth(today)
    val all = Store.transactions()
    val months = listOf(1, 2, 3).map { Dates.shiftMonth(ym, -it) }

    // 每个分类三个月的总额
    val totals = LinkedHashMap<String, Long>()
    months.forEach { m ->
        val r = Store.monthRange(m)
        val list = Store.inRange(r.first, r.second, all)
        Store.byCategory(list).forEach { (cat, v) ->
            totals[cat] = (totals[cat] ?: 0L) + v
        }
    }

    var n = 0
    var sum = 0L
    totals.forEach { (cat, total) ->
        val avg = total / 3
        if (avg <= 0L) return@forEach
        // 取整到元，回头看着干净
        val rounded = (avg / 100L) * 100L
        if (rounded <= 0L) return@forEach
        upsertBudget(cat, rounded)
        sum += rounded
        n++
    }

    // 有总计预算就跟着更新，没有就不硬塞一条
    if (n > 0 && Store.budgets().any { it.scope == "total" && it.period == "monthly" }) {
        upsertBudget("total", sum)
    }
    return n
}