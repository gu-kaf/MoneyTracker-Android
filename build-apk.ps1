# 一键编译出手机能装的 APK。
#
# 用法：在这个目录下开 PowerShell，执行
#     .\build-apk.ps1
# 编好后 APK 会复制到 .\apk\ 下面，文件名带日期。
#
# 依赖都在这台机器的 D 盘，不写注册表、不改系统设置：
#     D:\DSH\_androidsdk     Android SDK
#     D:\DSH\_gradle         Gradle 本体和依赖缓存

param(
    [switch]$Release   # 加这个参数编 release 版（体积小、跑得顺），不加就编 debug 版
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$proj    = $PSScriptRoot
$sdk     = 'D:\DSH\_androidsdk'
$gradle  = 'D:\DSH\_gradle\gradle-8.5\bin\gradle.bat'
$gHome   = 'D:\DSH\_gradle\home'

# JDK：优先用能找到的，找不到就报错说清楚
$jdkCandidates = @(
    'C:\Program Files\BellSoft\LibericaJDK-21',
    'C:\Program Files\Java\jdk-17',
    'C:\Program Files\Java\jdk-21'
)
$jdk = $jdkCandidates | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1

Write-Host ''
Write-Host '  记账本 · 安卓版编译' -ForegroundColor Cyan
Write-Host '  ----------------------------------------'

if (-not $jdk) {
    Write-Host '  找不到 JDK。需要 JDK 17 或更高。' -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $gradle)) {
    Write-Host "  找不到 Gradle：$gradle" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $sdk)) {
    Write-Host "  找不到 Android SDK：$sdk" -ForegroundColor Red
    exit 1
}

Write-Host "  JDK      $jdk"
Write-Host "  SDK      $sdk"
Write-Host "  Gradle   $gradle"
Write-Host ''

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:GRADLE_USER_HOME = $gHome
$env:PATH = "$jdk\bin;$env:PATH"

$task = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
$kind = if ($Release) { 'release' } else { 'debug' }

Set-Location $proj
$sw = [System.Diagnostics.Stopwatch]::StartNew()
& $gradle $task --no-daemon --console=plain
$code = $LASTEXITCODE
$sw.Stop()

if ($code -ne 0) {
    Write-Host ''
    Write-Host '  编译失败，上面有报错。' -ForegroundColor Red
    exit $code
}

# 找到编出来的 APK 并复制到一个好找的地方
$out = Join-Path $proj "app\build\outputs\apk\$kind"
$apk = Get-ChildItem $out -Filter '*.apk' -ErrorAction SilentlyContinue |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1

if (-not $apk) {
    Write-Host '  编译说成功了，但没找到 APK，去 app\build\outputs 下面看看。' -ForegroundColor Yellow
    exit 1
}

$dest = Join-Path $proj 'apk'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd'
$name = "MoneyTracker-$kind-$stamp.apk"
Copy-Item $apk.FullName (Join-Path $dest $name) -Force

Write-Host ''
Write-Host "  好了，用时 $([math]::Round($sw.Elapsed.TotalMinutes,1)) 分钟" -ForegroundColor Green
Write-Host "  APK  $dest\$name"
Write-Host "  大小 $([math]::Round((Get-Item (Join-Path $dest $name)).Length/1MB,1)) MB"
Write-Host ''
Write-Host '  装到手机上：把 APK 拷过去点开装，或者手机连电脑后执行'
Write-Host "      & '$sdk\platform-tools\adb.exe' install -r '$dest\$name'"
Write-Host ''