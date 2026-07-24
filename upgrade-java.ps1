# Upgrade local Java environment to JDK 21 (user-scope, reversible)
#
# 说明：本机 Java 8 在 *系统* PATH 中硬编码（D:\Java1.8\jdk1.8.0_361\bin），
# 系统 PATH 优先于用户 PATH，因此仅改用户 PATH 无法切换 `java`/`javac`。
# 本脚本安全地完成「用户级 JAVA_HOME = JDK 21」的升级——这会让 Maven 及所有
# 依据 JAVA_HOME 的工具使用 JDK 21（系统级 JAVA_HOME 被用户级覆盖）。
# 若还需让 `java`/`javac` 直接命令也指向 21，需以管理员身份编辑系统 PATH，
# 移除/替换 D:\Java1.8 条目（注意可能影响依赖 Java 8 的程序，如部分 Navicat）。

$ErrorActionPreference = 'Stop'

$curJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME','User')
if (-not $curJavaHome) { $curJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME','Machine') }
$curPathUser = [Environment]::GetEnvironmentVariable('Path','User')

$backup = "JAVA_HOME=$curJavaHome`r`nUSER_PATH=$curPathUser"
Set-Content -Path 'C:\Users\mr_dly\Desktop\临时目录\env-backup.txt' -Value $backup -Encoding UTF8

Write-Output "BACKUP_SAVED to env-backup.txt"
Write-Output "CURRENT_JAVA_HOME=$curJavaHome"
Write-Output "JAVA21_HOME_EXISTS=$(Test-Path 'D:\jdk-21.0.9')"

$jdk21 = 'D:\jdk-21.0.9'
if (-not (Test-Path $jdk21)) { throw "JDK 21 not found at $jdk21" }

# 设置用户级 JAVA_HOME -> JDK 21（覆盖系统级 Java 8）
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk21, 'User')

Write-Output "JAVA_HOME (User) set to $jdk21"
Write-Output "Maven 等依据 JAVA_HOME 的工具现已使用 JDK 21。"
Write-Output "注意：打开新终端生效；直接 `java -version` 仍显示 Java 8（系统 PATH 优先）。"

