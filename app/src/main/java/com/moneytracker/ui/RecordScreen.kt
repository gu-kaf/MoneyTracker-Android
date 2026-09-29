/**
 * 记账页 —— RecordScreen
 *
 * 这个页面干什么：
 *   记一笔账，或者改一笔已经记过的账。支出 / 收入 / 转账三种都在这一屏里完成：
 *   金额、分类、二级分类、账户、日期、商户、备注、垫付，一路点下来不用翻页。
 *
 * 怎么用（签名跟 MainActivity 里接的那两个参数一致，别改）：
 *   RecordScreen(editId = null) { 出去 }        // 新记一笔；保存成功回调 onDone
 *   RecordScreen(editId = 记录.id) { 出去 }      // 编辑；各字段自动预填，保存走 Store.update
 *
 * 几个约定，后面维护的人照着来：
 *   1. 金额只认自绘键盘，绝不弹系统输入法；1-9 / 0 / . / 退格 / 完成 都画在页面里。
 *   2. 数据只走 Store：新增是 Store.add，修改是 Store.update；返回 false 表示这笔重复，
 *      页面只提示、不落库（判重是数据层的事，这里不重复实现）。
 *   3. 页面订阅 Store.version，数据一变（包括别的页面改的账户、分类）自己重画。
 *   4. 颜色从 MaterialTheme.colorScheme 和 theme 里的 LocalExtraColors 取，本文件不写死色值；
 *      间距、圆角都用 4 的倍数。
 *
 * 已知取舍：
 *   - 「垫付」只对支出有意义：勾选框只在支出时出现，切到收入/转账自动取消勾选，
 *     但「替谁花的」这个名字留着（收入也算人情往来）。
 *   - 转账不需要分类，所以选转账时分类区整块收起来，只多出一个「转到哪个账户」。
 *   - 分类没选就落「其他支出 / 其他收入」，跟数据层 Categories.normalize 的习惯一致，
 *     明细页和报表不会因为空分类而错位。
 */
package com.moneytracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneytracker.data.Categories
import com.moneytracker.data.Defaults
import com.moneytracker.data.Store
import com.moneytracker.data.Transaction
import com.moneytracker.ui.theme.LocalExtraColors
import com.moneytracker.util.Dates
import com.moneytracker.util.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordScreen(editId: String? = null, onDone: () -> Unit) {

    // 订阅版本号：账户、分类、规则在别的地方改了，这一页跟着重画
    val version by Store.version.collectAsState()
    val extra = LocalExtraColors.current

    // 编辑模式下先把原来那一条抓出来当草稿。抓一次就够，不要跟着 version 反复重建
    val editing: Transaction? = remember(editId) {
        editId?.let { id -> Store.data.transactions.firstOrNull { it.id == id } }
    }

    val currency = Store.data.settings.currency
    // Store.accountNames() = 活跃账户的名字，归档的不在里头（数据层给的口子，别自己 map）
    val accounts = remember(version) { Store.accountNames() }
    val defaultAccount = remember(version) {
        if (accounts.contains("现金")) "现金" else accounts.firstOrNull().orEmpty()
    }

    // ==================== 页面状态 ====================
    var type by remember { mutableStateOf(editing?.type ?: "expense") }
    var amountText by remember {
        mutableStateOf(editing?.let { if (it.amount > 0) Money.text(it.amount).replace(",", "") else "" }.orEmpty())
    }
    var category by remember { mutableStateOf(editing?.category.orEmpty()) }
    var subcategory by remember { mutableStateOf(editing?.subcategory.orEmpty()) }
    var merchant by remember { mutableStateOf(editing?.merchant.orEmpty()) }
    var note by remember { mutableStateOf(editing?.note.orEmpty()) }
    var date by remember {
        mutableStateOf(editing?.date?.takeIf { it.isNotEmpty() } ?: Dates.today())
    }
    var account by remember {
        mutableStateOf(editing?.account?.takeIf { it.isNotEmpty() } ?: defaultAccount)
    }
    var toAccount by remember { mutableStateOf(editing?.toAccount.orEmpty()) }
    var forWhom by remember { mutableStateOf(editing?.forWhom.orEmpty()) }
    var isAdvance by remember { mutableStateOf(editing?.isAdvance ?: false) }

    // 用户自己动过分类 / 类型之后，规则就不许再抢着改了
    var catTouched by remember { mutableStateOf(editing != null) }
    var typeTouched by remember { mutableStateOf(editing != null) }

    var ruleHint by remember { mutableStateOf("") }
    var keypadOpen by remember { mutableStateOf(true) }
    var showDatePicker by remember { mutableStateOf(false) }
    var tip by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    // 垫付只在支出里出现，类型一换就自动取消
    LaunchedEffect(type) {
        if (type != "expense") isAdvance = false
    }

    val catNames = remember(version, type) { Categories.names(type) }
    val commonSubs = remember(version, type, category) { commonSubsOf(category, type) }

    // 账户归档之后旧记录还挂在它上面（Store.deleteAccount 默认只归档，账留在原地），
    // 编辑这种记录时 Store.accounts() 里就没有它了，一个按钮都不会亮。
    // 这里把当前值补成一个按钮，只是为了让人看见自己挂在哪儿，不改它的值。
    val accountChips = remember(version, account, accounts) {
        if (account.isNotEmpty() && !accounts.contains(account)) accounts + account else accounts
    }
    val toAccountChips = remember(version, toAccount, account, accounts) {
        val base = accounts.filter { it != account }
        if (toAccount.isNotEmpty() && !base.contains(toAccount)) base + toAccount else base
    }

    // 金额颜色：跟着设置里的「支出红、收入绿」走，颜色本身由主题给
    val amountTint = when (type) {
        "income" -> extra.income
        "expense" -> extra.expense
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // ==================== 小工具 ====================

    /** 轻提示：默认 1.6 秒自己消失；closeAfter 为真时提示完就回调 onDone（保存完要出去） */
    fun showTip(msg: String, closeAfter: Boolean = false) {
        tip = msg
        scope.launch {
            delay(if (closeAfter) 450 else 1600)
            if (closeAfter) onDone() else if (tip == msg) tip = ""
        }
    }

    /** 切支出 / 收入 / 转账 */
    fun switchType(k: String) {
        type = k
        typeTouched = true
        if (k != "expense") isAdvance = false
        if (k == "transfer") {
            category = ""
            subcategory = ""
        } else if (category.isNotEmpty() && category !in Categories.names(k)) {
            category = ""
            subcategory = ""
        }
    }

    /** 敲了商户名就拿规则猜一下分类（猜到了自动选上；用户自己选过就不抢） */
    fun onMerchantChange(v: String) {
        merchant = v
        val r = Defaults.match(v)
        if (r == null) {
            ruleHint = ""
            return
        }
        val wantType =
            if (!typeTouched && (r.setType == "expense" || r.setType == "income")) r.setType else type
        if (wantType != type) {
            type = wantType
            if (wantType != "expense") isAdvance = false
        }
        if (!catTouched && r.setCategory.isNotEmpty() && r.setCategory in Categories.names(wantType)) {
            category = r.setCategory
            subcategory = if (r.setSubcategory.isNotEmpty() &&
                r.setSubcategory in Categories.subsOf(r.setCategory, wantType)
            ) r.setSubcategory else ""
            ruleHint = "规则把这一类归到「$category」，点分类可以自己改"
        } else {
            ruleHint = if (r.setCategory.isNotEmpty()) "看着像「${r.setCategory}」，点一下归到那儿" else ""
        }
    }

    /** 按一下键盘 */
    fun pressKey(k: String) {
        when (k) {
            "⌫" -> if (amountText.isNotEmpty()) amountText = amountText.dropLast(1)
            "." -> if (!amountText.contains(".") && amountText.length < 9) {
                amountText = if (amountText.isEmpty()) "0." else amountText + "."
            }
            else -> {
                val dot = amountText.indexOf('.')
                if (dot >= 0 && amountText.length - dot > 2) return // 两位小数封顶
                if (amountText.length >= 10) return                 // 别让数字顶破屏幕
                amountText = if (amountText == "0") k else amountText + k
            }
        }
    }

    /** 存。keepGoing = true 就是「保存并再来一笔」 */
    fun doSave(keepGoing: Boolean) {
        val cents = Money.parse(amountText)
        if (cents == null || cents <= 0) {
            showTip("金额还没填对")
            return
        }
        if (type == "transfer") {
            if (toAccount.isEmpty()) {
                showTip("还没选转到哪个账户")
                return
            }
            if (toAccount == account) {
                showTip("转出和转入不能是同一个账户")
                return
            }
        }

        val finalCat = when {
            type == "transfer" -> ""
            category.isNotEmpty() -> category
            type == "income" -> "其他收入"
            else -> "其他支出"
        }

        val t = editing?.copy() ?: Transaction()
        t.type = type
        t.amount = cents
        t.date = date
        if (t.time.isEmpty()) t.time = Dates.nowTime()
        t.category = finalCat
        t.subcategory = if (type == "transfer") "" else subcategory
        t.account = account
        t.toAccount = if (type == "transfer") toAccount else ""
        t.merchant = merchant.trim()
        t.note = note.trim()
        t.forWhom = forWhom.trim()
        t.isAdvance = type == "expense" && isAdvance
        t.isReimbursed = if (t.isAdvance) (editing?.isReimbursed ?: false) else false
        if (t.ledgerId.isEmpty()) t.ledgerId = Store.currentLedger().id
        if (t.source.isEmpty()) t.source = "manual"

        val ok = if (editing == null) Store.add(t) else Store.update(t)
        if (!ok) {
            showTip("这笔看起来已经记过了，没重复记")
            return
        }

        if (keepGoing) {
            // 清掉这一笔的内容；类型 / 日期 / 账户留着，连着记几笔最省事
            amountText = ""
            category = ""
            subcategory = ""
            merchant = ""
            note = ""
            forWhom = ""
            isAdvance = false
            catTouched = false
            typeTouched = false
            ruleHint = ""
            showTip("记好了，再来一笔")
        } else {
            showTip(if (editing == null) "记好了" else "改好了", closeAfter = true)
        }
    }

    // ==================== 界面 ====================
    Column(Modifier.fillMaxSize()) {

        // 一行小标题：外层 TopAppBar 已经顶着账本名了，这里只写清现在在干什么
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (editing == null) "记一笔" else "改一笔",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (editing != null) {
                Text(
                    text = "编辑中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 金额大字：点一下就是把键盘叫回来
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable { keypadOpen = true }
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = currency,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (amountText.isEmpty()) "0.00" else amountText,
                        fontSize = 40.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = amountTint
                    )
                }
                val cents = Money.parse(amountText)
                Text(
                    text = when {
                        cents != null -> "合计 ${Money.yuan(cents, currency)}"
                        !keypadOpen -> "点一下上面的金额，把键盘叫回来"
                        else -> "下面键盘敲金额，不弹系统输入法"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 支出 / 收入 / 转账
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("expense" to "支出", "income" to "收入", "transfer" to "转账").forEach { pair ->
                val k = pair.first
                FilterChip(
                    selected = type == k,
                    onClick = { switchType(k) },
                    label = {
                        Text(pair.second, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 中间：可以滚的表单
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {

            // ---------- 分类（转账不需要） ----------
            if (type != "transfer") {
                Text("分类", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    catNames.forEach { n ->
                        FilterChip(
                            selected = n == category,
                            onClick = {
                                catTouched = true
                                category = if (category == n) "" else n
                                subcategory = ""
                            },
                            label = { Text(n) }
                        )
                    }
                }

                // 二级分类：一级没选就不显示
                if (category.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "二级分类（$category · 可以不选）",
                        style = MaterialTheme.typography.titleSmall
                    )
                    val subs = remember(version, category, type) { Categories.subsOf(category, type) }
                    if (commonSubs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "常用",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            commonSubs.forEach { s -> SubChip(s, subcategory) { subcategory = it } }
                        }
                    }
                    if (subs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            subs.forEach { s -> SubChip(s, subcategory) { subcategory = it } }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
            }

            // ---------- 商户名 ----------
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = merchant,
                onValueChange = { onMerchantChange(it) },
                label = { Text("商户名（可以不填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (ruleHint.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = ruleHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ---------- 日期 ----------
            Spacer(Modifier.height(12.dp))
            Text("日期", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.DateRange, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("$date　${weekdayOf(date)}")
            }

            // ---------- 账户 ----------
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (type == "transfer") "转出账户" else "账户",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                accountChips.forEach { a ->
                    FilterChip(
                        selected = a == account,
                        onClick = { account = a },
                        label = { Text(a) }
                    )
                }
            }

            // 转账才有的：转到哪个账户
            if (type == "transfer") {
                Spacer(Modifier.height(12.dp))
                Text("转到哪个账户", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    toAccountChips.forEach { a ->
                        FilterChip(
                            selected = a == toAccount,
                            onClick = { toAccount = if (toAccount == a) "" else a },
                            label = { Text(a) }
                        )
                    }
                }
            }

            // ---------- 备注 ----------
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可以不填）") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            // ---------- 垫付 ----------
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("垫付", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = forWhom,
                        onValueChange = { forWhom = it },
                        label = { Text("替谁花的（可以不填）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (type == "expense") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isAdvance = !isAdvance }
                        ) {
                            Checkbox(checked = isAdvance, onCheckedChange = { isAdvance = it })
                            Text("这笔钱对方该还我", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "「垫付」只对支出有意义，这一笔的抬头照样可以填在这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 轻提示
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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // 自绘数字键盘：按「完成」收起来，点上面的金额再叫回来
        if (keypadOpen) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    listOf("1", "2", "3", "⌫"),
                    listOf("4", "5", "6", "."),
                    listOf("7", "8", "9", "0")
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { k ->
                            KeyButton(
                                text = k,
                                onClick = { pressKey(k) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                KeyButton(
                    text = "完成",
                    onClick = { keypadOpen = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 保存
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { doSave(true) },
                modifier = Modifier.weight(1f)
            ) { Text("保存并再来一笔") }
            Button(
                onClick = { doSave(false) },
                modifier = Modifier.weight(1f)
            ) { Text(if (editing == null) "保存" else "保存修改") }
        }
    }

    // 日期选择
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dateToMillis(date))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { date = millisToDate(it) }
                    showDatePicker = false
                }) { Text("好") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** 二级分类的小按钮：点亮的那个再点一下就是取消 */
@Composable
private fun SubChip(value: String, current: String, onPick: (String) -> Unit) {
    FilterChip(
        selected = value == current,
        onClick = { onPick(if (value == current) "" else value) },
        label = { Text(value) }
    )
}

/** 键盘上的一个键 */
@Composable
private fun KeyButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.height(44.dp)
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 这个分类下「常用」的二级：按用户以前记过的次数排，最多 6 个 */
private fun commonSubsOf(category: String, type: String): List<String> {
    if (category.isEmpty()) return emptyList()
    val pool = Categories.subsOf(category, type)
    if (pool.isEmpty()) return emptyList()
    val hits = HashMap<String, Int>()
    Store.data.transactions.forEach { t ->
        if (t.type == type && t.category == category && t.subcategory.isNotEmpty()) {
            hits[t.subcategory] = (hits[t.subcategory] ?: 0) + 1
        }
    }
    return hits.entries
        .sortedByDescending { it.value }
        .map { it.key }
        .filter { pool.contains(it) }
        .take(6)
}

/** 「2026-09-12」→「周五」 */
private fun weekdayOf(date: String): String {
    val d = Dates.parse(date) ?: return ""
    val cal = Calendar.getInstance()
    cal.time = d
    val week = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    val idx = cal.get(Calendar.DAY_OF_WEEK) - 1
    return if (idx in week.indices) week[idx] else ""
}

/** 日期选择器用的是 UTC 零点毫秒，这里跟着它换算，免得多算或少算一天 */
private fun millisToDate(ms: Long): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
    cal.timeInMillis = ms
    return String.format(
        Locale.US, "%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
    )
}

private fun dateToMillis(date: String): Long? {
    val p = date.split("-")
    if (p.size != 3) return null
    val y = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    val d = p[2].toIntOrNull() ?: return null
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
    cal.clear()
    cal.set(y, m - 1, d)
    return cal.timeInMillis
}