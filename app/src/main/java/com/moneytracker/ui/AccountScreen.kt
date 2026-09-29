// AccountScreen.kt —— 账户页：抬头三个数（我的资产 / 他人账户 / 垫付未还）、
// 账户按分组显示（每组一个小标题和小计）、负数余额标红、
// 「替谁花了多少 · 谁欠我多少」卡片（按人统计垫付，可整批标已还 / 撤销），
// 以及账户的新增、修改、归档、彻底删除。
// 账户的增删改一律走数据层的 Store.addAccount / updateAccount / deleteAccount，
// 改名时的历史记录同步由 Store.updateAccount 负责，页面这边不自己改 transactions。
// 界面用 Compose + Material3，中文，颜色一律取 MaterialTheme.colorScheme。
package com.moneytracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneytracker.data.Account
import com.moneytracker.data.Store
import com.moneytracker.ui.theme.LocalExtraColors
import com.moneytracker.util.Money
import kotlinx.coroutines.launch

/**
 * 账户页。抬头三个数：我的资产 / 他人账户 / 垫付未还。
 *
 * 账户按「分组」分组显示，每组一个小标题和该组小计；
 * 余额是负数（信用卡这类）就显示成负数并且变色。
 *
 * 下面那张「替谁花了多少 · 谁欠我多少」只算当前账本的记录：
 * forWhom 非空的人按人分组，isAdvance 且没 isReimbursed 的才算欠，
 * 点右边的按钮能把某个人的垫付整批标成已还，或者撤销。
 *
 * 余额口径跟 Store.netAssets() 一致，是跨账本合计的——账户本来就是几本账共用的。
 * 账户的增删改全部调数据层：Store.addAccount / updateAccount / deleteAccount，
 * 改名要同步历史记录这件事由 Store.updateAccount 负责，页面不自己动 transactions。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(onBack: () -> Unit) {
    val tick = Store.version.collectAsState().value
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val extra = LocalExtraColors.current

    val accounts = remember(tick) { Store.data.accounts.toList() }
    val txns = remember(tick) { Store.transactions() }
    val currency = remember(tick) { Store.data.settings.currency }

    fun say(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    var editing by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf(false) }
    var repayAsk by remember { mutableStateOf<PersonStat?>(null) }
    var hardDel by remember { mutableStateOf<Account?>(null) }

    // 抬头三个数
    val netAssets = Store.netAssets()
    val othersTotal = accounts.filter { it.isOthers && !it.archived }
        .sumOf { Store.accountBalance(it.name) }
    val owedTotal = txns.filter { it.forWhom.isNotBlank() && it.isAdvance && !it.isReimbursed }
        .sumOf { it.amount }

    // 按人统计垫付。只认当前账本、forWhom 非空的记录。
    val persons = remember(tick) {
        txns.filter { it.forWhom.isNotBlank() }
            .groupBy { it.forWhom }
            .map { (name, list) ->
                val spends = list.filter { it.type == "expense" }
                val advances = list.filter { it.isAdvance }
                val unpaid = advances.filter { !it.isReimbursed }
                val repaid = advances.filter { it.isReimbursed }
                PersonStat(
                    name = name,
                    spent = spends.sumOf { it.amount },
                    spentCount = spends.size,
                    owed = unpaid.sumOf { it.amount },
                    unpaidCount = unpaid.size,
                    repaidSum = repaid.sumOf { it.amount },
                    repaidCount = repaid.size,
                    advanceCount = advances.size
                )
            }
            .sortedWith(compareByDescending<PersonStat> { it.owed }.thenByDescending { it.spent })
    }
    val owedPersons = persons.count { it.owed > 0 }

    val visible = accounts.filter { !it.archived }
    val archived = accounts.filter { it.archived }
    // 分组顺序按账户出现顺序来，不排序，用户怎么建的就怎么显示
    val groups = mutableListOf<String>()
    visible.forEach {
        val g = it.group.ifBlank { "自己" }
        if (!groups.contains(g)) groups.add(g)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账户") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = "新增账户")
            }
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ==================== 抬头三个数 ====================
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeadNumber("我的资产", Money.yuan(netAssets, currency), Modifier.weight(1f), true)
                    HeadNumber("他人账户", Money.yuan(othersTotal, currency), Modifier.weight(1f), false)
                    HeadNumber("垫付未还", Money.yuan(owedTotal, currency), Modifier.weight(1f), false)
                }
            }

            // ==================== 账户（按分组） ====================
            groups.forEach { g ->
                val list = visible.filter { it.group.ifBlank { "自己" } == g }
                val subtotal = list.sumOf { Store.accountBalance(it.name) }
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            g,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${list.size} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "小计 ${Money.yuan(subtotal, currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            list.forEachIndexed { i, a ->
                                val bal = Store.accountBalance(a.name)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { editing = a }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(a.name, style = MaterialTheme.typography.bodyLarge)
                                            if (a.isOthers) {
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    "他人",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Text(
                                            buildString {
                                                append(kindLabel(a.kind))
                                                if (a.owner.isNotBlank()) {
                                                    append(" · 归属 ")
                                                    append(a.owner)
                                                }
                                                if (!a.includeInAssets) append(" · 不计入净资产")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        Money.yuan(bal, currency),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = if (bal < 0) extra.expense else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (i < list.size - 1) AccountDivider()
                            }
                        }
                    }
                }
            }

            if (visible.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "还没有账户，点右下角的加号建一个。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // ==================== 替谁花了多少 · 谁欠我多少 ====================
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "替谁花了多少 · 谁欠我多少",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "还欠合计 ${Money.yuan(owedTotal, currency)}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (owedTotal > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "一共替别人花了 ${Money.yuan(persons.sumOf { it.spent }, currency)}，" +
                            "涉及 ${persons.size} 个人（其中 $owedPersons 个人还欠着）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    AccountDivider()

                    if (persons.isEmpty()) {
                        Text(
                            "当前这本账里还没有「替别人花」的记录。\n记账时填上「替谁」，这里就会自动算。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    persons.forEach { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "一共 ${Money.yuan(p.spent, currency)} · ${p.spentCount} 笔",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    personState(p, currency),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        p.owed > 0 -> extra.expense
                                        p.advanceCount > 0 -> extra.income
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    if (p.owed > 0) "还欠 ${Money.yuan(p.owed, currency)}" else "没欠了",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (p.owed > 0) extra.expense
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (p.owed > 0) {
                                    TextButton(onClick = { repayAsk = p }) { Text("已还清") }
                                } else if (p.advanceCount > 0) {
                                    TextButton(onClick = {
                                        Store.reimburseAll(p.name, false)
                                        say("已把 ${p.name} 的垫付撤销成未还")
                                    }) { Text("撤销已还") }
                                }
                            }
                        }
                        AccountDivider()
                    }
                }
            }

            // ==================== 已归档 ====================
            if (archived.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "已归档（${archived.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            archived.forEachIndexed { i, a ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(a.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${kindLabel(a.kind)} · 已归档",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = {
                                        Store.updateAccount(a.copy(archived = false))
                                        say("已恢复「${a.name}」")
                                    }) { Text("恢复") }
                                    // 只有名下没有任何记录时才放出真删入口。
                                    // 有记录的话 Store.deleteAccount(reallyDelete = true) 会拒绝，
                                    // 那些记录的钱会在余额里凭空消失，所以这里干脆不给入口。
                                    val used = Store.accountUsage(a.name)
                                    if (used == 0) {
                                        TextButton(onClick = { hardDel = a }) {
                                            Text("彻底删除", color = MaterialTheme.colorScheme.error)
                                        }
                                    } else {
                                        Text(
                                            "$used 笔在用",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        )
                                    }
                                }
                                if (i < archived.size - 1) AccountDivider()
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(72.dp))
        }
    }

    // ==================== 弹窗 ====================

    if (adding) {
        AccountDialog(
            initial = Account(name = "", kind = "cash", group = "自己"),
            isNew = true,
            groups = groups,
            onDismiss = { adding = false },
            onSave = { a ->
                if (a.name.isBlank()) {
                    say("账户得有个名字")
                } else if (Store.data.accounts.any { it.name == a.name }) {
                    say("已经有叫「${a.name}」的账户了，换个名字")
                } else {
                    Store.addAccount(a)
                    adding = false
                    say("已新增「${a.name}」")
                }
            }
        )
    }

    editing?.let { a ->
        AccountDialog(
            initial = a,
            isNew = false,
            groups = groups,
            onDismiss = { editing = null },
            onSave = { fixed ->
                val old = a.name
                if (fixed.name.isBlank()) {
                    say("账户得有个名字")
                } else if (fixed.name != old && Store.data.accounts.any { it.name == fixed.name }) {
                    say("已经有叫「${fixed.name}」的账户了，换个名字")
                } else {
                    // 改名要同步历史记录，这件事交给 Store.updateAccount：
                    // 它会把 transactions 里的 account / toAccount 一起改掉并重算指纹。
                    // 这里只先数一下有多少笔会受影响，好给用户一句提示。
                    val n = if (fixed.name != old)
                        Store.data.transactions.count { it.account == old || it.toAccount == old }
                    else 0
                    Store.updateAccount(fixed, old)
                    say(
                        when {
                            n > 0 -> "已改名，顺手改了 $n 笔记录"
                            fixed.name != old -> "已改名"
                            else -> "已保存"
                        }
                    )
                    editing = null
                }
            },
            onArchive = {
                Store.deleteAccount(a.id)
                editing = null
                say("已归档「${a.name}」，不再计入「我的资产」，随时能恢复")
            }
        )
    }

    repayAsk?.let { p ->
        AlertDialog(
            onDismissRequest = { repayAsk = null },
            title = { Text("把 ${p.name} 的垫付标成已还") },
            text = {
                Text(
                    "这本账里 ${p.name} 名下有 ${p.unpaidCount} 笔垫付没还，" +
                        "合计 ${Money.yuan(p.owed, currency)}。\n" +
                        "点确定就把这几笔一起标成已还，随时可以用「撤销已还」改回来。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repayAsk = null
                    Store.reimburseAll(p.name, true)
                    say("已把 ${p.name} 的 ${p.unpaidCount} 笔垫付标成已还")
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { repayAsk = null }) { Text("取消") } }
        )
    }

    // 彻底删除。Store.deleteAccount(reallyDelete = true) 只在账户名下一笔记录都没有时才放行，
    // 有记录就返回 false —— 因为那些记录会挂在一个不存在的账户上、钱在余额里凭空少掉。
    // 所以上面那个入口只在「N 笔在用」为 0 时才显示，这里再兜一次底。
    hardDel?.let { a ->
        AlertDialog(
            onDismissRequest = { hardDel = null },
            title = { Text("彻底删除账户「${a.name}」") },
            text = {
                Text(
                    "这个账户名下现在没有任何记录，删掉就没了，删完不能撤销。\n\n" +
                        "删之前建议先点一次「立即备份」，删完还能回档。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = a.name
                    hardDel = null
                    if (Store.deleteAccount(a.id, reallyDelete = true)) {
                        say("已彻底删除「$name」")
                    } else {
                        say("删不掉：「$name」名下还有 ${Store.accountUsage(name)} 笔记录，先挪走记录，或者只归档")
                    }
                }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { hardDel = null }) { Text("取消") } }
        )
    }
}

// ==================== 小组件 ====================

/** 抬头里的一个数字 */
@Composable
private fun HeadNumber(title: String, value: String, modifier: Modifier, big: Boolean) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun AccountDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** 新增 / 修改账户 */
@Composable
private fun AccountDialog(
    initial: Account,
    isNew: Boolean,
    groups: List<String>,
    onSave: (Account) -> Unit,
    onDismiss: () -> Unit,
    onArchive: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind.ifBlank { "cash" }) }
    var group by remember { mutableStateOf(initial.group.ifBlank { "自己" }) }
    var owner by remember { mutableStateOf(initial.owner) }
    var balance by remember { mutableStateOf(Money.text(initial.initialBalance)) }
    var inAssets by remember { mutableStateOf(initial.includeInAssets) }

    val parsed = Money.parse(balance)
    val groupChoices = (listOf("自己") + groups + listOf("家人", "朋友", "公司")).distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增账户" else "修改账户") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("账户名") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                Text("类型", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(kinds.size) { i ->
                        val k = kinds[i]
                        PickChip(k.second, kind == k.first) { kind = k.first }
                    }
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    singleLine = true,
                    label = { Text("分组") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groupChoices.size) { i ->
                        val g = groupChoices[i]
                        PickChip(g, group == g) { group = g }
                    }
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it },
                    singleLine = true,
                    label = { Text("归属人（填了就算别人名下的账户）") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    singleLine = true,
                    isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("初始余额") },
                    supportingText = {
                        Text(
                            if (parsed == null) "填个数字，比如 1200 或 -350.5"
                            else "欠钱就填负数，比如信用卡填 -350.5"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("计入净资产", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = inAssets, onCheckedChange = { inAssets = it })
                }
                if (!isNew && onArchive != null && !initial.archived) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "归档 = 不在记账页显示（跟电脑版一样）。余额和历史记录都留着，" +
                            "但归档之后这个账户不再计入上面的「我的资产」，随时能恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = {
                    val cents = parsed ?: 0L
                    onSave(
                        Account(
                            id = initial.id,
                            name = name.trim(),
                            kind = kind,
                            group = group.trim().ifBlank { "自己" },
                            owner = owner.trim(),
                            isOthers = owner.trim().isNotEmpty(),
                            initialBalance = cents,
                            includeInAssets = inAssets,
                            archived = initial.archived
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew && onArchive != null && !initial.archived) {
                    TextButton(onClick = onArchive) { Text("归档") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/** 自己画的小选择块，省得依赖还在实验里的 FilterChip */
@Composable
private fun PickChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

// ==================== 小工具 ====================

/** 一个人的垫付账。金额单位都是「分」。 */
private data class PersonStat(
    val name: String,
    val spent: Long,
    val spentCount: Int,
    val owed: Long,
    val unpaidCount: Int,
    val repaidSum: Long,
    val repaidCount: Int,
    val advanceCount: Int
)

/** 账户类型：存的 id 要跟电脑版一致 */
private val kinds = listOf(
    "cash" to "现金",
    "debit" to "储蓄卡",
    "credit" to "信用卡",
    "wallet" to "钱包",
    "investment" to "投资"
)

private fun kindLabel(kind: String): String =
    kinds.firstOrNull { it.first == kind }?.second ?: "现金"

/** 状态那行：「2 笔还没还」「已还清 2 笔（¥244.00）」「没标成垫付」 */
private fun personState(p: PersonStat, currency: String): String = when {
    p.unpaidCount > 0 -> "${p.unpaidCount} 笔还没还"
    p.advanceCount > 0 -> "已还清 ${p.repaidCount} 笔（${Money.yuan(p.repaidSum, currency)}）"
    else -> "没标成垫付"
}

/** 账户落库一律走数据层：Store.addAccount / Store.updateAccount / Store.deleteAccount */