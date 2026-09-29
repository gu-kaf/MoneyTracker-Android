package com.moneytracker.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 手机版的数据模型，字段名和电脑版逐字一致。
 *
 * 两边共用同一种 db.json：手机导出的文件电脑能读，电脑的能导进手机。
 * 所以这里每个字段的名字、类型、缺省值都不能随便动。
 * 金额一律用「分」存整数，绝不用浮点数。
 */

// 生成 id 的方式跟电脑版一致：32 位十六进制，没有横线
fun newId(): String = UUID.randomUUID().toString().replace("-", "")

fun nowIso(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
    .format(java.util.Date())

data class Transaction(
    var id: String = newId(),
    var type: String = "expense",        // expense / income / transfer
    var amount: Long = 0,                // 分
    var date: String = "",               // yyyy-MM-dd
    var time: String = "",               // HH:mm
    var category: String = "",
    var subcategory: String = "",
    var account: String = "现金",
    var toAccount: String = "",
    var merchant: String = "",
    var note: String = "",
    var tags: MutableList<String> = mutableListOf(),
    var source: String = "manual",       // manual / auto / csv / import
    var excludeFromBudget: Boolean = false,

    // 这一笔记在谁的账本上。空串 = 默认账本（老数据没有这个字段，都算默认）
    var ledgerId: String = "",

    // 替别人花的钱
    var forWhom: String = "",
    var isAdvance: Boolean = false,
    var isReimbursed: Boolean = false,

    var hash: String = "",
    var createdAt: String = nowIso(),
    var updatedAt: String = nowIso()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("amount", amount)
        put("date", date)
        put("time", time)
        put("category", category)
        put("subcategory", subcategory)
        put("account", account)
        put("toAccount", toAccount)
        put("merchant", merchant)
        put("note", note)
        put("tags", JSONArray(tags))
        put("source", source)
        put("excludeFromBudget", excludeFromBudget)
        put("ledgerId", ledgerId)
        put("forWhom", forWhom)
        put("isAdvance", isAdvance)
        put("isReimbursed", isReimbursed)
        put("hash", hash)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Transaction {
            val t = Transaction()
            t.id = o.optString("id", newId())
            t.type = o.optString("type", "expense")
            t.amount = o.optLong("amount", 0)
            t.date = o.optString("date", "")
            t.time = o.optString("time", "")
            t.category = o.optString("category", "")
            t.subcategory = o.optString("subcategory", "")
            t.account = o.optString("account", "现金")
            t.toAccount = o.optString("toAccount", "")
            t.merchant = o.optString("merchant", "")
            t.note = o.optString("note", "")
            t.tags = mutableListOf()
            o.optJSONArray("tags")?.let { a -> for (i in 0 until a.length()) t.tags.add(a.optString(i)) }
            t.source = o.optString("source", "manual")
            t.excludeFromBudget = o.optBoolean("excludeFromBudget", false)
            t.ledgerId = o.optString("ledgerId", "")
            t.forWhom = o.optString("forWhom", "")
            t.isAdvance = o.optBoolean("isAdvance", false)
            t.isReimbursed = o.optBoolean("isReimbursed", false)
            t.hash = o.optString("hash", "")
            t.createdAt = o.optString("createdAt", nowIso())
            t.updatedAt = o.optString("updatedAt", t.createdAt)
            return t
        }
    }
}

/** 一个账本 = 一个人。多个人共用一个程序，各记各的，还能横向比。 */
data class Ledger(
    var id: String = newId(),
    var name: String = "",
    var color: String = "",
    var note: String = "",
    var archived: Boolean = false,
    var createdAt: String = nowIso()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("color", color)
        put("note", note); put("archived", archived); put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Ledger(
            id = o.optString("id", newId()),
            name = o.optString("name", ""),
            color = o.optString("color", ""),
            note = o.optString("note", ""),
            archived = o.optBoolean("archived", false),
            createdAt = o.optString("createdAt", nowIso())
        )
    }
}

data class Account(
    var id: String = newId(),
    var name: String = "",
    var kind: String = "cash",           // cash / debit / credit / wallet / investment
    var group: String = "自己",           // 分组名，自由填
    var owner: String = "",
    var isOthers: Boolean = false,
    var initialBalance: Long = 0,
    var includeInAssets: Boolean = true,
    var archived: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("kind", kind)
        put("group", group); put("owner", owner); put("isOthers", isOthers)
        put("initialBalance", initialBalance)
        put("includeInAssets", includeInAssets); put("archived", archived)
    }

    companion object {
        fun fromJson(o: JSONObject) = Account(
            id = o.optString("id", newId()),
            name = o.optString("name", ""),
            kind = o.optString("kind", "cash"),
            group = o.optString("group", "自己"),
            owner = o.optString("owner", ""),
            isOthers = o.optBoolean("isOthers", false),
            initialBalance = o.optLong("initialBalance", 0),
            includeInAssets = o.optBoolean("includeInAssets", true),
            archived = o.optBoolean("archived", false)
        )
    }
}

data class Budget(
    var id: String = newId(),
    var period: String = "monthly",      // monthly / weekly / yearly
    var scope: String = "total",         // total 或分类名
    var amount: Long = 0,
    var startDate: String = "",
    var alertThreshold: Double = 0.8,
    var rollover: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("period", period); put("scope", scope); put("amount", amount)
        put("startDate", startDate); put("alertThreshold", alertThreshold); put("rollover", rollover)
    }

    companion object {
        fun fromJson(o: JSONObject) = Budget(
            id = o.optString("id", newId()),
            period = o.optString("period", "monthly"),
            scope = o.optString("scope", "total"),
            amount = o.optLong("amount", 0),
            startDate = o.optString("startDate", ""),
            alertThreshold = o.optDouble("alertThreshold", 0.8),
            rollover = o.optBoolean("rollover", false)
        )
    }
}

data class Rule(
    var id: String = newId(),
    var keyword: String = "",            // 多个关键词用 | 分隔
    var matchField: String = "both",     // merchant / note / both
    var setCategory: String = "",
    var setSubcategory: String = "",
    var setType: String = "",
    var priority: Int = 50,
    var hitCount: Int = 0,
    var enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("keyword", keyword); put("matchField", matchField)
        put("setCategory", setCategory); put("setSubcategory", setSubcategory)
        put("setType", setType); put("priority", priority)
        put("hitCount", hitCount); put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            id = o.optString("id", newId()),
            keyword = o.optString("keyword", ""),
            matchField = o.optString("matchField", "both"),
            setCategory = o.optString("setCategory", ""),
            setSubcategory = o.optString("setSubcategory", ""),
            setType = o.optString("setType", ""),
            priority = o.optInt("priority", 50),
            hitCount = o.optInt("hitCount", 0),
            enabled = o.optBoolean("enabled", true)
        )
    }
}

data class CategoryGroup(
    var id: String = newId(),
    var name: String = "",
    var subs: MutableList<String> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("subs", JSONArray(subs))
    }

    companion object {
        fun fromJson(o: JSONObject): CategoryGroup {
            val g = CategoryGroup(
                id = o.optString("id", newId()),
                name = o.optString("name", "")
            )
            o.optJSONArray("subs")?.let { a -> for (i in 0 until a.length()) g.subs.add(a.optString(i)) }
            return g
        }
    }
}

data class AppSettings(
    var theme: String = "light",
    var accent: String = "",
    var monthStartDay: Int = 1,
    var redExpense: Boolean = true,
    var allowDuplicateImport: Boolean = false,
    var currency: String = "¥",
    var fontId: String = "soft",
    var fontScale: Int = 100,
    var expenseCategories: MutableList<CategoryGroup> = mutableListOf(),
    var incomeCategories: MutableList<CategoryGroup> = mutableListOf(),
    var currentLedger: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("theme", theme); put("accent", accent)
        put("monthStartDay", monthStartDay); put("redExpense", redExpense)
        put("allowDuplicateImport", allowDuplicateImport)
        put("currency", currency); put("fontId", fontId); put("fontScale", fontScale)
        // 手机版没有的几个电脑版字段也写出来，免得电脑读到 null
        put("autoScanDir", ""); put("autoScanEnabled", false); put("autoScanIntervalSec", 60)
        put("fontFile", ""); put("bgImage", ""); put("bgBlur", 18); put("bgDim", 62); put("bgFit", "fill")
        put("expenseCategories", JSONArray().apply { expenseCategories.forEach { put(it.toJson()) } })
        put("incomeCategories", JSONArray().apply { incomeCategories.forEach { put(it.toJson()) } })
        put("currentLedger", currentLedger)
    }

    companion object {
        fun fromJson(o: JSONObject): AppSettings {
            val s = AppSettings()
            s.theme = o.optString("theme", "light")
            s.accent = o.optString("accent", "")
            s.monthStartDay = o.optInt("monthStartDay", 1)
            s.redExpense = o.optBoolean("redExpense", true)
            s.allowDuplicateImport = o.optBoolean("allowDuplicateImport", false)
            s.currency = o.optString("currency", "¥")
            s.fontId = o.optString("fontId", "soft")
            s.fontScale = o.optInt("fontScale", 100)
            s.currentLedger = o.optString("currentLedger", "")
            o.optJSONArray("expenseCategories")?.let { a ->
                for (i in 0 until a.length()) s.expenseCategories.add(CategoryGroup.fromJson(a.getJSONObject(i)))
            }
            o.optJSONArray("incomeCategories")?.let { a ->
                for (i in 0 until a.length()) s.incomeCategories.add(CategoryGroup.fromJson(a.getJSONObject(i)))
            }
            return s
        }
    }
}

/** 整个库。format 和 version 跟电脑版一致，两边互相认得出来。 */
data class AppData(
    var format: String = "money-tracker",
    var version: Int = 1,
    var exportedAt: String = "",
    var transactions: MutableList<Transaction> = mutableListOf(),
    var accounts: MutableList<Account> = mutableListOf(),
    var budgets: MutableList<Budget> = mutableListOf(),
    var rules: MutableList<Rule> = mutableListOf(),
    var ledgers: MutableList<Ledger> = mutableListOf(),
    var settings: AppSettings = AppSettings()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", format)
        put("version", version)
        put("exportedAt", exportedAt)
        put("transactions", JSONArray().apply { transactions.forEach { put(it.toJson()) } })
        put("accounts", JSONArray().apply { accounts.forEach { put(it.toJson()) } })
        put("budgets", JSONArray().apply { budgets.forEach { put(it.toJson()) } })
        put("rules", JSONArray().apply { rules.forEach { put(it.toJson()) } })
        put("ledgers", JSONArray().apply { ledgers.forEach { put(it.toJson()) } })
        put("settings", settings.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): AppData {
            val d = AppData()
            d.format = o.optString("format", "money-tracker")
            d.version = o.optInt("version", 1)
            d.exportedAt = o.optString("exportedAt", "")
            o.optJSONArray("transactions")?.let { a ->
                for (i in 0 until a.length()) d.transactions.add(Transaction.fromJson(a.getJSONObject(i)))
            }
            o.optJSONArray("accounts")?.let { a ->
                for (i in 0 until a.length()) d.accounts.add(Account.fromJson(a.getJSONObject(i)))
            }
            o.optJSONArray("budgets")?.let { a ->
                for (i in 0 until a.length()) d.budgets.add(Budget.fromJson(a.getJSONObject(i)))
            }
            o.optJSONArray("rules")?.let { a ->
                for (i in 0 until a.length()) d.rules.add(Rule.fromJson(a.getJSONObject(i)))
            }
            o.optJSONArray("ledgers")?.let { a ->
                for (i in 0 until a.length()) d.ledgers.add(Ledger.fromJson(a.getJSONObject(i)))
            }
            o.optJSONObject("settings")?.let { d.settings = AppSettings.fromJson(it) }
            return d
        }
    }
}