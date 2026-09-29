package com.moneytracker.data

/**
 * 分类体系。出厂有一套默认的，用户改了之后以 settings 里那份为准。
 * 默认表跟电脑版 Models.cs 里的 Categories.DefaultExpense / DefaultIncome 一一对应。
 */
object Categories {

    fun defaultExpense(): MutableList<CategoryGroup> = mutableListOf(
        CategoryGroup(name = "餐饮", subs = mutableListOf("早餐", "午餐", "晚餐", "外卖", "零食饮料", "聚餐")),
        CategoryGroup(name = "交通", subs = mutableListOf("公交地铁", "打车", "加油", "停车", "火车飞机")),
        CategoryGroup(name = "购物", subs = mutableListOf("日用品", "服饰", "数码", "家居", "美妆")),
        CategoryGroup(name = "居住", subs = mutableListOf("房租房贷", "水电燃气", "物业", "维修")),
        CategoryGroup(name = "娱乐", subs = mutableListOf("电影演出", "游戏", "旅行", "运动健身", "订阅会员")),
        CategoryGroup(name = "医疗", subs = mutableListOf("门诊", "药品", "体检", "保险")),
        CategoryGroup(name = "教育", subs = mutableListOf("书籍", "课程", "培训", "文具")),
        CategoryGroup(name = "通讯", subs = mutableListOf("话费", "宽带", "会员订阅")),
        CategoryGroup(name = "人情", subs = mutableListOf("红包", "礼物", "请客", "孝敬")),
        CategoryGroup(name = "宠物", subs = mutableListOf("宠物食品", "宠物医疗", "宠物用品")),
        CategoryGroup(name = "金融", subs = mutableListOf("手续费", "利息", "税费", "罚款")),
        CategoryGroup(name = "其他支出")
    )

    fun defaultIncome(): MutableList<CategoryGroup> = mutableListOf(
        CategoryGroup(name = "工资", subs = mutableListOf("月薪", "奖金", "加班费")),
        CategoryGroup(name = "兼职", subs = mutableListOf("外快", "稿费", "接单")),
        CategoryGroup(name = "投资", subs = mutableListOf("利息", "分红", "理财收益")),
        CategoryGroup(name = "红包", subs = mutableListOf("亲友", "平台红包")),
        CategoryGroup(name = "报销", subs = mutableListOf("差旅", "办公")),
        CategoryGroup(name = "退款", subs = mutableListOf("退货", "返现")),
        CategoryGroup(name = "其他收入")
    )

    /** 当前生效的支出分类（用户改过就是他自己的那套） */
    fun expenses(): MutableList<CategoryGroup> {
        val mine = Store.data.settings.expenseCategories
        return if (mine.isNotEmpty()) mine else defaultExpense()
    }

    /** 当前生效的收入分类 */
    fun incomes(): MutableList<CategoryGroup> {
        val mine = Store.data.settings.incomeCategories
        return if (mine.isNotEmpty()) mine else defaultIncome()
    }

    fun names(type: String): List<String> =
        (if (type == "income") incomes() else expenses()).map { it.name }

    /** 某个一级分类下面的二级分类 */
    fun subsOf(category: String, type: String): List<String> {
        val list = if (type == "income") incomes() else expenses()
        return list.firstOrNull { it.name == category }?.subs ?: emptyList()
    }

    /** 第一次启动把出厂默认灌进设置，之后以设置里那份为准 */
    fun ensure() {
        val s = Store.data.settings
        if (s.expenseCategories.isEmpty()) s.expenseCategories = defaultExpense()
        if (s.incomeCategories.isEmpty()) s.incomeCategories = defaultIncome()
    }

    /** 外部账单里的分类名映射到我们这套，认不出来落「其他」 */
    fun normalize(raw: String, type: String): String {
        if (raw.isBlank()) return if (type == "income") "其他收入" else "其他支出"
        val s = raw.trim()
        val table = mapOf(
            "餐饮美食" to "餐饮", "餐饮" to "餐饮", "外卖" to "餐饮", "食品酒水" to "餐饮",
            "交通出行" to "交通", "交通" to "交通", "打车" to "交通", "公共交通" to "交通",
            "购物" to "购物", "日用百货" to "购物", "服饰装扮" to "购物", "数码电器" to "购物",
            "居住" to "居住", "房租房贷" to "居住", "水电煤" to "居住",
            "文化休闲" to "娱乐", "娱乐" to "娱乐", "运动户外" to "娱乐", "订阅服务" to "通讯",
            "医疗健康" to "医疗", "医疗" to "医疗", "保险" to "医疗",
            "教育培训" to "教育", "教育" to "教育", "学习" to "教育",
            "通讯物流" to "通讯", "通讯" to "通讯", "话费" to "通讯",
            "人情往来" to "人情", "人情" to "人情", "红包" to "人情",
            "宠物" to "宠物", "宠物宝贝" to "宠物",
            "金融保险" to "金融", "金融" to "金融",
            "转账" to "其他支出", "其他" to "其他支出",
            "工资" to "工资", "收入" to "其他收入", "退款" to "退款", "投资收益" to "投资"
        )
        table[s]?.let { return it }
        for (n in names(type)) if (s.contains(n) || n.contains(s)) return n
        return if (type == "income") "其他收入" else "其他支出"
    }
}