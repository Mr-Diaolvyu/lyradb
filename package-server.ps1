# BS 架构版本·服务端打包脚本（PowerShell）：构建前端 → 嵌入静态资源 → 打包可独立部署的 fat jar
# 用法：在项目根目录运行  powershell -ExecutionPolicy Bypass -File .\package-server.ps1
# 产物：backend\target\lyradb-backend-1.0.0-SNAPSHOT.jar （内嵌前端，浏览器访问 http://<host>:8080 即用）
$ErrorActionPreference = "Stop"

$ROOT = $PSScriptRoot
$FRONTEND = Join-Path $ROOT "frontend"
$BACKEND = Join-Path $ROOT "backend"
$STATIC = Join-Path $BACKEND "src\main\resources\static"

Write-Host "==> [1/3] 构建前端 (Vue)"
Push-Location $FRONTEND
npm install
npm run build
Pop-Location

Write-Host "==> [2/3] 嵌入前端到后端 static 资源"
if (Test-Path $STATIC) { Remove-Item -Recurse -Force $STATIC }
New-Item -ItemType Directory -Path $STATIC | Out-Null
Copy-Item -Recurse -Force (Join-Path $FRONTEND "dist\*") $STATIC

Write-Host "==> [3/3] 打包后端 fat jar"
Push-Location $BACKEND
mvn -q clean package -DskipTests
Pop-Location

Write-Host "==> 完成"
Write-Host "产物: $BACKEND\target\lyradb-backend-1.0.0-SNAPSHOT.jar"
Write-Host ""
Write-Host "启动示例："
Write-Host "  # 个人版（默认）"
Write-Host "  java -jar $BACKEND\target\lyradb-backend-1.0.0-SNAPSHOT.jar"
Write-Host "  # 企业版"
Write-Host "  `$env:LYRADB_EDITION='enterprise'; java -jar $BACKEND\target\lyradb-backend-1.0.0-SNAPSHOT.jar"
Write-Host ""
Write-Host "启动后浏览器访问 http://localhost:8080 （企业版默认账号 admin/admin，请立即改密）"
