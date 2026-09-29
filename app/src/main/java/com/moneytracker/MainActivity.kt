package com.moneytracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneytracker.data.Store
import com.moneytracker.ui.AccountScreen
import com.moneytracker.ui.AnalysisScreen
import com.moneytracker.ui.BudgetScreen
import com.moneytracker.ui.CategoryScreen
import com.moneytracker.ui.ListScreen
import com.moneytracker.ui.RecordScreen
import com.moneytracker.ui.ReportScreen
import com.moneytracker.ui.SettingsScreen
import com.moneytracker.ui.theme.MoneyTrackerTheme

/**
 * 整个 app 的壳。底下五个页签，右上角进设置。
 *
 * 数据存在手机私有目录里，不联网、不读权限、卸载就干净。
 * 格式跟电脑版同一种 db.json，导出去电脑能直接用。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 库要在画界面之前读起来，不然第一帧是空的
        Store.init(applicationContext)

        setContent {
            MoneyTrackerTheme {
                AppRoot()
            }
        }
    }
}

private data class Tab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("记账", Icons.Filled.Add),
    Tab("明细", Icons.AutoMirrored.Filled.List),
    Tab("报表", Icons.Filled.PieChart),
    Tab("预算", Icons.Filled.Savings),
    Tab("分析", Icons.Filled.Analytics)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    // 数据一变就重画
    // 顶层也要订阅数据版本：账本名、笔数这些是画在壳上的，
    // 子页面各自订阅自己的，管不到这里。
    val ver by Store.version.collectAsState()

    var tabIndex by remember { mutableStateOf(0) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var ledgerMenu by remember { mutableStateOf(false) }

    // 设置 / 账户 / 分类管理是整屏页面，进去就盖住主界面
    when {
        showAccounts -> {
            AccountScreen(onBack = { showAccounts = false })
            return
        }
        showCategories -> {
            CategoryScreen(onBack = { showCategories = false })
            return
        }
        showSettings -> {
            SettingsScreen(
                onBack = { showSettings = false },
                onOpenAccounts = { showAccounts = true },
                onOpenCategories = { showCategories = true }
            )
            return
        }
    }

    val ledger = remember(ver) { Store.currentLedger() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 账本切换就放在标题上，点一下弹出来
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { ledgerMenu = true }
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(colorOf(ledger.color), CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                ledger.name.ifEmpty { "我" },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "切换账本",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = ledgerMenu,
                            onDismissRequest = { ledgerMenu = false }
                        ) {
                            Store.data.ledgers.filter { !it.archived }.forEach { l ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .size(10.dp)
                                                    .background(colorOf(l.color), CircleShape)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            val n = Store.data.transactions.count {
                                                Store.ledgerOf(it) == l.id
                                            }
                                            Text("${l.name}（$n 笔）")
                                        }
                                    },
                                    onClick = {
                                        Store.switchLedger(l.id)
                                        ledgerMenu = false
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tabIndex == i,
                        onClick = {
                            tabIndex = i
                            // 从别的页签回到记账页时，退出编辑状态
                            if (i != 0) editingId = null
                        },
                        icon = { Icon(t.icon, contentDescription = t.title) },
                        label = { Text(t.title) }
                    )
                }
            }
        }
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            when (tabIndex) {
                0 -> RecordScreen(
                    editId = editingId,
                    onDone = {
                        editingId = null
                        tabIndex = 1
                    }
                )
                1 -> ListScreen(
                    onEdit = { id ->
                        editingId = id
                        tabIndex = 0
                    }
                )
                2 -> ReportScreen()
                3 -> BudgetScreen()
                else -> AnalysisScreen()
            }
        }
    }
}

private fun colorOf(hex: String): Color {
    val h = hex.trim().removePrefix("#")
    if (h.length != 6) return Color(0xFF4A90D9)
    val v = h.toLongOrNull(16) ?: return Color(0xFF4A90D9)
    return Color(0xFF000000 or v)
}