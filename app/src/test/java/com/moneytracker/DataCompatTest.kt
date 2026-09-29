package com.moneytracker

import com.moneytracker.data.Account
import com.moneytracker.data.AppData
import com.moneytracker.data.Budget
import com.moneytracker.data.Categories
import com.moneytracker.data.Defaults
import com.moneytracker.data.Ledger
import com.moneytracker.data.Rule
import com.moneytracker.data.Transaction
import com.moneytracker.util.Dates
import com.moneytracker.util.Hash
import com.moneytracker.util.Money
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 数据层自检。跑在本机 JVM 上，不需要手机也不需要模拟器。
 *
 * 最要紧的是第一条：手机版算出来的判重指纹必须和电脑版逐位相同。
 * 两边不一致的话，同一笔账在手机和电脑之间导来导去会被当成两笔，
 * 金额直接翻倍——这是数据互通里最容易出事的地方，所以要拿真实账本对。
 *
 * resources/hash-cases.txt 是本机真实账本导出的比对样本，里面有真实
 * 商户名，不提交到仓库。文件不存在时这一条自动跳过，其余检查照跑。
 */
class DataCompatTest {

    private val sep = '\u0001'

    /** 每行：日期  金额分  商户  备注  账户  账本 电脑版算出的指纹 */
    private fun cases(): List<List<String>> {
        val f = File("src/test/resources/hash-cases.txt")
        if (!f.exists()) return emptyList()
        return f.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { it.split(sep) }
            .filter { it.size >= 7 }
    }

    @Test
    fun fingerprintIsIdenticalToDesktop() {
        val rows = cases()
        if (rows.isEmpty()) {
            println("没有 hash-cases.txt，这条跳过")
            return
        }
        var bad = 0
        val detail = StringBuilder()
        rows.forEach { p ->
            val mine = Hash.of(
                date = p[0],
                amount = p[1].toLong(),
                merchant = p[2],
                note = p[3],
                account = p[4]
            ) + p[5]
            if (mine != p[6]) {
                bad++
                if (detail.length < 400) {
                    detail.append("\n  ").append(p[0]).append(' ')
                        .append(p[4]).append("  电脑=").append(p[6])
                        .append("  手机=").append(mine)
                }
            }
        }
        assertEquals(
            "和电脑版指纹对不上的有 $bad 条（一共比了 ${rows.size} 条）$detail",
            0, bad
        )
    }

    /** 商户名和备注都空的时候，指纹也得算得出来，不能空指针 */
    @Test
    fun fingerprintHandlesEmptyFields() {
        val a = Hash.of("2026-09-01", 100, "", "", "现金")
        assertEquals(32, a.length)
        // 商户为空时应该拿备注顶上，这两条结果不同才对
        val withNote = Hash.of("2026-09-01", 100, "", "买水", "现金")
        val withMerchant = Hash.of("2026-09-01", 100, "买水", "", "现金")
        assertEquals(withNote, withMerchant)
        assertTrue(a != withNote)
    }

    @Test
    fun moneyFormat() {
        assertEquals("0.00", Money.text(0))
        assertEquals("1.00", Money.text(100))
        assertEquals("12.34", Money.text(1234))
        assertEquals("1,234.56", Money.text(123456))
        assertEquals("-12.34", Money.text(-1234))
        assertEquals("¥1,234.56", Money.yuan(123456))
        assertEquals("+¥12.34", Money.signed(1234))
        assertEquals("-¥12.34", Money.signed(-1234))
    }

    @Test
    fun moneyParse() {
        assertEquals(100L, Money.parse("1"))
        assertEquals(1234L, Money.parse("12.34"))
        assertEquals(1230L, Money.parse("12.3"))
        assertEquals(123456L, Money.parse("1,234.56"))
        assertEquals(1200L, Money.parse("¥12"))
        assertEquals(-1234L, Money.parse("-12.34"))
        assertEquals(0L, Money.parse("0"))
        // 超过两位小数要截断，不能四舍五入，不然金额对不上
        assertEquals(1299L, Money.parse("12.999"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse(""))
        assertNull(Money.parse("   "))
        assertNull(Money.parse("-"))
    }

    @Test
    fun defaultCategoryTree() {
        val e = Categories.defaultExpense()
        val i = Categories.defaultIncome()
        assertEquals(12, e.size)
        assertEquals(7, i.size)
        assertEquals(
            listOf("早餐", "午餐", "晚餐", "外卖", "零食饮料", "聚餐"),
            e.first { it.name == "餐饮" }.subs
        )
        assertTrue(e.any { it.name == "其他支出" })
        assertTrue(i.any { it.name == "其他收入" })
        // 一级分类不能重名，否则界面上两个同名按钮点哪个都不知道
        assertEquals(e.size, e.map { it.name }.toSet().size)
        assertEquals(i.size, i.map { it.name }.toSet().size)
    }

    @Test
    fun defaultAccountsAndRules() {
        val a = Defaults.accounts()
        assertEquals(5, a.size)
        assertEquals(listOf("现金", "微信", "支付宝", "招商银行", "信用卡"), a.map { it.name })
        assertEquals("cash", a[0].kind)
        assertEquals("credit", a[4].kind)

        val r = Defaults.rules()
        assertEquals(15, r.size)
        // 每条规则的 id 不能撞，撞了后面存回文件会互相顶掉
        assertEquals(r.size, r.map { it.id }.toSet().size)
        assertTrue(r.all { it.enabled })
        assertTrue(r.all { it.keyword.isNotBlank() })
        // 优先级别全是同一个数的话，规则顺序就不确定了
        assertTrue(r.map { it.priority }.toSet().size > 3)
    }

    @Test
    fun dateHelpers() {
        assertEquals(30, Dates.daysInMonth("2026-09"))
        assertEquals(31, Dates.daysInMonth("2026-10"))
        assertEquals(28, Dates.daysInMonth("2026-02"))
        assertEquals(29, Dates.daysInMonth("2024-02"))
        assertEquals("2026-09", Dates.yearMonth("2026-09-15"))
        assertEquals("2026-08", Dates.shiftMonth("2026-09", -1))
        assertEquals("2027-01", Dates.shiftMonth("2026-12", 1))
        assertEquals("2025-12", Dates.shiftMonth("2026-01", -1))
        assertEquals("9月", Dates.monthLabel("2026-09"))
        assertEquals("12月", Dates.monthLabel("2026-12"))
    }

    /**
     * 存下去再读回来，字段一个都不能丢。
     * 这条是替用户把守数据安全的：格式写错一点，用户的账就少一块。
     */
    @Test
    fun jsonRoundTrip() {
        val d = AppData()
        val t = Transaction(
            date = "2026-09-01", time = "12:30", amount = 1234,
            category = "餐饮", subcategory = "午餐", merchant = "瑞幸",
            account = "微信", note = "备注", type = "expense",
            forWhom = "张三", isAdvance = true
        )
        t.hash = Hash.of(t.date, t.amount, t.merchant, t.note, t.account)
        t.tags = mutableListOf("测试", "标签")
        d.transactions.add(t)

        d.accounts.add(Account(name = "现金", kind = "cash", initialBalance = 10000, group = "自己"))
        d.budgets.add(Budget(scope = "餐饮", amount = 200000, alertThreshold = 0.75))
        d.rules.add(Rule(keyword = "瑞幸|星巴克", setCategory = "餐饮", priority = 88))
        d.ledgers.add(Ledger(name = "我", color = "#4A90D9"))
        d.settings.theme = "sakura"
        d.settings.accent = "#D9467A"
        d.settings.fontScale = 110
        d.settings.monthStartDay = 5
        d.settings.expenseCategories = Categories.defaultExpense()
        d.settings.incomeCategories = Categories.defaultIncome()

        val back = AppData.fromJson(JSONObject(d.toJson().toString()))

        assertEquals(1, back.transactions.size)
        val b = back.transactions[0]
        assertEquals(t.id, b.id)
        assertEquals(t.hash, b.hash)
        assertEquals(1234L, b.amount)
        assertEquals("2026-09-01", b.date)
        assertEquals("12:30", b.time)
        assertEquals("餐饮", b.category)
        assertEquals("午餐", b.subcategory)
        assertEquals("瑞幸", b.merchant)
        assertEquals("微信", b.account)
        assertEquals("备注", b.note)
        assertEquals(listOf("测试", "标签"), b.tags)
        assertEquals("张三", b.forWhom)
        assertTrue(b.isAdvance)
        assertEquals(false, b.isReimbursed)

        assertEquals(1, back.accounts.size)
        assertEquals(10000L, back.accounts[0].initialBalance)
        assertEquals("自己", back.accounts[0].group)

        assertEquals(1, back.budgets.size)
        assertEquals(200000L, back.budgets[0].amount)
        assertEquals(0.75, back.budgets[0].alertThreshold, 0.0001)

        assertEquals(1, back.rules.size)
        assertEquals("瑞幸|星巴克", back.rules[0].keyword)
        assertEquals(88, back.rules[0].priority)

        assertEquals(1, back.ledgers.size)
        assertEquals("#4A90D9", back.ledgers[0].color)

        assertEquals("sakura", back.settings.theme)
        assertEquals("#D9467A", back.settings.accent)
        assertEquals(110, back.settings.fontScale)
        assertEquals(5, back.settings.monthStartDay)
        assertEquals(12, back.settings.expenseCategories.size)
        assertEquals(7, back.settings.incomeCategories.size)
        assertEquals("午餐", back.settings.expenseCategories.first { it.name == "餐饮" }.subs[1])
    }

    /** 老数据缺字段读进来也不能崩，缺什么补什么 */
    @Test
    fun tolerantOfMissingFields() {
        val sparse = JSONObject("""{"transactions":[{"date":"2026-01-01","amount":500}],"accounts":[],"settings":{}}""")
        val d = AppData.fromJson(sparse)
        assertEquals(1, d.transactions.size)
        val t = d.transactions[0]
        assertEquals("2026-01-01", t.date)
        assertEquals(500L, t.amount)
        // 缺的字段要有兜底值，不能是 null
        assertEquals("expense", t.type)
        assertEquals("现金", t.account)
        assertEquals("", t.ledgerId)
        assertEquals(false, t.isAdvance)
        assertEquals("light", d.settings.theme)
        assertEquals("¥", d.settings.currency)
    }
}