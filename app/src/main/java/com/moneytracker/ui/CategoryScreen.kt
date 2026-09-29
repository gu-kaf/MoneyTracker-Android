// CategoryScreen.kt —— 分类管理页：支出 / 收入两个页签，
// 一级分类可加、改名、删、上下调序，展开后管理它的二级分类（同样可加、改名、删、调序），
// 底部「恢复出厂分类」。改名和删除会同步历史记录并重算判重指纹。
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.unit.dp
import com.moneytracker.data.Categories
import com.moneytracker.data.CategoryGroup
import com.moneytracker.data.Store
import com.moneytracker.data.Transaction
import kotlinx.coroutines.launch

/**
 * 分类管理页。支出 / 收入两个页签。
 *
 * 一级分类能加、改名、删、上下调序；点开一条能看它下面的二级分类，二级同样能加、
 * 改名、删、调序。所有改动写进 settings.expenseCategories / incomeCategories。
 *
 * 改名和删除会同步历史记录，这一点跟电脑版对齐：
 *   · 改一级分类名 → 把所有还写着旧名字的记录一次性改过来，并重算判重指纹；
 *     改完提示改了多少处。二级分类改名同理，只改这个一级分类底下的那些。
 *   · 删一级分类 → 先告诉你有多少笔在用，确认后这些记录转到「其他支出」/「其他收入」，
 *     一笔都不丢；「其他支出」「其他收入」本身是兜底分类，不允许删。
 *   · 删二级分类 → 记录留着，只把它们的二级分类清空。
 *
 * 判重指纹里其实没有分类字段（只算日期/金额/商户/备注/账户/账本），
 * 这里按任务要求照样重算一遍 Store.fingerprint()，值不变，但保持口径统一。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(onBack: () -> Unit) {
    val tick = Store.version.collectAsState().value
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var type by remember { mutableStateOf("expense") }
    var expanded by remember { mutableStateOf<String?>(null) }

    // 弹窗
    var addGroup by remember { mutableStateOf(false) }
    var renameGroup by remember { mutableStateOf<String?>(null) }
    var deleteGroup by remember { mutableStateOf<String?>(null) }
    var addSubTo by remember { mutableStateOf<String?>(null) }
    var renameSubAsk by remember { mutableStateOf<Pair<String, String>?>(null) }
    var resetAsk by remember { mutableStateOf(false) }

    // tick 一变就重新取一次
    val groups = remember(tick) { groupsOf(type).toList() }

    fun say(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { addGroup = true }) {
                        Text("＋ 添加一级分类")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { resetAsk = true }) {
                        Text("恢复出厂分类", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = if (type == "income") 1 else 0) {
                Tab(
                    selected = type == "expense",
                    onClick = { type = "expense"; expanded = null },
                    text = { Text("支出") }
                )
                Tab(
                    selected = type == "income",
                    onClick = { type = "income"; expanded = null },
                    text = { Text("收入") }
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "一共 ${groups.size} 个一级分类。" +
                        "点一条能展开它的二级分类；右边 ⋮ 是改名、调序、删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )

                groups.forEachIndexed { index, g ->
                    val open = expanded == g.name
                    val using = countUsing(type, g.name)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (open) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded = if (open) null else g.name }
                                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        g.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "${g.subs.size} 个二级分类 · $using 笔在用",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    if (open) "收起" else "展开",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                GroupMenu(
                                    index = index,
                                    count = groups.size,
                                    onAddSub = { addSubTo = g.name },
                                    onRename = { renameGroup = g.name },
                                    onDelete = { deleteGroup = g.name },
                                    onMove = { delta ->
                                        moveGroup(type, index, delta)
                                    }
                                )
                            }

                            if (open) {
                                CategoryDivider()
                                if (g.subs.isEmpty()) {
                                    Text(
                                        "还没有二级分类。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                                g.subs.forEachIndexed { si, sub ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 24.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            sub,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        SubMenu(
                                            index = si,
                                            count = g.subs.size,
                                            onRename = { renameSubAsk = g.name to sub },
                                            onMove = { delta -> moveSub(type, g.name, si, delta) },
                                            onDelete = {
                                                val n = deleteSub(type, g.name, sub)
                                                say(
                                                    if (n > 0) "已删「$sub」，$n 笔记录的二级分类被清空"
                                                    else "已删「$sub」"
                                                )
                                            }
                                        )
                                    }
                                }
                                CategoryDivider()
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { addSubTo = g.name }
                                        .padding(horizontal = 24.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "＋ 添加二级分类",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // ==================== 弹窗 ====================

    if (addGroup) {
        CategoryNameDialog(
            title = if (type == "income") "添加收入分类" else "添加支出分类",
            label = "一级分类名",
            initial = "",
            onDismiss = { addGroup = false },
            onOk = { v ->
                val name = v.trim()
                val list = groupsOf(type)
                when {
                    name.isEmpty() -> say("得有个名字")
                    list.any { it.name == name } -> say("已经有「$name」了")
                    else -> {
                        list.add(CategoryGroup(name = name))
                        Store.save()
                        addGroup = false
                        say("已添加「$name」")
                    }
                }
            }
        )
    }

    renameGroup?.let { old ->
        CategoryNameDialog(
            title = "改名",
            label = "一级分类名",
            initial = old,
            helper = "改完会把历史记录里还写着「$old」的都一起改过来。",
            onDismiss = { renameGroup = null },
            onOk = { v ->
                val name = v.trim()
                val list = groupsOf(type)
                when {
                    name.isEmpty() -> say("得有个名字")
                    name == old -> renameGroup = null
                    list.any { it.name == name } -> say("已经有「$name」了")
                    else -> {
                        val n = renameCategory(type, old, name)
                        renameGroup = null
                        say(
                            if (n > 0) "「$old」改成「$name」，同步改了 $n 处记录"
                            else "「$old」改成「$name」，没有记录受影响"
                        )
                    }
                }
            }
        )
    }

    deleteGroup?.let { name ->
        val using = countUsing(type, name)
        val fallback = fallbackOf(type)
        AlertDialog(
            onDismissRequest = { deleteGroup = null },
            title = { Text("删除「$name」") },
            text = {
                Text(
                    if (using > 0)
                        "现在有 $using 笔记录用的是这个分类。\n删除后这些记录不会丢，" +
                            "会自动转到「$fallback」，二级分类清空。"
                    else
                        "这个分类眼下没有记录在用，删掉就没有了。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteGroup = null
                    when {
                        name == fallback -> say("「$fallback」是兜底分类，不能删")
                        else -> {
                            val moved = deleteCategory(type, name)
                            say(
                                if (moved > 0) "已删「$name」，$moved 笔记录转到了「$fallback」"
                                else "已删「$name」"
                            )
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteGroup = null }) { Text("取消") } }
        )
    }

    addSubTo?.let { group ->
        CategoryNameDialog(
            title = "给「$group」加二级分类",
            label = "二级分类名",
            initial = "",
            onDismiss = { addSubTo = null },
            onOk = { v ->
                val name = v.trim()
                val g = groupsOf(type).firstOrNull { it.name == group }
                when {
                    g == null -> { addSubTo = null; say("这个一级分类不在了") }
                    name.isEmpty() -> say("得有个名字")
                    g.subs.any { it == name } -> say("「$group」下面已经有「$name」了")
                    else -> {
                        g.subs.add(name)
                        Store.save()
                        addSubTo = null
                        say("已在「$group」下添加「$name」")
                    }
                }
            }
        )
    }

    renameSubAsk?.let { pair ->
        val group = pair.first
        val old = pair.second
        CategoryNameDialog(
            title = "改名",
            label = "二级分类名",
            initial = old,
            helper = "只改「$group」底下的记录。",
            onDismiss = { renameSubAsk = null },
            onOk = { v ->
                val name = v.trim()
                val g = groupsOf(type).firstOrNull { it.name == group }
                when {
                    g == null -> { renameSubAsk = null; say("这个一级分类不在了") }
                    name.isEmpty() -> say("得有个名字")
                    name == old -> renameSubAsk = null
                    g.subs.any { it == name } -> say("「$group」下面已经有「$name」了")
                    else -> {
                        val n = renameSub(type, group, old, name)
                        renameSubAsk = null
                        say(if (n > 0) "已改成「$name」，同步改了 $n 笔记录" else "已改成「$name」")
                    }
                }
            }
        )
    }

    if (resetAsk) {
        AlertDialog(
            onDismissRequest = { resetAsk = false },
            title = { Text("恢复出厂分类") },
            text = {
                Text(
                    "支出和收入两套分类都会变回出厂的样子，你自己加的和改的都没了。\n" +
                        "已经记好的账一笔不动，只是记录上写着的分类名可能对不上新分类表。\n" +
                        "确定要恢复吗？",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.data.settings.expenseCategories = Categories.defaultExpense()
                    Store.data.settings.incomeCategories = Categories.defaultIncome()
                    Store.save()
                    expanded = null
                    resetAsk = false
                    say("分类已恢复出厂设置")
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { resetAsk = false }) { Text("取消") } }
        )
    }
}

// ==================== 小组件 ====================

/** 一级分类右边的 ⋮ 菜单 */
@Composable
private fun GroupMenu(
    index: Int,
    count: Int,
    onAddSub: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "更多操作",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("添加二级分类") }, onClick = { open = false; onAddSub() })
            DropdownMenuItem(text = { Text("改名") }, onClick = { open = false; onRename() })
            DropdownMenuItem(
                text = { Text("上移") },
                enabled = index > 0,
                onClick = { open = false; onMove(-1) }
            )
            DropdownMenuItem(
                text = { Text("下移") },
                enabled = index < count - 1,
                onClick = { open = false; onMove(1) }
            )
            DropdownMenuItem(text = { Text("删除") }, onClick = { open = false; onDelete() })
        }
    }
}

/** 二级分类右边的 ⋮ 菜单 */
@Composable
private fun SubMenu(
    index: Int,
    count: Int,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "更多操作",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("改名") }, onClick = { open = false; onRename() })
            DropdownMenuItem(
                text = { Text("上移") },
                enabled = index > 0,
                onClick = { open = false; onMove(-1) }
            )
            DropdownMenuItem(
                text = { Text("下移") },
                enabled = index < count - 1,
                onClick = { open = false; onMove(1) }
            )
            DropdownMenuItem(text = { Text("删除") }, onClick = { open = false; onDelete() })
        }
    }
}

/** 一行文字的输入框弹窗 */
@Composable
private fun CategoryNameDialog(
    title: String,
    label: String,
    initial: String,
    helper: String? = null,
    onOk: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (helper != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        helper,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CategoryDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

// ==================== 小工具 ====================

/**
 * 当前这一套分类。settings 里那份是空的就先灌出厂默认，
 * 免得在副本上改了半天没存下来。
 */
private fun groupsOf(type: String): MutableList<CategoryGroup> {
    val s = Store.data.settings
    return if (type == "income") {
        if (s.incomeCategories.isEmpty()) s.incomeCategories = Categories.defaultIncome()
        s.incomeCategories
    } else {
        if (s.expenseCategories.isEmpty()) s.expenseCategories = Categories.defaultExpense()
        s.expenseCategories
    }
}

/** 这条记录算不算在这个页签里。转账在支出这边，因为它也是花出去的钱。 */
private fun typeMatches(t: Transaction, type: String): Boolean =
    if (type == "income") t.type == "income" else t.type == "expense" || t.type == "transfer"

private fun fallbackOf(type: String): String =
    if (type == "income") "其他收入" else "其他支出"

/** 有多少笔记录正在用这个一级分类 */
private fun countUsing(type: String, name: String): Int =
    Store.data.transactions.count { it.category == name && typeMatches(it, type) }

/** 一级分类改名，顺便把历史记录改过来。返回改了多少笔。 */
private fun renameCategory(type: String, old: String, new: String): Int {
    val g = groupsOf(type).firstOrNull { it.name == old } ?: return 0
    g.name = new
    var n = 0
    Store.data.transactions.forEach { t ->
        if (t.category == old && typeMatches(t, type)) {
            t.category = new
            t.hash = Store.fingerprint(t)
            n++
        }
    }
    Store.save()
    return n
}

/** 删一级分类，记录转到兜底分类。返回转了多少笔。 */
private fun deleteCategory(type: String, name: String): Int {
    val list = groupsOf(type)
    val fallback = fallbackOf(type)
    if (name == fallback) return 0
    if (list.none { it.name == fallback }) list.add(CategoryGroup(name = fallback))
    var moved = 0
    Store.data.transactions.forEach { t ->
        if (t.category == name && typeMatches(t, type)) {
            t.category = fallback
            t.subcategory = ""
            t.hash = Store.fingerprint(t)
            moved++
        }
    }
    list.removeAll { it.name == name }
    Store.save()
    return moved
}

/** 一级分类上下调序 */
private fun moveGroup(type: String, index: Int, delta: Int) {
    val list = groupsOf(type)
    val j = index + delta
    if (index !in list.indices || j !in list.indices) return
    val tmp = list[index]
    list[index] = list[j]
    list[j] = tmp
    Store.save()
}

/** 二级分类改名，只改这个一级分类底下的记录 */
private fun renameSub(type: String, group: String, old: String, new: String): Int {
    val g = groupsOf(type).firstOrNull { it.name == group } ?: return 0
    val i = g.subs.indexOf(old)
    if (i < 0) return 0
    g.subs[i] = new
    var n = 0
    Store.data.transactions.forEach { t ->
        if (t.category == group && t.subcategory == old && typeMatches(t, type)) {
            t.subcategory = new
            t.hash = Store.fingerprint(t)
            n++
        }
    }
    Store.save()
    return n
}

/** 删二级分类：记录留着，只把二级分类清空。返回清了多少笔。 */
private fun deleteSub(type: String, group: String, sub: String): Int {
    val g = groupsOf(type).firstOrNull { it.name == group } ?: return 0
    g.subs.removeAll { it == sub }
    var n = 0
    Store.data.transactions.forEach { t ->
        if (t.category == group && t.subcategory == sub && typeMatches(t, type)) {
            t.subcategory = ""
            t.hash = Store.fingerprint(t)
            n++
        }
    }
    Store.save()
    return n
}

/** 二级分类上下调序 */
private fun moveSub(type: String, group: String, index: Int, delta: Int) {
    val g = groupsOf(type).firstOrNull { it.name == group } ?: return
    val j = index + delta
    if (index !in g.subs.indices || j !in g.subs.indices) return
    val tmp = g.subs[index]
    g.subs[index] = g.subs[j]
    g.subs[j] = tmp
    Store.save()
}