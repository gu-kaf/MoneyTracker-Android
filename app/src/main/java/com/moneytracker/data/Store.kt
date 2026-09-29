package com.moneytracker.data

import android.content.Context
import com.moneytracker.util.Dates
import com.moneytracker.util.Hash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 库。整个 app 只有这一份数据，靠它读写 db.json。
 *
 * 文件放在 app 私有目录里，别的 app 看不到，卸载就一起删掉。
 * 格式和电脑版完全一致，导出去电脑能直接读。
 */
object Store {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var backup: File
    private lateinit var backupsDir: File

    var data: AppData = AppData()
        private set

    /** 界面订阅这个，数据一变就重画 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    val filePath: String get() = file.absolutePath
    val backupsPath: String get() = backupsDir.absolutePath

    /** 当前在看哪本账 */
    var currentLedgerId: String = ""
        private set

    // ==================== 启动 / 存取 ====================

    fun init(ctx: Context) {
        initAt(ctx.filesDir.absolutePath)
    }

    /**
     * 指定一个目录来初始化。单元测试走这里——测试跑在本机 JVM 上，
     * 根本没有 Android 环境，拿不到 Context。
     * 两条路径后续行为完全一样，不存在「测试过的一套、真机另一套」。
     */
    fun initAt(dirPath: String) {
        dir = File(dirPath)
        dir.mkdirs()
        file = File(dir, "db.json")
        backup = File(dir, "db.json.bak")
        backupsDir = File(dir, "backups").also { it.mkdirs() }

        if (file.exists()) {
            runCatching { loadFrom(file) }.onFailure {
                // 主文件坏了就用备份顶上，别让用户看到空库
                if (backup.exists()) runCatching { loadFrom(backup) }
                else fresh()
            }
        } else {
            fresh()
        }
        Categories.ensure()
        ensureLedger()
        _version.value++
    }

    private fun fresh() {
        data = AppData()
        data.accounts = Defaults.accounts()
        data.rules = Defaults.rules()
        data.settings.expenseCategories = Categories.defaultExpense()
        data.settings.incomeCategories = Categories.defaultIncome()
        data.ledgers = mutableListOf(
            Ledger(name = "我", color = "#4A90D9", note = "默认账本")
        )
    }

    private fun loadFrom(f: File) {
        val text = f.readText(Charsets.UTF_8)
        data = AppData.fromJson(JSONObject(text))
        if (data.accounts.isEmpty()) data.accounts = Defaults.accounts()
        if (data.rules.isEmpty()) data.rules = Defaults.rules()
        if (data.ledgers.isEmpty()) {
            data.ledgers = mutableListOf(Ledger(name = "我", color = "#4A90D9", note = "默认账本"))
        }
    }

    /** 保存。每次写完顺手留一份 .bak，主文件坏了还能救。 */
    fun save() {
        runCatching {
            if (file.exists()) file.copyTo(backup, overwrite = true)
            data.exportedAt = nowStamp()
            file.writeText(data.toJson().toString(), Charsets.UTF_8)
        }
        _version.value++
    }

    /** 从外部文件导入整个库（用户在设置里选文件） */
    fun importFrom(path: String): Result<Int> = runCatching {
        val text = File(path).readText(Charsets.UTF_8)
        val incoming = AppData.fromJson(JSONObject(text))
        data = incoming
        if (data.accounts.isEmpty()) data.accounts = Defaults.accounts()
        Categories.ensure()
        ensureLedger()
        save()
        data.transactions.size
    }

    /** 导出整个库 */
    fun exportTo(path: String) {
        data.exportedAt = nowStamp()
        File(path).writeText(data.toJson().toString(), Charsets.UTF_8)
    }

    private fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    // ==================== 账本 ====================

    private fun ensureLedger() {
        if (data.ledgers.isEmpty()) {
            data.ledgers.add(Ledger(name = "我", color = "#4A90D9"))
        }
        val want = data.settings.currentLedger
        currentLedgerId = if (want.isNotEmpty() && data.ledgers.any { it.id == want }) want
        else data.ledgers.first().id
    }

    fun switchLedger(id: String) {
        if (data.ledgers.any { it.id == id }) {
            currentLedgerId = id
            data.settings.currentLedger = id
            save()
        }
    }

    fun currentLedger(): Ledger =
        data.ledgers.firstOrNull { it.id == currentLedgerId } ?: data.ledgers.first()

    fun addLedger(name: String, color: String, note: String = ""): Ledger {
        val l = Ledger(name = name, color = color, note = note)
        data.ledgers.add(l)
        save()
        return l
    }

    fun updateLedger(l: Ledger) {
        val i = data.ledgers.indexOfFirst { it.id == l.id }
        if (i >= 0) { data.ledgers[i] = l; save() }
    }

    /** 删账本。记录要么转给别的账本，要么一起删。最后一个账本删不掉。 */
    fun deleteLedger(id: String, moveTo: String?, deleteRecords: Boolean): Boolean {
        if (data.ledgers.size <= 1) return false
        if (!data.ledgers.any { it.id == id }) return false
        if (deleteRecords) {
            data.transactions.removeAll { it.ledgerId == id || (it.ledgerId.isEmpty() && id == data.ledgers.firstOrNull()?.id) }
        } else if (moveTo != null) {
            data.transactions.forEach {
                if (it.ledgerId == id) { it.ledgerId = moveTo; it.hash = fingerprint(it) }
            }
        }
        data.ledgers.removeAll { it.id == id }
        if (currentLedgerId == id) switchLedger(data.ledgers.first().id)
        save()
        return true
    }

    // ==================== 记录 ====================

    /**
     * 判重指纹。同一本账里指纹相同就算重复；不同账本各记一笔互不影响。
     *
     * 这里拼的是 ledgerOf(t) 而不是 t.ledgerId，很要紧：
     * 电脑版存下来的老记录 ledgerId 是空的，而手机上新记的账会填上当前账本号。
     * 直接拼 t.ledgerId 的话，同一笔账一个带账本号一个不带，拼出来不一样，
     * 判重就废了——用户从电脑导过来的账，在手机上再记一遍会重复计入。
     */
    fun fingerprint(t: Transaction): String =
        Hash.of(t.date, t.amount, t.merchant, t.note, t.account) + ledgerOf(t)

    /** 当前账本里的记录，按日期倒序 */
    fun transactions(): List<Transaction> {
        val lid = currentLedgerId
        return data.transactions
            .filter { ledgerOf(it) == lid }
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.time })
    }

    /** 这条记录属于哪本账。老数据没有 ledgerId，算第一本 */
    fun ledgerOf(t: Transaction): String =
        if (t.ledgerId.isNullOrEmpty()) data.ledgers.firstOrNull()?.id ?: "" else t.ledgerId

    /** 这笔是不是重复的（同账本里已有同样指纹） */
    fun isDuplicate(t: Transaction, ignoreId: String? = null): Boolean {
        val fp = fingerprint(t)
        return data.transactions.any { it.id != ignoreId && fingerprint(it) == fp }
    }

    fun add(t: Transaction): Boolean {
        if (t.ledgerId.isNullOrEmpty()) t.ledgerId = currentLedgerId
        if (isDuplicate(t)) return false
        t.hash = fingerprint(t)
        t.updatedAt = nowStamp()
        data.transactions.add(t)
        applyRule(t)
        save()
        return true
    }

    /** 加的时候顺手拿规则猜一下分类 */
    private fun applyRule(t: Transaction) {
        if (t.category.isNotEmpty()) return
        val text = (t.merchant + " " + t.note).trim()
        Defaults.match(text)?.let { r ->
            if (r.setType.isNotEmpty()) t.type = r.setType
            if (r.setCategory.isNotEmpty()) t.category = r.setCategory
            if (r.setSubcategory.isNotEmpty()) t.subcategory = r.setSubcategory
            r.hitCount++
        }
        t.hash = fingerprint(t)
    }

    fun update(t: Transaction): Boolean {
        val i = data.transactions.indexOfFirst { it.id == t.id }
        if (i < 0) return false
        if (isDuplicate(t, ignoreId = t.id)) return false
        t.updatedAt = nowStamp()
        t.hash = fingerprint(t)
        data.transactions[i] = t
        save()
        return true
    }

    fun delete(id: String) {
        data.transactions.removeAll { it.id == id }
        save()
    }

    fun deleteMany(ids: Collection<String>) {
        data.transactions.removeAll { ids.contains(it.id) }
        save()
    }

    /** 把某人的垫付一次全部标成已还 */
    fun reimburseAll(person: String, done: Boolean) {
        data.transactions.forEach {
            if (it.forWhom == person && it.isAdvance && ledgerOf(it) == currentLedgerId) {
                it.isReimbursed = done
                it.updatedAt = nowStamp()
            }
        }
        save()
    }

    // ==================== 账户 ====================

    fun accounts(): List<Account> = data.accounts.filter { !it.archived }

    fun accountNames(): List<String> = data.accounts.filter { !it.archived }.map { it.name }

    fun addAccount(a: Account) {
        if (data.accounts.any { it.name == a.name && !it.archived }) return
        data.accounts.add(a)
        save()
    }

    /**
     * 改账户。改名的话要把历史记录里写着旧名字的地方一起改掉，
     * 不然那部分账会挂在已经不存在的账户上，余额和资产都算不准。
     * oldName 不传就自己拿改之前那个名字。
     */
    fun updateAccount(a: Account, oldName: String? = null) {
        val i = data.accounts.indexOfFirst { it.id == a.id }
        if (i < 0) return
        val old = oldName ?: data.accounts[i].name
        if (old != a.name) {
            data.transactions.forEach { t ->
                if (t.account == old) {
                    t.account = a.name
                    t.hash = fingerprint(t)
                }
                if (t.toAccount == old) t.toAccount = a.name
            }
        }
        data.accounts[i] = a
        save()
    }

    /**
     * 归档是默认做法，账还在，只是不再出现在选择列表里。
     *
     * reallyDelete = true 是真从列表里抹掉，但只在该账户名下一笔记录都没有时才允许。
     * 不然那些记录会挂在一个不存在的账户上，而 accountBalance 找不到账户就返回 0，
     * 那部分钱会在余额和净资产里凭空少掉——用户的钱不能这么丢。
     * 真要彻底删，得先把记录迁到别的账户去。
     */
    fun deleteAccount(id: String, reallyDelete: Boolean = false): Boolean {
        val i = data.accounts.indexOfFirst { it.id == id }
        if (i < 0) return false
        if (!reallyDelete) {
            data.accounts[i].archived = true
            save()
            return true
        }
        val name = data.accounts[i].name
        val used = data.transactions.count { it.account == name || it.toAccount == name }
        if (used > 0) return false
        data.accounts.removeAt(i)
        save()
        return true
    }

    /** 某个账户名下挂着多少笔记录，删账户前拿它给用户提示 */
    fun accountUsage(name: String): Int =
        data.transactions.count { it.account == name || it.toAccount == name }

    /** 某个账户当前的余额 = 初始余额 + 收入 - 支出 + 转入 - 转出 */
    fun accountBalance(name: String, ledgerOnly: Boolean = false): Long {
        val a = data.accounts.firstOrNull { it.name == name } ?: return 0
        var bal = a.initialBalance
        data.transactions.forEach { t ->
            if (ledgerOnly && ledgerOf(t) != currentLedgerId) return@forEach
            when (t.type) {
                "income" -> if (t.account == name) bal += t.amount
                "expense" -> if (t.account == name) bal -= t.amount
                "transfer" -> {
                    if (t.account == name) bal -= t.amount
                    if (t.toAccount == name) bal += t.amount
                }
            }
        }
        return bal
    }

    /**
     * 净资产。只算标了「计入资产」且不属于别人的账户。
     * 这个是跨账本合计的——账户本来就好几本账共用。
     */
    fun netAssets(): Long = data.accounts
        .filter { it.includeInAssets && !it.isOthers && !it.archived }
        .sumOf { accountBalance(it.name) }

    // ==================== 预算 ====================

    /**
     * 预算跟电脑版一样是全局的，不跟账本走——Budget 模型里本来就没有 ledgerId，
     * 电脑版也这样。要改成每本账一套，得先动模型，两边一起动。
     */
    fun budgets(): List<Budget> = data.budgets

    fun budgetOf(scope: String): Budget? = data.budgets.firstOrNull { it.scope == scope }

    fun addBudget(b: Budget) {
        data.budgets.add(b)
        save()
    }

    fun updateBudget(b: Budget) {
        val i = data.budgets.indexOfFirst { it.id == b.id }
        if (i >= 0) {
            data.budgets[i] = b
            save()
        }
    }

    fun deleteBudget(id: String) {
        data.budgets.removeAll { it.id == id }
        save()
    }

    /**
     * 这个月到今天的支出进度。返回 (已花, 上限)。
     * scope 是 "total" 就统计全部支出，否则只看那个分类。
     * 标了「不计入预算」的记录会跳过。
     */
    fun budgetProgress(b: Budget): Pair<Long, Long> {
        val ym = Dates.yearMonth(Dates.today())
        val (from, to) = monthRange(ym)
        val used = transactions()
            .filter { it.date >= from && it.date <= to }
            .filter { it.type == "expense" && !it.excludeFromBudget }
            .filter { b.scope == "total" || it.category == b.scope }
            .sumOf { it.amount }
        return used to b.amount
    }

    // ==================== 统计 ====================

    /** 某个时间段里的记录（含首尾） */
    fun inRange(from: String, to: String, list: List<Transaction>? = null): List<Transaction> {
        val src = list ?: transactions()
        return src.filter { it.date >= from && it.date <= to }
    }

    fun monthRange(ym: String): Pair<String, String> {
        val from = "$ym-01"
        val to = "$ym-${Dates.daysInMonth(ym).toString().padStart(2, '0')}"
        return from to to
    }

    data class Summary(val income: Long, val expense: Long, val count: Int) {
        val net: Long get() = income - expense
    }

    fun summary(list: List<Transaction>): Summary {
        var inc = 0L; var exp = 0L
        list.forEach { if (it.type == "income") inc += it.amount else if (it.type == "expense") exp += it.amount }
        return Summary(inc, exp, list.size)
    }

    /** 按分类汇总支出，从多到少 */
    fun byCategory(list: List<Transaction>): List<Pair<String, Long>> =
        list.filter { it.type == "expense" }
            .groupBy { it.category.ifEmpty { "其他支出" } }
            .map { it.key to it.value.sumOf { t -> t.amount } }
            .sortedByDescending { it.second }

    /** 按月份汇总，给走势图用 */
    fun byMonth(list: List<Transaction>): List<Triple<String, Long, Long>> =
        list.groupBy { Dates.yearMonth(it.date) }
            .filter { it.key.isNotEmpty() }
            .map { (ym, ts) ->
                val inc = ts.filter { it.type == "income" }.sumOf { it.amount }
                val exp = ts.filter { it.type == "expense" }.sumOf { it.amount }
                Triple(ym, inc, exp)
            }
            .sortedBy { it.first }

    // ==================== 备份 ====================

    /**
     * 快照文件名精确到毫秒，而且撞了就往后面加序号。
     * 原来只用秒，用户连着点两下「立即备份」，第二个会把第一个盖掉，
     * 等于白备份一次。
     */
    private fun uniqueSnapshotName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        var name = "$stamp.json"
        var i = 1
        while (File(backupsDir, name).exists()) {
            name = "$stamp-$i.json"
            i++
        }
        return name
    }

    fun snapshot(reason: String): String {
        val f = File(backupsDir, uniqueSnapshotName())
        data.exportedAt = nowStamp()
        f.writeText(data.toJson().toString(), Charsets.UTF_8)
        val idx = File(backupsDir, "index.json")
        val arr = runCatching { org.json.JSONArray(idx.readText()) }.getOrNull()
            ?: org.json.JSONArray()
        arr.put(JSONObject().apply {
            put("file", f.name)
            put("reason", reason)
            put("count", data.transactions.size)
            put("at", nowStamp())
        })
        idx.writeText(arr.toString(), Charsets.UTF_8)
        return f.name
    }

    fun snapshots(): List<org.json.JSONObject> {
        val idx = File(backupsDir, "index.json")
        if (!idx.exists()) return emptyList()
        val arr = runCatching { org.json.JSONArray(idx.readText()) }.getOrElse { return emptyList() }
        return (0 until arr.length()).mapNotNull { runCatching { arr.getJSONObject(it) }.getOrNull() }.reversed()
    }

    fun restore(name: String): Boolean {
        val f = File(backupsDir, name)
        if (!f.exists()) return false
        return runCatching {
            snapshot("回档前自动备份")
            loadFrom(f)
            save()
            true
        }.getOrElse { false }
    }
}