# BS 服务端打包：前端质量门禁 → 嵌入静态资源 → 后端测试与 fat jar。
param(
    [string]$Version = "3.0.1"
)

$ErrorActionPreference = "Stop"
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "版本号必须使用 X.Y.Z 格式，实际：$Version"
}
$RootPath = $PSScriptRoot
$FrontendPath = Join-Path $RootPath "frontend"
$BackendPath = Join-Path $RootPath "backend"
$StaticPath = Join-Path $BackendPath "src\main\resources\static"

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "缺少命令：$Name"
    }
}

function Invoke-Native([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "命令执行失败（退出码 $LASTEXITCODE）：$Command $($Arguments -join ' ')"
    }
}

Assert-Command "npm"
Assert-Command "mvn"

Write-Host "==> [1/3] 校验并构建前端"
Push-Location $FrontendPath
try {
    Invoke-Native "npm" @("ci")
    Invoke-Native "npm" @("run", "lint")
    Invoke-Native "npm" @("run", "typecheck")
    Invoke-Native "npm" @("run", "test")
    Invoke-Native "npm" @("run", "build")
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath (Join-Path $FrontendPath "dist"))) {
    throw "前端构建成功但未找到产物：$(Join-Path $FrontendPath 'dist')"
}

Write-Host "==> [2/3] 嵌入前端静态资源"
$ExpectedStaticParent = [System.IO.Path]::GetFullPath((Join-Path $BackendPath "src\main\resources"))
$ResolvedStatic = [System.IO.Path]::GetFullPath($StaticPath)
if (-not $ResolvedStatic.StartsWith($ExpectedStaticParent, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "拒绝清理意外路径：$ResolvedStatic"
}
if (Test-Path -LiteralPath $ResolvedStatic) {
    Remove-Item -LiteralPath $ResolvedStatic -Recurse -Force
}
New-Item -ItemType Directory -Path $ResolvedStatic | Out-Null
Copy-Item -Path (Join-Path $FrontendPath "dist\*") -Destination $ResolvedStatic -Recurse -Force

Write-Host "==> [3/3] 验证并打包后端"
Push-Location $RootPath
try {
    Invoke-Native "mvn" @("-B", "-q", "-pl", "backend", "-am", "clean", "verify", "-Drevision=$Version")
} finally {
    Pop-Location
}

$Artifact = Join-Path $BackendPath "target\lyradb-backend-$Version.jar"
if (-not (Test-Path -LiteralPath $Artifact)) {
    throw "构建结束但未找到产物：$Artifact"
}

Write-Host "==> 完成"
Write-Host "产物：$Artifact"
Write-Host "生产启动必须设置 SPRING_PROFILES_ACTIVE=prod、JASYPT_PASSWORD 与 LYRADB_DB_PASSWORD；企业空用户库首次启动还需管理员 bootstrap 变量。"
