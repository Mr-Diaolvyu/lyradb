# 桌面便携版打包脚本（PowerShell）：构建前端 → 嵌入静态资源 → jpackage 应用镜像 → 压缩便携 zip
# 用法：在项目根目录运行  powershell -ExecutionPolicy Bypass -File .\package-desktop.ps1
# 产物：
#   backend\target\desktop\LyraDB\            自带精简 JRE 的应用镜像（双击 LyraDB.exe 即用）
#   backend\target\LyraDB-windows-x64-portable.zip   免安装便携包（解压即用）
param(
    [string]$Version = "1.0.0"
)
$ErrorActionPreference = "Stop"

$ROOT = $PSScriptRoot
$FRONTEND = Join-Path $ROOT "frontend"
$BACKEND = Join-Path $ROOT "backend"
$STATIC = Join-Path $BACKEND "src\main\resources\static"
$IMAGE = Join-Path $BACKEND "target\desktop\LyraDB"
$ZIP = Join-Path $BACKEND "target\LyraDB-$Version-windows-x64-portable.zip"

Write-Host "==> [1/4] 构建前端 (Vue)"
Push-Location $FRONTEND
npm install
npm run build
Pop-Location

Write-Host "==> [2/4] 嵌入前端到后端 static 资源"
if (Test-Path $STATIC) { Remove-Item -Recurse -Force $STATIC }
New-Item -ItemType Directory -Path $STATIC | Out-Null
Copy-Item -Recurse -Force (Join-Path $FRONTEND "dist\*") $STATIC

Write-Host "==> [3/4] 构建后端 fat jar 并 jpackage 生成应用镜像"
Push-Location $BACKEND
mvn -q -Pdesktop clean package -DskipTests
Pop-Location
if (-not (Test-Path (Join-Path $IMAGE "LyraDB.exe"))) {
    throw "jpackage 产物缺失: $IMAGE\LyraDB.exe（请确认 JDK 21 且 jpackage 在 PATH 中）"
}

Write-Host "==> [4/4] 压缩便携 zip"
if (Test-Path $ZIP) { Remove-Item -Force $ZIP }
Compress-Archive -Path $IMAGE -DestinationPath $ZIP -CompressionLevel Optimal

Write-Host "==> 完成"
Write-Host "应用镜像: $IMAGE"
Write-Host "便携包:   $ZIP"
Write-Host ""
Write-Host "使用方式：解压 zip 后双击 LyraDB\LyraDB.exe 即可启动（免安装，自带 JRE）"
