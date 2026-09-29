package com.moneytracker

import com.moneytracker.data.Account
import com.moneytracker.data.Budget
import com.moneytracker.data.Store
import com.moneytracker.data.Transaction
import com.moneytracker.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 库的端到端自检。跑在本机 JVM 上，不需要手机。
 *
 * 这一组的重点是拿一份真实账本走完整链路：读进来、算汇总、算账户余额、
 * 存回去再读一遍。用户最怕的是「数据读丢了」或者「算错钱」，
 * 这些都不能只靠编译通过来判断，得真跑。
 *
 * resources/real-db.json 是本机真实账本，含真实商户名，不提交到仓库。
 * 文件不存在时整组跳过。
 */
class StoreEndToEndTest {

    private fun sample(): File? =
        File("src/test/resources/real-db.json").takeIf { it.exists() }

    private fun tmpDir(tag: String): File =
        Files.createTempDirectory("mt-$tag").toFile().also { it.deleteOnExit() }

    /** 把真实账本铺到一个临时目录里，当作用户的手机 */
    private fun setUpWithReal(tag: String): Boolean {
        val src = sample() ?: return false
        val dir = tmpDir(tag)
        src.copyTo(File(dir, "db.json"), overwrite = true)
        Store.initAt(dir.absolutePath)
        return true
    }

    @Test
    fun readsRealLedgerWithoutLosingAnything() {
        if (!setUpWithReal("read")) { println("没有 real-db.json，跳过"); return }

        assertEquals("记录条数对不上，说明有记录读丢了", 169, Store.data.transactions.size)
        assertEquals("账户读丢了", 5, Store.data.accounts.size)
        assertEquals("预算读丢了", 5, Store.data.budgets.size)
        assertEquals("自动记账规则读丢了", 15, Store.data.rules.size)

        // 每条都必须有指纹，否则没法判重
        assertTrue(
            "有记录的指纹是空的",
            Store.data.transactions.all { it.hash.isNotEmpty() }
        )

        // 日期范围
        val dates = Store.data.transactions.map { it.date }.sorted()
        assertEquals("2026-06-01", dates.first())
        assertEquals("2026-09-27", dates.last())

        // 账户名一个不少
        val names = Store.data.accounts.map { it.name }.toSet()
        assertEquals(setOf("现金", "微信", "支付宝", "招商银行", "信用卡"), names)
    }

    @Test
    fun summariesMatchTheRealNumbers() {
        if (!setUpWithReal("sum")) { println("没有 real-db.json，跳过"); return }

        val all = Store.data.transactions
        val s = Store.summary(all)
        assertEquals("支出合计算错了", 20800_30L, s.expense)
        assertEquals("收入合计算错了", 51316_62L, s.income)
        assertEquals("笔数算错了", 169, s.count)

        // 分类汇总的合计必须等于支出总额，一条都不能漏
        val byCat = Store.byCategory(all)
        assertEquals("按分类汇总和总额对不上，说明有记录没归到分类里",
            s.expense, byCat.sumOf { it.second })
        assertTrue("分类应该按金额从多到少排", byCat[0].second >= byCat.last().second)

        // 按月汇总同理
        val byMonth = Store.byMonth(all)
        assertEquals("按月汇总的支出合计对不上", s.expense, byMonth.sumOf { it.third })
        assertEquals("按月汇总的收入合计对不上", s.income, byMonth.sumOf { it.second })
        assertTrue("月份应该按时间排好", byMonth.size >= 3)
    }

    @Test
    fun saveAndReloadKeepsEveryRecord() {
        if (!setUpWithReal("roundtrip")) { println("没有 real-db.json，跳过"); return }

        val countBefore = Store.data.transactions.size
        val expBefore = Store.summary(Store.data.transactions).expense
        val hashBefore = Store.data.transactions.map { it.id to it.hash }.toMap()

        Store.save()

        // 备份文件应该留下来了
        val bak = File(Store.filePath).parentFile?.let { File(it, "db.json.bak") }
        assertTrue("保存时没有留下备份文件", bak != null && bak.exists())

        // 假装重启程序，重新读一遍
        Store.initAt(File(Store.filePath).parentFile!!.absolutePath)

        assertEquals("存回去再读，记录数变了", countBefore, Store.data.transactions.size)
        assertEquals("存回去再读，支出合计变了", expBefore, Store.summary(Store.data.transactions).expense)
        assertEquals("存回去再读，指纹变了", hashBefore, Store.data.transactions.map { it.id to it.hash }.toMap())
    }

    @Test
    fun addingTwiceCountsOnce() {
        if (!setUpWithReal("dup")) { println("没有 real-db.json，跳过"); return }

        // 拿真实账本里已有的一条，原样再加一次，必须被判重挡住
        val existing = Store.data.transactions.first()
        val copy = Transaction(
            date = existing.date,
            amount = existing.amount,
            merchant = existing.merchant,
            note = existing.note,
            account = existing.account,
            category = existing.category
        )
        val before = Store.data.transactions.size
        assertFalse("同一笔账应该被判成重复", Store.add(copy))
        assertEquals("判重没挡住，多记了一笔", before, Store.data.transactions.size)

        // 金额改一个分就不是同一笔了
        val different = Transaction(
            date = existing.date,
            amount = existing.amount + 1,
            merchant = existing.merchant,
            note = existing.note,
            account = existing.account,
            category = existing.category
        )
        assertTrue("金额不同不该判成重复", Store.add(different))
        assertEquals(before + 1, Store.data.transactions.size)
    }

    @Test
    fun accountBalanceAddsUp() {
        val dir = tmpDir("bal")
        Store.initAt(dir.absolutePath)

        // 造个干净的局面，自己算得清
        Store.data.transactions.clear()
        Store.data.accounts.clear()
        Store.data.accounts.add(Account(name = "现金", kind = "cash", initialBalance = 100_00L))
        Store.data.accounts.add(Account(name = "信用卡", kind = "credit", initialBalance = 0))
        Store.save()

        assertTrue(Store.add(Transaction(
            date = "2026-09-01", amount = 25_00L, account = "现金",
            category = "餐饮", merchant = "午饭", type = "expense")))
        assertTrue(Store.add(Transaction(
            date = "2026-09-05", amount = 500_00L, account = "现金",
            category = "工资", type = "income")))
        // 转账：现金出去，信用卡进账
        assertTrue(Store.add(Transaction(
            date = "2026-09-06", amount = 100_00L, account = "现金", toAccount = "信用卡",
            category = "转账", type = "transfer")))

        // 现金 = 100 - 25 + 500 - 100 = 475 元
        assertEquals(475_00L, Store.accountBalance("现金"))
        // 信用卡 = 0 + 100 = 100 元
        assertEquals(100_00L, Store.accountBalance("信用卡"))
    }

    @Test
    fun budgetProgressCountsOnlyTheRightMonth() {
        val dir = tmpDir("budget")
        Store.initAt(dir.absolutePath)
        Store.data.transactions.clear()
        Store.save()

        val thisMonth = Dates.yearMonth(Dates.today())
        val lastMonth = Dates.shiftMonth(thisMonth, -1)

        Store.add(Transaction(date = "$thisMonth-01", amount = 30_00L, account = "现金", category = "餐饮"))
        Store.add(Transaction(date = "$thisMonth-02", amount = 20_00L, account = "现金", category = "交通"))
        // 上个月的，不该算进来
        Store.add(Transaction(date = "$lastMonth-10", amount = 999_00L, account = "现金", category = "餐饮"))

        val total = Store.budgetProgress(Budget(scope = "total", amount = 1000_00L))
        assertEquals("总预算的已花算错了", 50_00L, total.first)
        assertEquals(1000_00L, total.second)

        val food = Store.budgetProgress(Budget(scope = "餐饮", amount = 500_00L))
        assertEquals("餐饮预算的已花算错了", 30_00L, food.first)

        // 标了不计入预算的，要跳过
        val t = Store.data.transactions.first { it.category == "交通" }
        t.excludeFromBudget = true
        Store.save()
        val after = Store.budgetProgress(Budget(scope = "total", amount = 1000_00L))
        assertEquals("不计入预算的记录被算进去了", 30_00L, after.first)
    }

    @Test
    fun ledgerSeparatesPeople() {
        val dir = tmpDir("ledger")
        Store.initAt(dir.absolutePath)
        Store.data.transactions.clear()
        Store.save()

        val me = Store.currentLedger()
        val other = Store.addLedger("老婆", "#D9467A")

        Store.add(Transaction(date = "2026-09-01", amount = 10_00L, account = "现金", category = "餐饮", merchant = "我的"))
        Store.switchLedger(other.id)
        Store.add(Transaction(date = "2026-09-01", amount = 20_00L, account = "现金", category = "餐饮", merchant = "她的"))

        assertEquals("她的账本应该只有 1 笔", 1, Store.transactions().size)
        assertEquals(20_00L, Store.summary(Store.transactions()).expense)

        Store.switchLedger(me.id)
        assertEquals("我的账本应该只有 1 笔", 1, Store.transactions().size)
        assertEquals(10_00L, Store.summary(Store.transactions()).expense)

        // 两个账本的记录合计是 2 笔：账本是筛视图，不是各存一份
        assertEquals(2, Store.data.transactions.size)

        // 同一天同金额的两笔，分属不同账本，不该互相判重
        Store.switchLedger(other.id)
        assertTrue(Store.add(Transaction(
            date = "2026-09-01", amount = 10_00L, account = "现金",
            category = "餐饮", merchant = "我的")))
    }

    @Test
    fun backupAndRestoreRoundTrip() {
        if (!setUpWithReal("backup")) { println("没有 real-db.json，跳过"); return }

        val before = Store.data.transactions.size
        val name = Store.snapshot("测试用快照")
        assertTrue("快照文件应该写出来", File(Store.backupsPath, name).exists())
        assertEquals(1, Store.snapshots().size)

        // 删一条再回档，应该回来
        Store.delete(Store.data.transactions.first().id)
        assertEquals(before - 1, Store.data.transactions.size)

        assertTrue("回档应该成功", Store.restore(name))
        assertEquals("回档后记录数没回来", before, Store.data.transactions.size)
    }

    @Test
    fun deletingAccountWithRecordsIsRefused() {
        val dir = tmpDir("delacct")
        Store.initAt(dir.absolutePath)
        Store.data.transactions.clear()
        Store.data.accounts.clear()
        Store.data.accounts.add(Account(name = "现金", kind = "cash", initialBalance = 100_00L))
        Store.data.accounts.add(Account(name = "旧卡", kind = "debit", initialBalance = 50_00L))
        Store.save()

        Store.add(Transaction(date = "2026-09-01", amount = 30_00L, account = "旧卡", category = "餐饮"))

        assertEquals(1, Store.accountUsage("旧卡"))
        assertEquals(0, Store.accountUsage("现金"))
        val oldId = Store.data.accounts.first { it.name == "旧卡" }.id

        // 名下有记录时真删必须被拒。真删掉的话这些账会挂在一个不存在的账户上，
        // accountBalance 找不到账户返回 0，这 30 块就在净资产里凭空消失了。
        assertFalse("名下还有记录时不该允许真删", Store.deleteAccount(oldId, reallyDelete = true))
        assertTrue("账户不该被删掉", Store.data.accounts.any { it.name == "旧卡" })
        assertEquals("余额不能变", 20_00L, Store.accountBalance("旧卡"))

        // 归档是可以的
        assertTrue(Store.deleteAccount(oldId))
        assertFalse("归档后不该再出现在选择列表里", Store.accountNames().contains("旧卡"))
        assertEquals("归档之后钱还是算得出来，不能丢", 20_00L, Store.accountBalance("旧卡"))

        // 一笔记录都没有的账户才允许真删
        Store.data.accounts.add(Account(name = "空卡", kind = "debit"))
        Store.save()
        val emptyId = Store.data.accounts.first { it.name == "空卡" }.id
        assertTrue(Store.deleteAccount(emptyId, reallyDelete = true))
        assertFalse(Store.data.accounts.any { it.name == "空卡" })
    }

    @Test
    fun emptyDatabaseStillWorks() {
        val dir = tmpDir("empty")
        Store.initAt(dir.absolutePath)

        // 全新安装：应该有默认账户、默认规则、一个默认账本、一套默认分类
        assertEquals(5, Store.data.accounts.size)
        assertEquals(15, Store.data.rules.size)
        assertEquals(1, Store.data.ledgers.size)
        assertEquals(12, Store.data.settings.expenseCategories.size)
        assertEquals(0, Store.data.transactions.size)

        // 空库上算什么都得给出 0，不能崩
        assertEquals(0L, Store.summary(Store.transactions()).expense)
        assertEquals(0, Store.byCategory(Store.transactions()).size)
        assertEquals(0L, Store.accountBalance("现金"))
        assertEquals(0L, Store.netAssets())

        // 记一笔之后余额要对
        Store.add(Transaction(date = Dates.today(), amount = 12_34L, account = "现金", category = "餐饮"))
        assertEquals(-12_34L, Store.accountBalance("现金"))
        assertNotEquals(0L, Store.netAssets())
    }
}