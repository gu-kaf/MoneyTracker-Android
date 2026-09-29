package com.moneytracker.ui

/**
 * 报表页。
 *
 * 干嘛用的：按月看收支全貌。从上到下依次是
 *   1. 月份切换器（上一月 / 2026年9月 / 下一月）
 *   2. 总览卡：收入、支出、结余（结余按正负上色）
 *   3. 分类占比：环形图 + 每个分类的色点 / 名称 / 金额 / 百分比
 *   4. 分类排行：横向条形，前 10 名，条长按最大值归一
 *   5. 每月对比：最近 6 个月的收入和支出双柱
 *   6. 各账本盈亏：一本账一行，底下合计
 *
 * 数据只读，全部走数据层现成的口子：
 *   Store.transactions()          当前账本记录
 *   Store.inRange / monthRange    按月筛选
 *   Store.summary / byCategory / byMonth  现成的汇总
 * 一个字节都不写回去，账本和记录的增删改由别的页面负责。
 *
 * 图表全是 Canvas 手绘的，没有引任何图表库，也没有第三方依赖。
 * 颜色：收入绿 / 支出红走主题里的 LocalExtraColors（extra.income / extra.expense，
 * 用户在设置里关掉「支出红收入绿」时会跟着变成主题色）；分类占比那一圈用
 * 主题里那份 chartPalette；其余一切从 MaterialTheme.colorScheme 取。
 * 本文件不写死任何色值，换主题整个页面一起变。
 *
 * 签名按冻结的壳来：不带参数，数据自己在里面从 Store 取。
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneytracker.data.Store
import com.moneytracker.ui.theme.LocalExtraColors
import com.moneytracker.ui.theme.chartPalette
import com.moneytracker.util.Dates
import com.moneytracker.util.Money
import java.util.Locale

/**
 * 分类占比图的取色：用主题里那份固定色板 chartPalette（ui/theme/Theme.kt）。
 * 环形图要的是「相邻分类互相分得开」，色板本身就是为这个准备、且明确
 * 跟主题无关的，所以这里不自己定义颜色，分类多了就从头循环。
 */
private fun categoryColor(index: Int): Color {
    val n = chartPalette.size
    val i = if (index < 0) 0 else index % n
    return chartPalette[i]
}

/** 这一屏要用的全部数据，算好一次交给界面，别每帧重算 */
private data class ReportModel(
    val ym: String,
    val income: Long,
    val expense: Long,
    val count: Int,
    /** 支出分类汇总，已从多到少 */
    val categories: List<Pair<String, Long>>,
    /** 最近 6 个月：yyyy-MM -> (收入, 支出) */
    val monthly: List<Pair<String, Pair<Long, Long>>>,
    /** 各账本：名称、收入、支出 */
    val ledgers: List<Triple<String, Long, Long>>
)

private fun buildReportModel(ym: String): ReportModel {
    val all = Store.transactions()
    val range = Store.monthRange(ym)
    val monthTx = Store.inRange(range.first, range.second, all)
    val sum = Store.summary(monthTx)

    // 先整库按月汇总一次，再从里面挑最近 6 个月，省得每个月都扫一遍流水
    val byMonth = Store.byMonth(all).associate { it.first to (it.second to it.third) }
    val months = (5 downTo 0).map { Dates.shiftMonth(ym, -it) }
    val monthly = months.map { m -> m to (byMonth[m] ?: (0L to 0L)) }

    // 账本之间比的是同一段时间，得拿全部记录来筛，不能用当前账本那一份
    val ledgerRows = Store.data.ledgers.map { l ->
        val mine = Store.data.transactions.filter { Store.ledgerOf(it) == l.id }
        val s = Store.summary(Store.inRange(range.first, range.second, mine))
        Triple(l.name.ifEmpty { "未命名账本" }, s.income, s.expense)
    }

    return ReportModel(
        ym = ym,
        income = sum.income,
        expense = sum.expense,
        count = sum.count,
        categories = Store.byCategory(monthTx),
        monthly = monthly,
        ledgers = ledgerRows
    )
}

/** 「2026-09」写成「2026年9月」 */
private fun monthTitle(ym: String): String {
    val p = ym.split("-")
    if (p.size != 2) return ym
    val y = p[0].toIntOrNull() ?: return ym
    val m = p[1].toIntOrNull() ?: return ym
    return "${y}年${m}月"
}

private fun percentText(value: Long, total: Long): String =
    if (total <= 0L) "0.0%" else String.format(Locale.US, "%.1f%%", value * 100.0 / total)

@Composable
fun ReportScreen() {
    // 订阅版本号：数据一变（记一笔、改预算、换账本）这里就重画
    val version = Store.version.collectAsState().value
    val currency = Store.data.settings.currency
    val extra = LocalExtraColors.current
    var ym by remember { mutableStateOf(Dates.yearMonth(Dates.today()).ifEmpty { "2026-01" }) }

    val model = remember(version, ym) { buildReportModel(ym) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MonthSwitcher(
            ym = ym,
            onPrev = { ym = Dates.shiftMonth(ym, -1) },
            onNext = { ym = Dates.shiftMonth(ym, 1) }
        )

        OverviewCard(model = model, currency = currency)

        SectionCard(
            title = "分类占比",
            subtitle = if (model.categories.isEmpty()) null
            else "这个月一共花了 ${Money.yuan(model.expense, currency)}，按分类摊开看"
        ) {
            if (model.categories.isEmpty()) {
                EmptyHint("这个月还没有支出记录")
            } else {
                CategoryDonut(categories = model.categories, total = model.expense, currency = currency)
            }
        }

        SectionCard(title = "分类排行", subtitle = "前 10 名，条长按最大值归一") {
            if (model.categories.isEmpty()) {
                EmptyHint("这个月还没有支出记录")
            } else {
                CategoryRanking(categories = model.categories.take(10), currency = currency)
            }
        }

        SectionCard(title = "每月对比", subtitle = "最近 6 个月，绿的是收入，红的是支出") {
            MonthBars(
                monthly = model.monthly,
                incomeColor = extra.income,
                expenseColor = extra.expense
            )
        }

        SectionCard(title = "各账本盈亏", subtitle = "同一段时间里每本账各自的收入和支出") {
            LedgerCompare(rows = model.ledgers, currency = currency)
        }

        Text(
            text = "共 ${model.count} 笔记录 · 数据只存在这台手机上",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

// ==================== 月份切换 ====================

@Composable
private fun MonthSwitcher(ym: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一月")
        }
        Text(
            text = monthTitle(ym),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一月")
        }
    }
}

// ==================== 总览 ====================

@Composable
private fun OverviewCard(model: ReportModel, currency: String) {
    val extra = LocalExtraColors.current
    val net = model.income - model.expense
    // 结余为正走收入色（绿），透支走支出色（红），都跟着主题走
    val netColor = if (net < 0) extra.expense else extra.income

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = monthTitle(model.ym) + "总览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OverviewCell(
                    label = "收入",
                    value = Money.yuan(model.income, currency),
                    color = extra.income,
                    modifier = Modifier.weight(1f)
                )
                OverviewCell(
                    label = "支出",
                    value = Money.yuan(model.expense, currency),
                    color = extra.expense,
                    modifier = Modifier.weight(1f)
                )
                OverviewCell(
                    label = "结余",
                    value = Money.signed(net, currency),
                    color = netColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun OverviewCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

// ==================== 分类占比 ====================

@Composable
private fun CategoryDonut(categories: List<Pair<String, Long>>, total: Long, currency: String) {
    val slices = categories.mapIndexed { i, c ->
        categoryColor(i) to (if (total > 0L) c.second.toFloat() / total else 0f)
    }

    Column {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            DonutCanvas(slices = slices, modifier = Modifier.size(180.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "支出",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = Money.text(total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${categories.size} 个分类",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        categories.forEachIndexed { i, c ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(categoryColor(i))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = c.first,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(text = Money.yuan(c.second, currency), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = percentText(c.second, total),
                    modifier = Modifier.width(56.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DonutCanvas(slices: List<Pair<Color, Float>>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.17f
        val d = size.minDimension - stroke
        val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        var start = -90f // 从正上方起笔画，看着顺眼
        slices.forEach { (color, share) ->
            val sweep = share * 360f
            if (sweep <= 0f) return@forEach
            // 太窄的扇区不再抠缝，不然会画没
            val drawSweep = if (sweep > 4f) sweep - 1.5f else sweep
            drawArc(
                color = color,
                startAngle = start,
                sweepAngle = drawSweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(d, d),
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            start += sweep
        }
    }
}

// ==================== 分类排行 ====================

@Composable
private fun CategoryRanking(categories: List<Pair<String, Long>>, currency: String) {
    val maxValue = categories.maxOfOrNull { it.second } ?: 0L
    val track = MaterialTheme.colorScheme.surfaceVariant

    Column {
        categories.forEachIndexed { i, c ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = c.first,
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                ) {
                    val r = size.height / 2f
                    drawRoundRect(color = track, cornerRadius = CornerRadius(r, r))
                    val frac = if (maxValue <= 0L) 0f else c.second.toFloat() / maxValue
                    if (frac > 0f) {
                        drawRoundRect(
                            color = categoryColor(i),
                            size = Size(size.width * frac, size.height),
                            cornerRadius = CornerRadius(r, r)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = Money.yuan(c.second, currency),
                    modifier = Modifier.width(84.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ==================== 每月对比 ====================

@Composable
private fun MonthBars(
    monthly: List<Pair<String, Pair<Long, Long>>>,
    incomeColor: Color,
    expenseColor: Color
) {
    val maxValue = monthly.maxOfOrNull { maxOf(it.second.first, it.second.second) } ?: 0L

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(color = incomeColor, text = "收入")
            Spacer(Modifier.width(16.dp))
            LegendDot(color = expenseColor, text = "支出")
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            monthly.forEach { item ->
                val ym = item.first
                val income = item.second.first
                val expense = item.second.second
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        val cr = 3.dp.toPx()
                        val safeMax = if (maxValue <= 0L) 1f else maxValue.toFloat()
                        val w = size.width * 0.28f
                        val gap = size.width * 0.12f
                        val left = (size.width - (w * 2f + gap)) / 2f
                        val hIn = size.height * (income / safeMax)
                        val hEx = size.height * (expense / safeMax)
                        if (hIn > 0f) {
                            drawRoundRect(
                                color = incomeColor,
                                topLeft = Offset(left, size.height - hIn),
                                size = Size(w, hIn),
                                cornerRadius = CornerRadius(cr, cr)
                            )
                        }
                        if (hEx > 0f) {
                            drawRoundRect(
                                color = expenseColor,
                                topLeft = Offset(left + w + gap, size.height - hEx),
                                size = Size(w, hEx),
                                cornerRadius = CornerRadius(cr, cr)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = Dates.monthLabel(ym),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== 各账本盈亏 ====================

@Composable
private fun LedgerCompare(rows: List<Triple<String, Long, Long>>, currency: String) {
    if (rows.isEmpty()) {
        EmptyHint("还没有账本")
        return
    }
    val extra = LocalExtraColors.current
    val totalIncome = rows.sumOf { it.second }
    val totalExpense = rows.sumOf { it.third }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = "账本",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "收入",
                modifier = Modifier.width(84.dp),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "支出",
                modifier = Modifier.width(84.dp),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()
        rows.forEach { r ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = r.first,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    text = Money.yuan(r.second, currency),
                    modifier = Modifier.width(84.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = Money.yuan(r.third, currency),
                    modifier = Modifier.width(84.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "合计",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = Money.yuan(totalIncome, currency),
                modifier = Modifier.width(84.dp),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = extra.income
            )
            Text(
                text = Money.yuan(totalExpense, currency),
                modifier = Modifier.width(84.dp),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = extra.expense
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "合计是全部账本加在一起，跟上面总览卡不是一回事——总览卡只看当前账本。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== 小零件 ====================

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}