package com.moneytracker.ui

/**
 * 分析页。
 *
 * 干嘛用的：不让你自己算——把流水读一遍，把能看出来的事一条条摆出来。
 * 每条一张卡，卡上是结论（一句话），下面一行小字写清楚这个数是怎么算出来的。
 * 有数据的才显示，没数据的整条不出现，不硬凑。
 *
 * 一共 10 条发现：
 *   1. 比上个月多花 / 少花了多少，带百分比
 *   2. 储蓄率（结余 ÷ 收入），并对照 20% ~ 30% 的健康区间
 *   3. 钱主要花在哪个分类，占多少
 *   4. 最大的一笔是哪笔（日期、商户、金额）
 *   5. 常去的商户前 3 家
 *   6. 工作日和周末的平均每天花销对比
 *   7. 哪个分类这个月比上月涨得最猛
 *   8. 疑似订阅：同商户、金额相同、近三个月每月都出现
 *   9. 照这个速度月底会花多少（已花日均 × 当月天数）
 *  10. 想省钱最该动哪一块
 *
 * 只读数据层：整个文件没有一处写回 Store，只是 Store.transactions() /
 * byCategory / summary / inRange 这几个现成的口子拿来用。
 * 每条卡下面的小字是「算法说明」，不是客套话——数字对不上时能照着验算。
 *
 * 颜色：卡片语气色走主题里的 LocalExtraColors（extra.income / extra.expense），
 * 其余从 MaterialTheme.colorScheme 取；本文件不写死任何色值。
 * 签名按冻结的壳来：不带参数。
 */

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneytracker.data.Store
import com.moneytracker.data.Transaction
import com.moneytracker.ui.theme.LocalExtraColors
import com.moneytracker.util.Dates
import com.moneytracker.util.Money
import java.util.Calendar
import java.util.Locale

/** 语气：好 / 要留意 / 中性，只用来上色，不影响文字 */
private const val TONE_GOOD = 1
private const val TONE_WARN = 2

private data class Insight(
    val title: String,
    val detail: String,
    val tag: String? = null,
    val tone: Int = 0
)

private fun pctText(value: Double): String = String.format(Locale.US, "%.1f%%", value)

private fun sharePercent(part: Long, total: Long): String =
    if (total <= 0L) "0.0%" else pctText(part * 100.0 / total)

/** 0 = 周日，6 = 周六；日期解析不了返回 -1 */
private fun weekdayIndex(date: String): Int {
    val d = Dates.parse(date) ?: return -1
    val c = Calendar.getInstance()
    c.time = d
    return c.get(Calendar.DAY_OF_WEEK) - 1
}

/** 月份写成「2026年9月」，跟报表页一个写法 */
private fun monthTitle(ym: String): String {
    val p = ym.split("-")
    if (p.size != 2) return ym
    val y = p[0].toIntOrNull() ?: return ym
    val m = p[1].toIntOrNull() ?: return ym
    return "${y}年${m}月"
}

/** 一笔记录说给用户听时怎么叫它：先看商户，没有就看备注，都没有就看分类 */
private fun whatOf(t: Transaction): String = when {
    t.merchant.isNotBlank() -> t.merchant.trim()
    t.note.isNotBlank() -> t.note.trim()
    t.category.isNotBlank() -> t.category
    else -> "一笔支出"
}

/**
 * 把 10 条发现算出来。没有数据的返回 null，最后统一过滤掉。
 */
private fun buildInsights(today: String, currency: String): List<Insight> {
    val ym = Dates.yearMonth(today)
    val lastYm = Dates.shiftMonth(ym, -1)
    val all = Store.transactions()

    val curRange = Store.monthRange(ym)
    val prevRange = Store.monthRange(lastYm)
    val curTx = Store.inRange(curRange.first, curRange.second, all)
    val prevTx = Store.inRange(prevRange.first, prevRange.second, all)
    val cur = Store.summary(curTx)
    val prev = Store.summary(prevTx)
    val curExpenseTx = curTx.filter { it.type == "expense" }
    val curCats = Store.byCategory(curTx)
    val out = mutableListOf<Insight>()

    // 1. 跟上个月比
    if (prev.expense > 0L && cur.expense > 0L) {
        val delta = cur.expense - prev.expense
        val pct = delta * 100.0 / prev.expense
        out += if (delta > 0L) {
            Insight(
                title = "比上个月多花了 ${Money.yuan(delta, currency)}，多了 ${pctText(pct)}",
                detail = "本月支出 ${Money.yuan(cur.expense, currency)}，上月 ${Money.yuan(prev.expense, currency)}。" +
                    "算法：(本月 − 上月) ÷ 上月 × 100% = ${pctText(pct)}。",
                tag = "花得更多",
                tone = TONE_WARN
            )
        } else if (delta < 0L) {
            Insight(
                title = "比上个月少花了 ${Money.yuan(-delta, currency)}，少了 ${pctText(-pct)}",
                detail = "本月支出 ${Money.yuan(cur.expense, currency)}，上月 ${Money.yuan(prev.expense, currency)}。" +
                    "算法：(本月 − 上月) ÷ 上月 × 100% = ${pctText(pct)}。",
                tag = "省下来了",
                tone = TONE_GOOD
            )
        } else {
            Insight(
                title = "跟上个月花得一样多",
                detail = "两个月都是 ${Money.yuan(cur.expense, currency)}，一分不差。"
            )
        }
    }

    // 2. 储蓄率
    if (cur.income > 0L) {
        val rate = cur.net * 100.0 / cur.income
        val good = rate >= 20.0
        out += Insight(
            title = "储蓄率 ${pctText(rate)}" + if (good) "，在健康区间里" else "，低于健康的 20% ~ 30%",
            detail = "结余 ${Money.yuan(cur.net, currency)} ÷ 收入 ${Money.yuan(cur.income, currency)} = ${pctText(rate)}。" +
                "一般认为 20% ~ 30% 算稳，低于 20% 花得偏多，高于 30% 属于攒得住的。",
            tag = if (good) "健康" else "偏低",
            tone = if (good) TONE_GOOD else TONE_WARN
        )
    }

    // 3. 钱主要花在哪
    curCats.firstOrNull()?.let { top ->
        out += Insight(
            title = "钱主要花在「${top.first}」，占 ${sharePercent(top.second, cur.expense)}",
            detail = "本月一共花了 ${Money.yuan(cur.expense, currency)}，其中「${top.first}」${Money.yuan(top.second, currency)}。" +
                "算法：该分类支出 ÷ 本月总支出。排在它后面的是" +
                curCats.drop(1).take(2).joinToString("、") { "「${it.first}」${Money.yuan(it.second, currency)}" }
                    .let { if (it.isEmpty()) "没有别的了。" else "$it。" }
        )
    }

    // 4. 最大的一笔
    curExpenseTx.maxByOrNull { it.amount }?.let { big ->
        out += Insight(
            title = "最大的一笔是 ${Money.yuan(big.amount, currency)}：" + whatOf(big),
            detail = "${big.date}${if (big.time.isNotBlank()) " ${big.time}" else ""} · ${whatOf(big)}" +
                (if (big.category.isNotBlank()) " · ${big.category}" else "") +
                "。" + "算法：本月所有支出里金额最大的那一笔。" +
                "它一个人就占了本月支出的 ${sharePercent(big.amount, cur.expense)}。"
        )
    }

    // 5. 常去的商户前 3
    val merchants = curExpenseTx
        .filter { it.merchant.isNotBlank() }
        .groupBy { it.merchant.trim() }
        .map { (m, list) -> Triple(m, list.size, list.sumOf { t -> t.amount }) }
        .sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenByDescending { it.third })
        .take(3)
    if (merchants.isNotEmpty()) {
        out += Insight(
            title = "常去的商户前三：" + merchants.joinToString("、") { "${it.first}（${it.second} 次）" },
            detail = merchants.joinToString("；") {
                "${it.first} 去了 ${it.second} 次，共 ${Money.yuan(it.third, currency)}"
            } + "。算法：本月支出按商户名分组，先比次数，次数一样再比金额。"
        )
    }

    // 6. 工作日 vs 周末
    val perDay = curExpenseTx.groupBy { it.date }.mapValues { e -> e.value.sumOf { t -> t.amount } }
    val workDays = perDay.filterKeys { val w = weekdayIndex(it); w in 1..5 }
    val weekendDays = perDay.filterKeys { val w = weekdayIndex(it); w == 0 || w == 6 }
    if (workDays.isNotEmpty() && weekendDays.isNotEmpty()) {
        val workAvg = workDays.values.sum() / workDays.size
        val weekendAvg = weekendDays.values.sum() / weekendDays.size
        val diffPct = if (workAvg > 0L) (weekendAvg - workAvg) * 100.0 / workAvg else 0.0
        out += Insight(
            title = if (weekendAvg >= workAvg) {
                "周末平均每天比工作日多花 ${Money.yuan(weekendAvg - workAvg, currency)}"
            } else {
                "周末平均每天比工作日少花 ${Money.yuan(workAvg - weekendAvg, currency)}"
            },
            detail = "工作日 ${workDays.size} 天共 ${Money.yuan(workDays.values.sum(), currency)}，" +
                "平均 ${Money.yuan(workAvg, currency)}/天；周末 ${weekendDays.size} 天共 " +
                "${Money.yuan(weekendDays.values.sum(), currency)}，平均 ${Money.yuan(weekendAvg, currency)}/天" +
                "（相差 ${pctText(diffPct)}）。算法：按有支出的日期分组求和，再各自除以天数。"
        )
    }

    // 7. 涨得最猛的分类
    val curMap = curCats.toMap()
    val prevMap = Store.byCategory(prevTx).toMap()
    val growth = (curMap.keys + prevMap.keys).mapNotNull { k ->
        val c = curMap[k] ?: 0L
        val p = prevMap[k] ?: 0L
        if (p > 0L && c > p) Triple(k, c - p, c) else null
    }.maxByOrNull { it.second }
    if (growth != null) {
        val p = prevMap[growth.first] ?: 0L
        out += Insight(
            title = "「${growth.first}」这个月涨得最猛，比上月多 ${Money.yuan(growth.second, currency)}",
            detail = "本月「${growth.first}」${Money.yuan(growth.third, currency)}，上月 ${Money.yuan(p, currency)}，" +
                "涨了 ${pctText(growth.second * 100.0 / p)}。算法：两个月的分类支出逐个相减，取涨得最多的那个。",
            tag = "涨得猛",
            tone = TONE_WARN
        )
    }

    // 8. 疑似订阅
    val last3 = listOf(0, -1, -2).map { Dates.shiftMonth(ym, it) }
    val subs = all
        .filter { it.type == "expense" && it.merchant.isNotBlank() && last3.contains(Dates.yearMonth(it.date)) }
        .groupBy { it.merchant.trim() to it.amount }
        .filter { (_, list) -> last3.all { m -> list.any { Dates.yearMonth(it.date) == m } } }
        .map { (k, list) -> Triple(k.first, k.second, list.size) }
        .sortedByDescending { it.second }
        .take(3)
    if (subs.isNotEmpty()) {
        val monthly = subs.sumOf { it.second }
        out += Insight(
            title = "疑似订阅 ${subs.size} 项，每月固定出去 ${Money.yuan(monthly, currency)}",
            detail = subs.joinToString("；") {
                "${it.first} ${Money.yuan(it.second, currency)}（近三个月出现 ${it.third} 次）"
            } + "。判定算法：同一个商户、金额完全一样，而且最近三个月每个月都出现过。" +
                "不用的记得去退订，这类钱最容易忘了。",
            tag = "固定支出",
            tone = TONE_WARN
        )
    }

    // 9. 月底会花多少
    val dayOfMonth = today.substring(8).take(2).toIntOrNull() ?: 0
    val daysInMonth = Dates.daysInMonth(ym)
    if (cur.expense > 0L && dayOfMonth > 0) {
        val projected = cur.expense / dayOfMonth * daysInMonth
        val vsPrev = if (prev.expense > 0L) {
            val d = projected - prev.expense
            if (d >= 0L) "比上月实际花的还多 ${Money.yuan(d, currency)}"
            else "比上月实际花的少 ${Money.yuan(-d, currency)}"
        } else "上月没有可比的数据"
        out += Insight(
            title = "照这个速度，${monthTitle(ym)}会花到 ${Money.yuan(projected, currency)}",
            detail = "已花 ${Money.yuan(cur.expense, currency)} ÷ 已经过完 $dayOfMonth 天 × 当月 $daysInMonth 天 = " +
                "${Money.yuan(projected, currency)}，$vsPrev。算法就是日均乘以当月天数，越到月底越准。"
        )
    }

    // 10. 最该动哪一块
    if (curCats.isNotEmpty()) {
        // 这些分类通常压得动；一个都没有就退回花得最多的那个
        val compressible = listOf("娱乐", "购物", "餐饮", "通讯", "人情", "其他支出", "教育", "宠物")
        val target = curCats.firstOrNull { compressible.contains(it.first) } ?: curCats.first()
        out += Insight(
            title = "想省钱，先动「${target.first}」：${Money.yuan(target.second, currency)}，" +
                "占 ${sharePercent(target.second, cur.expense)}",
            detail = "它属于一般能压缩的那一类，而且在本月这些分类里花得最多。" +
                "砍掉两成就是省 ${Money.yuan(target.second / 5, currency)}。" +
                "算法：先看娱乐、购物、餐饮、通讯、人情这类可压缩分类，取花得最多的一个；" +
                "一个都不沾就退回本月最大分类。"
        )
    }

    return out
}

@Composable
fun AnalysisScreen() {
    val version = Store.version.collectAsState().value
    val currency = Store.data.settings.currency
    val today = Dates.today()

    val insights = remember(version, today) { buildInsights(today, currency) }
    val txCount = remember(version) { Store.transactions().size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = monthTitle(Dates.yearMonth(today)) + "的花法",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "翻了一遍当前账本的 $txCount 笔流水，看出 ${insights.size} 条。" +
                "有数据的才说，没数据的不硬凑。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (insights.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("还没什么可分析的", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "先记上几笔，这里就会自己长出结论来。至少要有一笔本月的支出，才能看出花法。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            insights.forEachIndexed { i, ins ->
                InsightCard(index = i + 1, insight = ins)
            }
        }

        Text(
            text = "这些都是按当前账本的流水现算的，换账本、记账之后结论会跟着变。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun InsightCard(index: Int, insight: Insight) {
    val extra = LocalExtraColors.current
    // 好话走收入色（绿），要留意的走支出色（红），都跟着主题和「支出红收入绿」开关走
    val toneColor = when (insight.tone) {
        TONE_GOOD -> extra.income
        TONE_WARN -> extra.expense
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$index",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp)
                )
                Text(
                    text = insight.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (insight.tag != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(toneColor.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = insight.tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = toneColor
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = insight.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}