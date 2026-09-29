package com.moneytracker.data

/**
 * 出厂默认的账户和自动记账规则。
 * 内容跟电脑版 Models.cs 的 DefaultAccounts / DefaultRules 一致，
 * 这样两边新建的库长得一样。
 */
object Defaults {

    fun accounts(): MutableList<Account> = mutableListOf(
        Account(name = "现金", kind = "cash"),
        Account(name = "微信", kind = "wallet"),
        Account(name = "支付宝", kind = "wallet"),
        Account(name = "招商银行", kind = "debit"),
        Account(name = "信用卡", kind = "credit")
    )

    private data class Def(
        val kw: String, val cat: String, val sub: String,
        val type: String, val pri: Int
    )

    fun rules(): MutableList<Rule> {
        val defs = listOf(
            Def("餐饮|美团|饿了么|肯德基|麦当劳|星巴克|瑞幸|库迪|蜜雪|外卖|餐厅|饭店|食堂|小吃|快餐|烧烤|火锅|米线|面馆|拉面|饺子|包子|料理|寿司|披萨|汉堡|奶茶|咖啡|饮品|早餐|午餐|晚餐|食品|生鲜|水果", "餐饮", "", "expense", 100),
            Def("工资|月薪|薪金|代发|薪水|劳务|奖金|补贴", "工资", "", "income", 95),
            Def("淘宝|天猫|京东|拼多多|唯品会|抖音商城|超市|永辉|盒马|大润发|华润|沃尔玛|便利店|商城|旗舰店|旗舰|日用品|百货", "购物", "", "expense", 90),
            Def("滴滴|高德|花小猪|曹操|地铁|公交|打车|出租车|网约车|12306|铁路|机票|航空|加油|中石化|中石油|停车|高速|高铁|共享单车|单车", "交通", "", "expense", 85),
            Def("爱奇艺|腾讯视频|优酷|芒果|网易云|QQ音乐|Spotify|B站|bilibili|会员|订阅|续费|话费|流量|宽带|电信|联通|移动通信", "通讯", "会员订阅", "expense", 80),
            Def("房租|租金|水费|电费|燃气|天然气|物业|供暖|国家电网|自来水", "居住", "", "expense", 75),
            Def("医院|药房|药店|门诊|体检|挂号|诊所|口腔|牙科", "医疗", "", "expense", 70),
            Def("退款|退货|返现|退还|冲正", "退款", "", "income", 60),
            Def("红包|转账收|微信红包|群收款", "红包", "", "income", 55),
            Def("酒店|民宿|旅馆|携程|去哪儿|飞猪|旅游|门票|景区", "娱乐", "旅游", "expense", 50),
            Def("电影|影城|影院|KTV|游戏|Steam|网吧|剧本杀|密室|演唱会|展|话剧", "娱乐", "电影演出", "expense", 48),
            Def("宠物|猫粮|狗粮|宠物医院|兽医", "宠物", "", "expense", 46),
            Def("学费|培训|课程|网课|书店|图书|当当|考试|报名费|教材", "教育", "", "expense", 44),
            Def("保费|保险|医保|社保|公积金", "金融", "保险", "expense", 42),
            Def("还款|信用卡还款|花呗|借呗|分期|利息|手续费", "金融", "还款", "expense", 40)
        )
        return defs.map {
            Rule(
                id = "rule-" + newId().substring(0, 8),
                keyword = it.kw,
                matchField = "both",
                setCategory = it.cat,
                setSubcategory = it.sub,
                setType = it.type,
                priority = it.pri,
                hitCount = 0,
                enabled = true
            )
        }.toMutableList()
    }

    /**
     * 拿商户名和备注去撞规则，撞上了就返回该用的分类。
     * 判定顺序跟电脑版一样：按 priority 从大到小。
     */
    fun match(text: String): Rule? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        return Store.data.rules
            .filter { it.enabled }
            .sortedByDescending { it.priority }
            .firstOrNull { r ->
                r.keyword.split("|").any { kw ->
                    kw.isNotBlank() && lower.contains(kw.lowercase())
                }
            }
    }
}