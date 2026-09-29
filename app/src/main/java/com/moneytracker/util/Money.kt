package com.moneytracker.util

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 金额、日期、判重——和电脑版算出来的结果必须一模一样，
 * 否则同一笔账在两边的判重指纹不同，导入时会重复计入。
 */
object Money {

    /** 分 -> "1,234.56"。永远两位小数、带千分位，跟电脑版一致。 */
    fun text(cents: Long): String {
        val neg = cents < 0
        val v = abs(cents)
        val yuan = v / 100
        val fen = v % 100
        val s = String.format(Locale.US, "%,d.%02d", yuan, fen)
        return if (neg) "-$s" else s
    }

    /** 带货币符号 */
    fun yuan(cents: Long, currency: String = "¥"): String = currency + text(cents)

    /** 带正负号，报表里用得着 */
    fun signed(cents: Long, currency: String = "¥"): String {
        val sign = if (cents > 0) "+" else if (cents < 0) "-" else ""
        return sign + currency + text(abs(cents))
    }

    /**
     * 把用户敲的字符串解析成分。
     * 认这些写法：12、12.3、12.34、1,234.5、¥12
     * 认不出来返回 null（调用方去提示，不要静默当 0）
     */
    fun parse(input: String): Long? {
        var s = input.trim()
        if (s.isEmpty()) return null
        s = s.replace("¥", "").replace("$", "").replace("€", "").replace(",", "").replace("￥", "").trim()
        if (s.isEmpty() || s == "-" || s == ".") return null
        val neg = s.startsWith("-")
        if (neg) s = s.substring(1)
        // 最多两位小数
        val dot = s.indexOf('.')
        val yuanPart: Long
        val fenPart: Long
        if (dot < 0) {
            yuanPart = s.toLongOrNull() ?: return null
            fenPart = 0
        } else {
            val a = s.substring(0, dot)
            var b = s.substring(dot + 1)
            if (b.length > 2) b = b.substring(0, 2)
            while (b.length < 2) b += "0"
            yuanPart = if (a.isEmpty()) 0 else (a.toLongOrNull() ?: return null)
            fenPart = b.toLongOrNull() ?: return null
        }
        var cents = yuanPart * 100 + fenPart
        return if (neg) -cents else cents
    }
}

object Dates {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    fun today(): String = day.format(Date())
    fun nowTime(): String = clock.format(Date())

    fun parse(s: String): Date? = try { day.parse(s) } catch (e: Exception) { null }

    /** 这个字符串是哪一年哪一月，解析不出来就返回空串 */
    fun yearMonth(dateStr: String): String =
        if (dateStr.length >= 7) dateStr.substring(0, 7) else ""

    /** 往前推几个月，给「上月对比」用 */
    fun shiftMonth(ym: String, delta: Int): String {
        val parts = ym.split("-")
        if (parts.size != 2) return ym
        var y = parts[0].toIntOrNull() ?: return ym
        var m = parts[1].toIntOrNull() ?: return ym
        m += delta
        while (m > 12) { m -= 12; y += 1 }
        while (m < 1) { m += 12; y -= 1 }
        return String.format(Locale.US, "%04d-%02d", y, m)
    }

    /** 这个月有多少天 */
    fun daysInMonth(ym: String): Int {
        val parts = ym.split("-")
        if (parts.size != 2) return 30
        val y = parts[0].toIntOrNull() ?: return 30
        val m = parts[1].toIntOrNull() ?: return 30
        val cal = java.util.Calendar.getInstance()
        // 必须先把「日」钉在 1 号再设年月。Calendar 实例带着当前日期的日，
        // 比如今天是 29 号，设成 2 月就会溢出滚到 3 月，二月算出 31 天。
        cal.set(y, m - 1, 1)
        return cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    }

    /** 中文的「9月」这种写法 */
    fun monthLabel(ym: String): String {
        val parts = ym.split("-")
        if (parts.size != 2) return ym
        val m = parts[1].toIntOrNull() ?: return ym
        return "${m}月"
    }
}

object Hash {
    /**
     * 判重指纹。必须和电脑版 Util.HashOf 算出一模一样的结果，
     * 否则同一笔账在两边指纹不同，导入时会重复计入。
     *
     * 电脑版的原式是：
     *     date + "|" + amount + "|" + (商户为空 ? 备注 : 商户) + "|" + 账户
     * 四段、三个竖线，商户为空时拿备注顶上，而且两边都不做 trim。
     * 别自作聪明改成五段或者加 trim，那样就对不上了。
     */
    fun of(date: String, amount: Long, merchant: String, note: String, account: String): String {
        val mid = if (merchant.isEmpty()) note else merchant
        val raw = date + "|" + amount + "|" + mid + "|" + account
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}