# 桌面便携版打包：前端质量门禁 → 后端测试 → jpackage → zip。
param(
    [string]$Version = "3.0.0"
)

$ErrorActionPreference = "Stop"
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "版本号必须使用 X.Y.Z 格式，实际：$Version"
}
$RootPath = $PSScriptRoot
$FrontendPath = Join-Path $RootPath "frontend"
$BackendPath = Join-Path $RootPath "backend"
$StaticPath = Join-Path $BackendPath "src\main\resources\static"
$ImagePath = Join-Path $BackendPath "target\desktop\LyraDB"
$ZipPath = Join-Path $BackendPath "target\LyraDB-$Version-windows-x64-portable.zip"

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
Assert-Command "jpackage"

Write-Host "==> [1/4] 校验并构建前端"
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

Write-Host "==> [2/4] 嵌入前端静态资源"
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

Write-Host "==> [3/4] 验证后端并生成桌面应用镜像"
Push-Location $BackendPath
try {
    Invoke-Native "mvn" @("-B", "-q", "-Pdesktop", "clean", "verify", "-Drevision=$Version")
} finally {
    Pop-Location
}

$Executable = Join-Path $ImagePath "LyraDB.exe"
if (-not (Test-Path -LiteralPath $Executable)) {
    throw "jpackage 产物缺失：$Executable。请确认 Maven 使用的 JDK 包含 jpackage。"
}

Write-Host "==> [4/4] 压缩便携包"
if (Test-Path -LiteralPath $ZipPath) {
    Remove-Item -LiteralPath $ZipPath -Force
}
Compress-Archive -Path $ImagePath -DestinationPath $ZipPath -CompressionLevel Optimal
if (-not (Test-Path -LiteralPath $ZipPath)) {
    throw "压缩完成但未找到产物：$ZipPath"
}

Write-Host "==> 完成"
Write-Host "应用镜像：$ImagePath"
Write-Host "便携包：$ZipPath"
