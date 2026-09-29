// SettingsScreen.kt —— 设置页：外观（七套主题 + 强调色 + 货币符号 + 支出红/收入绿 + 每月从几号算起）、
// 账本（切换/新建/改名换色/归档/删除）、账户与分类的入口、数据（导出/导入/数据文件路径）、
// 备份与回档（立即备份/快照列表/回档/删除快照）、关于。
// 界面用 Compose + Material3，中文，颜色一律取 MaterialTheme.colorScheme，间距圆角用 4 的倍数。
package com.moneytracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneytracker.data.Categories
import com.moneytracker.data.Ledger
import com.moneytracker.data.Store
import com.moneytracker.ui.theme.accentOptions
import com.moneytracker.ui.theme.parseHex
import com.moneytracker.ui.theme.themeOptions
import com.moneytracker.util.Dates
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

/**
 * 设置页。整屏一个页面，从主界面右上角的齿轮进来。
 *
 * 分七组：外观 / 账本 / 账户 / 分类 / 数据 / 备份与回档 / 关于。
 *
 * 外观这一组是换肤的唯一入口：改完 settings.theme 或 settings.accent 就调
 * Store.save()，version 一变，MoneyTrackerTheme 重新算色板，整个 app 立刻换色。
 * 页面本身不写死任何界面颜色，一律取 MaterialTheme.colorScheme；
 * 只有主题预览的那个小圆点必须是各主题自己的招牌色，才用字面值。
 *
 * 数据层不动：这一页只调 Store 上已有的方法。
 * 唯一例外是删除快照——数据层没给接口，就地改 backups/index.json。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit
) {
    val tick = Store.version.collectAsState().value
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // tick 一变就重新取一份，整页跟着 Store 刷新
    val settings = remember(tick) { Store.data.settings }
    val ledgers = remember(tick) { Store.data.ledgers.toList() }
    // 快照列表不走 Store.version（备份和删快照都不算改数据），自己用一个计数器催它刷新
    var snapTick by remember { mutableStateOf(0) }
    val snapshots = remember(tick, snapTick) { Store.snapshots() }
    val currency = settings.currency

    fun say(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    // 各种弹窗的开关
    var customAccent by remember { mutableStateOf(false) }
    var currencyEdit by remember { mutableStateOf(false) }
    var monthEdit by remember { mutableStateOf(false) }
    var fontEdit by remember { mutableStateOf(false) }
    var newLedger by remember { mutableStateOf(false) }
    var editLedger by remember { mutableStateOf<Ledger?>(null) }
    var delLedger by remember { mutableStateOf<Ledger?>(null) }
    var importAsk by remember { mutableStateOf(false) }
    var restoreAsk by remember { mutableStateOf<String?>(null) }
    var delSnapAsk by remember { mutableStateOf<String?>(null) }

    // 导出：自己选保存位置（下载目录、云盘、U 盘都行）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val r = runCatching {
                val tmp = File(ctx.cacheDir, "export-tmp.json")
                Store.exportTo(tmp.absolutePath)
                val out = ctx.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("打不开目标文件")
                out.use { o -> tmp.inputStream().use { i -> i.copyTo(o) } }
                tmp.delete()
                Store.data.transactions.size
            }
            r.onSuccess { say("已导出 $it 笔记录") }
                .onFailure { say("导出失败：${it.message ?: "未知原因"}") }
        }
    }

    // 导入：选中文件先落到缓存再交给 Store，会覆盖现有全部数据
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val r = runCatching {
                val tmp = File(ctx.cacheDir, "import-tmp.json")
                val input = ctx.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("打不开所选文件")
                input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.absolutePath
            }
            r.onSuccess { path ->
                Store.importFrom(path)
                    .onSuccess { say("导入成功，共 $it 笔记录") }
                    .onFailure { say("导入失败：${it.message ?: "文件格式不对"}") }
                File(ctx.cacheDir, "import-tmp.json").delete()
            }.onFailure { say("读取失败：${it.message ?: "未知原因"}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ==================== 外观 ====================
            SectionCard("外观") {
                Text(
                    "主题",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                )
                themeOptions.forEachIndexed { i, pair ->
                    val id = pair.first
                    val label = pair.second
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                settings.theme = id
                                Store.save()
                            }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 小圆点画的是这套主题自己的主色，所以只能用字面值
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(themeSwatch(id), CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            if (id in darkThemes) "深色" else "浅色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        RadioButton(
                            selected = settings.theme == id,
                            onClick = {
                                settings.theme = id
                                Store.save()
                            }
                        )
                    }
                    if (i < themeOptions.size - 1) RowDivider()
                }

                RowDivider()

                // 强调色：跟随主题 + 八个预设 + 自定义
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("强调色", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            accentLabel(settings.accent),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            ColorDot(
                                color = MaterialTheme.colorScheme.primary,
                                selected = settings.accent.isBlank(),
                                label = "跟随主题",
                                onClick = {
                                    settings.accent = ""
                                    Store.save()
                                }
                            )
                        }
                        items(accentOptions) { a ->
                            ColorDot(
                                color = parseHex(a.hex) ?: MaterialTheme.colorScheme.primary,
                                selected = settings.accent.equals(a.hex, ignoreCase = true),
                                label = a.name,
                                onClick = {
                                    settings.accent = a.hex
                                    Store.save()
                                }
                            )
                        }
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable { customAccent = true }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "自定义强调色",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("自定义", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                RowDivider()

                SettingRow(
                    title = "货币符号",
                    subtitle = "金额前面显示的那个字",
                    value = currency,
                    onClick = { currencyEdit = true }
                )

                RowDivider()

                SettingRow(
                    title = "支出用红色",
                    subtitle = "关掉之后收支都走主题色，不红不绿",
                    trailing = {
                        Switch(
                            checked = settings.redExpense,
                            onCheckedChange = {
                                settings.redExpense = it
                                Store.save()
                            }
                        )
                    }
                )

                RowDivider()

                SettingRow(
                    title = "字号大小",
                    subtitle = "整本 app 的字都跟着变，85% 到 125%",
                    value = "${settings.fontScale}%",
                    onClick = { fontEdit = true }
                )

                RowDivider()

                SettingRow(
                    title = "每月从几号算起",
                    subtitle = "发薪日不是 1 号的话，报表按这天分月",
                    value = "${settings.monthStartDay} 号",
                    onClick = { monthEdit = true }
                )
            }

            // ==================== 账本 ====================
            SectionCard("账本") {
                val active = ledgers.filter { !it.archived }
                val archived = ledgers.filter { it.archived }

                active.forEachIndexed { i, l ->
                    val isCurrent = l.id == Store.currentLedgerId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { Store.switchLedger(l.id) }
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(ledgerColor(l), CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(l.name, style = MaterialTheme.typography.bodyLarge)
                                if (isCurrent) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "当前",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                buildString {
                                    append(ledgerRecords(l.id))
                                    append(" 笔")
                                    if (l.note.isNotBlank()) {
                                        append(" · ")
                                        append(l.note)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { editLedger = l }) {
                            Icon(Icons.Default.Edit, contentDescription = "改名换色")
                        }
                        IconButton(onClick = {
                            if (ledgers.size > 1) delLedger = l else say("最后一本账删不掉")
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除账本",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (i < active.size - 1 || archived.isNotEmpty()) RowDivider()
                }

                if (archived.isNotEmpty()) {
                    Text(
                        "已归档（${archived.size}）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                    archived.forEach { l ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .background(ledgerColor(l), CircleShape)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(l.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${ledgerRecords(l.id)} 笔 · 已归档",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                l.archived = false
                                Store.updateLedger(l)
                                say("已恢复「${l.name}」")
                            }) { Text("恢复") }
                        }
                    }
                    RowDivider()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { newLedger = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "新建账本",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "一本账 = 一个人",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ==================== 账户 ====================
            SectionCard("账户") {
                SettingRow(
                    title = "账户管理",
                    subtitle = "${Store.data.accounts.count { !it.archived }} 个账户 · 分组、归属人、余额、垫付",
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = onOpenAccounts
                )
            }

            // ==================== 分类 ====================
            SectionCard("分类") {
                SettingRow(
                    title = "分类管理",
                    subtitle = "支出 ${Categories.expenses().size} 类 · 收入 ${Categories.incomes().size} 类，" +
                        "可改名、调序、增删二级分类",
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = onOpenCategories
                )
            }

            // ==================== 数据 ====================
            SectionCard("数据") {
                SettingRow(
                    title = "导出整个库",
                    subtitle = "存成 db.json，电脑版能直接读",
                    value = "${Store.data.transactions.size} 笔",
                    onClick = {
                        exportLauncher.launch("money-tracker-${Dates.today()}.json")
                    }
                )
                RowDivider()
                SettingRow(
                    title = "从文件导入",
                    subtitle = "会覆盖手机上现有的全部数据",
                    onClick = { importAsk = true }
                )
                RowDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("数据文件", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        Store.filePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "存在本机 app 私有目录，别的程序看不到，卸载就连同数据一起删掉。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ==================== 备份与回档 ====================
            SectionCard("备份与回档") {
                SettingRow(
                    title = "立即备份",
                    subtitle = "把现在的数据存成一个快照，随时能回档",
                    onClick = {
                        val name = Store.snapshot("手动备份")
                        snapTick++
                        say("已备份：$name")
                    }
                )
                RowDivider()
                if (snapshots.isEmpty()) {
                    Text(
                        "还没有快照。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                } else {
                    Text(
                        "快照（${snapshots.size}）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                    snapshots.forEachIndexed { i, o ->
                        val file = o.optString("file")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    o.optString("reason").ifBlank { "快照" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "${prettyTime(o.optString("at"))} · ${o.optInt("count")} 笔",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { restoreAsk = file }) { Text("回档") }
                            IconButton(onClick = { delSnapAsk = file }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除快照",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (i < snapshots.size - 1) RowDivider()
                    }
                }
            }

            // ==================== 关于 ====================
            SectionCard("关于") {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("记账本", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "版本 ${appVersion(ctx)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "数据只存在这台手机里，不联网、不上传、不要任何权限。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // ==================== 弹窗 ====================

    if (customAccent) {
        HexInputDialog(
            title = "自定义强调色",
            initial = settings.accent.ifBlank { "#4A90D9" },
            onDismiss = { customAccent = false },
            onOk = { v ->
                settings.accent = v
                Store.save()
                customAccent = false
                say("强调色已改成 $v，整个 app 一起换")
            }
        )
    }

    if (currencyEdit) {
        SimpleTextDialog(
            title = "货币符号",
            initial = currency,
            helper = "常见写法：¥ ￥ $ € £ ₩。留空就退回 ¥。",
            onDismiss = { currencyEdit = false },
            onOk = { v ->
                settings.currency = if (v.trim().isEmpty()) "¥" else v.trim().take(3)
                Store.save()
                currencyEdit = false
            }
        )
    }

    if (fontEdit) {
        FontScaleDialog(
            initial = settings.fontScale,
            onDismiss = { fontEdit = false },
            onOk = { v ->
                settings.fontScale = v.coerceIn(85, 125)
                Store.save()
                fontEdit = false
                say("字号已改成 ${settings.fontScale}%")
            }
        )
    }

    if (monthEdit) {
        SimpleTextDialog(
            title = "每月从几号算起",
            initial = settings.monthStartDay.toString(),
            helper = "填 1 到 28 之间的整数。填 15 就是每月 15 号到次月 14 号算一个月。",
            numberOnly = true,
            onDismiss = { monthEdit = false },
            onOk = { v ->
                val d = v.trim().toIntOrNull()
                if (d == null || d < 1 || d > 28) {
                    say("要填 1 到 28 之间的整数")
                } else {
                    settings.monthStartDay = d
                    Store.save()
                    monthEdit = false
                }
            }
        )
    }

    if (newLedger) {
        LedgerDialog(
            initial = Ledger(name = "", color = accentOptions.first().hex),
            isNew = true,
            onDismiss = { newLedger = false },
            onSave = { l ->
                if (l.name.isBlank()) {
                    say("账本得有个名字")
                } else {
                    val made = Store.addLedger(l.name, l.color, l.note)
                    Store.switchLedger(made.id)
                    newLedger = false
                    say("已新建并切到「${made.name}」")
                }
            }
        )
    }

    editLedger?.let { l ->
        LedgerDialog(
            initial = l,
            isNew = false,
            onDismiss = { editLedger = null },
            onSave = { fixed ->
                if (fixed.name.isBlank()) {
                    say("账本得有个名字")
                } else {
                    Store.updateLedger(fixed)
                    editLedger = null
                    say("已保存")
                }
            },
            onArchive = {
                l.archived = true
                Store.updateLedger(l)
                editLedger = null
                say("已归档「${l.name}」，记录一笔不动，随时能恢复")
            }
        )
    }

    delLedger?.let { l ->
        LedgerDeleteDialog(
            ledger = l,
            others = ledgers.filter { it.id != l.id },
            recordCount = ledgerRecords(l.id),
            onDismiss = { delLedger = null },
            onConfirm = { moveTo, wipe ->
                val ok = Store.deleteLedger(l.id, moveTo, wipe)
                delLedger = null
                say(if (ok) "「${l.name}」已删除" else "删不掉：至少要留一本账")
            }
        )
    }

    if (importAsk) {
        ConfirmDialog(
            title = "从文件导入",
            text = "会用手上这个 db.json 覆盖手机里现有的全部数据（现在有 ${Store.data.transactions.size} 笔）。\n" +
                "建议先点一次「立即备份」，导错了还能回档。",
            okText = "选文件",
            onOk = {
                importAsk = false
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
            onDismiss = { importAsk = false }
        )
    }

    restoreAsk?.let { name ->
        ConfirmDialog(
            title = "回档到这份快照",
            text = "现在的数据会被这份快照整个替换掉（回档前会自动再存一份备份）。\n确定要回吗？",
            okText = "回档",
            onOk = {
                restoreAsk = null
                val ok = Store.restore(name)
                say(if (ok) "已回档" else "回档失败：快照文件不在了")
            },
            onDismiss = { restoreAsk = null }
        )
    }

    delSnapAsk?.let { name ->
        ConfirmDialog(
            title = "删除这份快照",
            text = "删了就找不回来了，确定吗？",
            okText = "删除",
            onOk = {
                delSnapAsk = null
                val ok = deleteSnapshot(name)
                snapTick++
                say(if (ok) "快照已删除" else "删除失败")
            },
            onDismiss = { delSnapAsk = null }
        )
    }
}

// ==================== 小组件 ====================

/** 一组设置：小标题 + 一张卡片 */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
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
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

/** 一条设置。右侧可以是文字值，也可以是开关这类自定义控件。 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val rowModifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowModifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 卡片内部的分隔线 */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** 颜色小圆点，选中时描一圈 */
@Composable
private fun ColorDot(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = color,
            border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
            modifier = Modifier
                .size(36.dp)
                .clickable { onClick() }
        ) {}
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 通用确认框 */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    okText: String = "确定",
    onOk: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onOk) { Text(okText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 一行文字的输入框弹窗 */
@Composable
private fun SimpleTextDialog(
    title: String,
    initial: String,
    helper: String? = null,
    numberOnly: Boolean = false,
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
                    keyboardOptions = if (numberOnly) KeyboardOptions(keyboardType = KeyboardType.Number)
                    else KeyboardOptions.Default,
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

/** 字号缩放。范围跟 Theme.kt 里读的 fontScale 一致：85 ~ 125 */
@Composable
private fun FontScaleDialog(
    initial: Int,
    onOk: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var v by remember { mutableStateOf(initial.coerceIn(85, 125)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字号大小") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton("−") { v = (v - 5).coerceAtLeast(85) }
                    Text(
                        "$v%",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    StepButton("＋") { v = (v + 5).coerceAtMost(125) }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { MiniChip("小 85%", v == 85) { v = 85 } }
                    item { MiniChip("标准 100%", v == 100) { v = 100 } }
                    item { MiniChip("大 115%", v == 115) { v = 115 } }
                    item { MiniChip("特大 125%", v == 125) { v = 125 } }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "整本 app 的字都跟着变，改完立刻生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOk(v) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 字号弹窗里的加减按钮 */
@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .size(36.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 小选择块，字号那四个预设用 */
@Composable
private fun MiniChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/** #RRGGBB 输入框，认不出来就不让点「使用」 */
@Composable
private fun HexInputDialog(
    title: String,
    initial: String,
    onOk: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val parsed = parseHex(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = parsed == null,
                    label = { Text("#RRGGBB") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val c = parsed
                    if (c != null) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(c, CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("预览", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            "写成 #4A90D9 这样的六位十六进制",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = {
                    val t = text.trim()
                    onOk(if (t.startsWith("#")) t else "#$t")
                }
            ) { Text("使用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 新建 / 改名换色的账本弹窗 */
@Composable
private fun LedgerDialog(
    initial: Ledger,
    isNew: Boolean,
    onSave: (Ledger) -> Unit,
    onDismiss: () -> Unit,
    onArchive: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initial.name) }
    var note by remember { mutableStateOf(initial.note) }
    var color by remember { mutableStateOf(initial.color.ifBlank { accentOptions.first().hex }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建账本" else "改名换色") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("账本名") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    singleLine = true,
                    label = { Text("备注（可不填）") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("颜色", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(accentOptions) { a ->
                        ColorDot(
                            color = parseHex(a.hex) ?: MaterialTheme.colorScheme.primary,
                            selected = color.equals(a.hex, ignoreCase = true),
                            label = a.name,
                            onClick = { color = a.hex }
                        )
                    }
                }
                if (!isNew && onArchive != null && !initial.archived) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "归档 = 先收起来，记录一笔不动，以后还能恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    Ledger(
                        id = initial.id,
                        name = name.trim(),
                        color = color,
                        note = note.trim(),
                        archived = initial.archived,
                        createdAt = initial.createdAt
                    )
                )
            }) { Text("保存") }
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

/** 删除账本：记录转给别的账本，或者连记录一起删 */
@Composable
private fun LedgerDeleteDialog(
    ledger: Ledger,
    others: List<Ledger>,
    recordCount: Int,
    onConfirm: (moveTo: String?, deleteRecords: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var move by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf(others.firstOrNull()?.id ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除「${ledger.name}」") },
        text = {
            Column {
                Text(
                    "这本账下有 $recordCount 笔记录，你想怎么处理？",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { move = true }
                ) {
                    RadioButton(selected = move, onClick = { move = true })
                    Text("记录转给别的账本", style = MaterialTheme.typography.bodyMedium)
                }
                if (move) {
                    others.forEach { o ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { target = o.id }
                                .padding(start = 16.dp)
                        ) {
                            RadioButton(selected = target == o.id, onClick = { target = o.id })
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .background(ledgerColor(o), CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(o.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { move = false }
                ) {
                    RadioButton(selected = !move, onClick = { move = false })
                    Text("连记录一起删掉", style = MaterialTheme.typography.bodyMedium)
                }
                if (!move) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "这 $recordCount 笔会永久消失，删之前建议先备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (move) onConfirm(target.ifEmpty { null }, false) else onConfirm(null, true)
            }) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================== 小工具 ====================

/** 各主题的招牌色，只用来画预览圆点。跟 ui/theme/Theme.kt 里的色板对应。 */
private val themeSwatches = mapOf(
    "light" to "#3D7EBF",
    "dark" to "#6FA8DC",
    "green" to "#3F7D53",
    "midnight" to "#7FB3F0",
    "warm" to "#B07D3A",
    "sakura" to "#C96B8E",
    "contrast" to "#FFD400"
)

/** 偏深色的主题，列表里标一下 */
private val darkThemes = setOf("dark", "midnight", "contrast")

private fun themeSwatch(id: String): Color =
    parseHex(themeSwatches[id] ?: "#3D7EBF") ?: Color.Gray

/** 账本的颜色，没填就给个默认蓝 */
private fun ledgerColor(l: Ledger): Color =
    parseHex(l.color) ?: parseHex("#4A90D9") ?: Color.Gray

/** 这本账下有多少笔记录 */
private fun ledgerRecords(id: String): Int =
    Store.data.transactions.count { Store.ledgerOf(it) == id }

/** 强调色的显示名 */
private fun accentLabel(accent: String): String {
    if (accent.isBlank()) return "跟随主题"
    accentOptions.firstOrNull { it.hex.equals(accent, ignoreCase = true) }?.let { return it.name }
    return accent
}

/** "2025-01-02T09:30:00" -> "2025-01-02 09:30" */
private fun prettyTime(iso: String): String =
    if (iso.length < 16) iso else iso.substring(0, 16).replace('T', ' ')

/**
 * 删除一份快照。数据层没给这个接口，就地改 backups/index.json：
 * 先把对应的 json 删掉，再把索引里的那一条摘出去。
 */
private fun deleteSnapshot(name: String): Boolean = runCatching {
    File(Store.backupsPath, name).delete()
    val idx = File(Store.backupsPath, "index.json")
    if (!idx.exists()) return@runCatching true
    val arr = JSONArray(idx.readText())
    val keep = JSONArray()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("file") != name) keep.put(o)
    }
    idx.writeText(keep.toString(), Charsets.UTF_8)
    true
}.getOrElse { false }

/** 版本号从包里读，别写死在代码里 */
private fun appVersion(ctx: android.content.Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
}.getOrElse { "1.0" }