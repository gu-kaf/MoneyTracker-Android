// 顶层构建文件。插件版本集中在这里声明，app 模块只管用。
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// 生成 gradlew 用的。本机连 services.gradle.org 时好时坏，
// 开着 URL 校验会直接把 wrapper 任务判失败。这里关掉，跟构建本身无关。
tasks.wrapper {
    validateDistributionUrl = false
}