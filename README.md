<div align="center">

# 记账本 · 安卓版

电脑版记账程序的手机版，两边共用同一种数据文件。

![平台](https://img.shields.io/badge/平台-Android-3DDC84?logo=android&logoColor=white)
![语言](https://img.shields.io/badge/语言-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![界面](https://img.shields.io/badge/界面-Jetpack_Compose-4285F4?logo=jetpackcompose&logoColor=white)
![最低版本](https://img.shields.io/badge/最低-Android_7.0-3DDC84)
![第三方依赖](https://img.shields.io/badge/第三方依赖-无-brightgreen)
![外部权限](https://img.shields.io/badge/权限-不申请-blue)
![License](https://img.shields.io/badge/license-MIT-blue)

[电脑版](https://github.com/gu-kaf/MoneyTracker) · [下载电脑版](https://github.com/gu-kaf/MoneyTracker/releases)

</div>

## 一、这是什么

一个安卓记账程序。跟电脑版是同一套东西的手机版，功能一样，数据也互通。

用 Kotlin 和 Jetpack Compose 写的原生程序。不是网页套壳，没有 WebView，没有内嵌浏览器。

## 二、和电脑版的关系

两边读写的都是同一种 db.json，格式完全一致。

手机上导出的文件，电脑版直接能打开；电脑上导出的文件，手机上导入就行。多账本、垫付、二级分类、自动记账规则这些也是同一套字段，不会互相读不懂。

判重指纹用的是同一个算法：日期、金额、商户或备注、账户四段拼起来取 md5。所以同一笔账在两边的指纹一样，互相导入不会重复记账。


## 三、功能

记账

  自绘的数字键盘，不用系统输入法，单手就能记
  支出、收入、转账三种
  分类一层层选，一级平铺，选中后展开二级
  输商户名会自动猜分类，猜错了自己改一下就行
  替别人花的钱可以标成垫付，记下是谁，以后能看到谁还欠着

明细

  本月、上月、近三月、今年、全部，也能自己选一段日期
  按日期分组，每天有当天小计
  搜商户、搜备注、按分类筛
  长按一条能改、能删、能标记已经还了

报表

  环形图看钱都花在哪些分类上，谁占得多一眼看得出来
  分类排行前十
  最近六个月的收支对比柱状图
  每本账的盈亏摆在一起比

预算

  给分类设上限，或者设一个月度总额
  进度条三档颜色，快到上限会变黄，超了变红
  每一项会告诉你按现在的花法到月底够不够
  懒得一个个设的话，有个「按上三月均摊」一键填

分析

  自动读流水，给你十条发现：比上月多花多少、储蓄率、钱主要花在哪、最大一笔是什么、常去哪几家店、工作日和周末哪天更费钱、哪个分类涨得最猛、有没有在重复订阅、照这样月底会花多少、想省钱该动哪一块

设置

  七套主题：浅色、深色、护眼绿、午夜蓝、暖阳、樱花粉、高对比
  八个强调色，也能自己填色号
  字号能调大调小
  能换货币符号，能关掉支出用红色
  账户能分组，自己的和别人的分开摆
  分类能自己加、改名、调顺序，改名的时候历史记录会一起改过来
  备份和回档，改坏了能退回去

## 四、数据在哪

存在程序自己的私有目录里，别的程序看不到：

```
/data/data/com.moneytracker/files/db.json
```

在设置页能看到完整路径。卸载程序会一起删掉，所以换手机之前记得先在设置里导出一份。

每次保存都会顺手留一份 db.json.bak，主文件万一坏了能顶上。备份目录里可以存多份快照，随时退回去。

## 五、不联网、不读你的东西

装的时候不申请任何能碰到你隐私的权限。没有联网权限，没有读通讯录、读相册、读位置、读文件的权限。

AndroidManifest 里没有任何一条自己写的 uses-permission（打包工具会自动塞一条 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION，那是 Android 组件之间通信用来防止外部程序乱插广播的，跟你的数据无关）。

数据不出这台手机。

## 六、文件结构

```
D-android/
    build-apk.ps1              一键编译出 APK
    app/src/main/java/com/moneytracker/
        MainActivity.kt        程序外壳，五个页签加账本切换
        data/
            Models.kt          数据模型，字段名跟电脑版逐字一致
            Store.kt           读写、判重、统计、备份
            Categories.kt      分类体系
            Defaults.kt        默认账户和自动记账规则
        util/
            Money.kt           金额格式化、日期、判重指纹
        ui/
            RecordScreen.kt    记账
            ListScreen.kt      明细
            ReportScreen.kt    报表
            BudgetScreen.kt    预算
            AnalysisScreen.kt  分析
            SettingsScreen.kt  设置
            AccountScreen.kt   账户和垫付
            CategoryScreen.kt  分类管理
            theme/Theme.kt     七套主题
```

## 七、自己改的话

金额一律用「分」存整数，不要用浮点数，不然算多了会有小数误差。电脑版也是这么存的。

取消判重指纹那段别随手改。两边靠它认出同一笔账，算法一变，互相导入就会重复记账。

## License

MIT
