# LyraDB 个人版原生桌面打包：core/desktop 测试 → jpackage → 独立架构扫描 → 原生冒烟 → zip。
param(
    [string]$Version = "3.0.1"
)

$ErrorActionPreference = "Stop"
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "版本号必须使用 X.Y.Z 格式，实际：$Version"
}

$RootPath = $PSScriptRoot
$DesktopPath = Join-Path $RootPath "desktop"
$ImagePath = Join-Path $DesktopPath "target\desktop\LyraDB"
$Executable = Join-Path $ImagePath "LyraDB.exe"
$LauncherConfig = Join-Path $ImagePath "app\LyraDB.cfg"
$ZipPath = Join-Path $DesktopPath "target\LyraDB-$Version-windows-x64-portable.zip"
$WorkspacePath = Join-Path $RootPath "数据架构师工作空间"
$PackageResourcePath = Join-Path $DesktopPath "src\main\jpackage"

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "缺少命令：$Name"
    }
}

function Clear-ReadOnlyBuildArtifacts {
    $targetPath = Join-Path $DesktopPath "target"
    if (-not (Test-Path -LiteralPath $targetPath -PathType Container)) {
        return
    }
    Get-ChildItem -LiteralPath $targetPath -Recurse -Force |
        Where-Object {
            ($_.Attributes -band [System.IO.FileAttributes]::ReadOnly) -ne 0
        } |
        ForEach-Object {
            $_.IsReadOnly = $false
        }
}

function Assert-BrandAssets {
    foreach ($name in @("LyraDB.svg", "LyraDB.ico", "LyraDB.icns", "LyraDB.png")) {
        $path = Join-Path $PackageResourcePath $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "缺少 LyraDB 品牌资源：$path"
        }
    }

    $icoBytes = [System.IO.File]::ReadAllBytes(
        (Join-Path $PackageResourcePath "LyraDB.ico"))
    if ($icoBytes.Length -lt 22 -or
        $icoBytes[0] -ne 0 -or $icoBytes[1] -ne 0 -or
        $icoBytes[2] -ne 1 -or $icoBytes[3] -ne 0 -or
        $icoBytes[4] -lt 5) {
        throw "LyraDB.ico 不是有效的多尺寸 Windows 图标"
    }

    $pngBytes = [System.IO.File]::ReadAllBytes(
        (Join-Path $PackageResourcePath "LyraDB.png"))
    $pngSignature = @(137, 80, 78, 71, 13, 10, 26, 10)
    for ($index = 0; $index -lt $pngSignature.Count; $index++) {
        if ($pngBytes[$index] -ne $pngSignature[$index]) {
            throw "LyraDB.png 签名无效"
        }
    }

    $icnsBytes = [System.IO.File]::ReadAllBytes(
        (Join-Path $PackageResourcePath "LyraDB.icns"))
    if ([System.Text.Encoding]::ASCII.GetString($icnsBytes, 0, 4) -ne "icns") {
        throw "LyraDB.icns 签名无效"
    }
}

function Invoke-Native([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "命令执行失败（退出码 $LASTEXITCODE）：$Command $($Arguments -join ' ')"
    }
}

function Assert-NativeLauncher {
    if (-not (Test-Path -LiteralPath $Executable)) {
        throw "jpackage 原生可执行文件缺失：$Executable"
    }
    if (-not (Test-Path -LiteralPath $LauncherConfig)) {
        throw "jpackage 启动配置缺失：$LauncherConfig"
    }
    $launcherText = Get-Content -LiteralPath $LauncherConfig -Raw
    if ($launcherText -notmatch
        '(?m)^app\.mainclass=io\.github\.lexaquila\.lyradb\.desktop\.NativeDesktopApplication\r?$') {
        throw "启动器未指向原生桌面入口"
    }
    if ($launcherText -match
        'LyraDbApplication|spring\.profiles\.active=desktop|server\.port|JarLauncher') {
        throw "启动器仍包含旧 B/S 桌面包装配置"
    }

    Add-Type -AssemblyName System.Drawing
    $icon = [System.Drawing.Icon]::ExtractAssociatedIcon($Executable)
    if ($null -eq $icon) {
        throw "无法读取 LyraDB.exe 应用图标"
    }
    $bitmap = $icon.ToBitmap()
    try {
        $bluePixels = 0
        $opaquePixels = 0
        for ($x = 0; $x -lt $bitmap.Width; $x++) {
            for ($y = 0; $y -lt $bitmap.Height; $y++) {
                $pixel = $bitmap.GetPixel($x, $y)
                if ($pixel.A -gt 32) {
                    $opaquePixels++
                    if ($pixel.B -gt ($pixel.R + 50) -and
                        $pixel.B -gt ($pixel.G + 50)) {
                        $bluePixels++
                    }
                }
            }
        }
        if ($opaquePixels -eq 0 -or ($bluePixels / $opaquePixels) -lt 0.25) {
            throw "LyraDB.exe 未嵌入预期的蓝色品牌图标"
        }
    } finally {
        $bitmap.Dispose()
        $icon.Dispose()
    }
}
function Assert-NativePackageArchitecture {
    $appPath = Join-Path $ImagePath "app"
    if (-not (Test-Path -LiteralPath $appPath -PathType Container)) {
        throw "jpackage 应用依赖目录缺失：$appPath"
    }

    $desktopJar = Join-Path $appPath "lyradb-desktop-$Version.jar"
    $coreJar = Join-Path $appPath "lyradb-core-$Version.jar"
    foreach ($requiredJar in @($desktopJar, $coreJar)) {
        if (-not (Test-Path -LiteralPath $requiredJar -PathType Leaf)) {
            throw "原生桌面核心 JAR 缺失：$requiredJar"
        }
    }

    $jars = @(Get-ChildItem -LiteralPath $appPath -Filter "*.jar" -File)
    if ($jars.Count -eq 0) {
        throw "jpackage 应用依赖目录中没有 JAR：$appPath"
    }

    $forbiddenJarPattern = (
        '(?i)(^lyradb-backend-|^spring-web(?:mvc|flux)?-|^tomcat-embed-|' +
        '^jetty-|^undertow-|javafx-web|jxbrowser|(?:^|-)cef(?:-|$)|playwright)'
    )
    $forbiddenEntryPattern = (
        '(?im)(^|/)(javafx/scene/web|org/cef|com/teamdev/jxbrowser|' +
        'com/microsoft/playwright)/'
    )
    foreach ($jarFile in $jars) {
        if ($jarFile.Name -match $forbiddenJarPattern) {
            throw "原生桌面镜像包含禁止的 B/S 或嵌入式浏览器依赖：$($jarFile.Name)"
        }
        $entries = & jar tf $jarFile.FullName 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "无法检查 JAR 内容：$($jarFile.FullName)"
        }
        if (($entries -join "`n") -match $forbiddenEntryPattern) {
            throw "原生桌面镜像包含嵌入式浏览器类：$($jarFile.Name)"
        }
    }

    $classPath = Join-Path $appPath "*"
    $jdepsArguments = @(
        "--ignore-missing-deps",
        "--multi-release", "17",
        "-verbose:class",
        "--class-path", $classPath,
        $desktopJar,
        $coreJar
    )
    $jdepsOutput = & jdeps @jdepsArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "jdeps 原生桌面字节码扫描失败，退出码：$LASTEXITCODE"
    }
    $forbiddenDependencyPattern = (
        '(?im)\b(java\.awt\.Desktop|com\.sun\.net\.httpserver\.|' +
        'javafx\.scene\.web\.|org\.cef\.|com\.teamdev\.jxbrowser\.|' +
        'com\.microsoft\.playwright\.|org\.springframework\.boot\.SpringApplication)\b'
    )
    if (($jdepsOutput -join "`n") -match $forbiddenDependencyPattern) {
        throw "原生桌面核心字节码引用了浏览器、本地 HTTP 服务或 B/S 启动入口：$($Matches[1])"
    }
}

function Test-NativeDesktop {
    $resolvedWorkspace = [System.IO.Path]::GetFullPath($WorkspacePath)
    New-Item -ItemType Directory -Path $resolvedWorkspace -Force | Out-Null
    $smokePath = [System.IO.Path]::GetFullPath(
        (Join-Path $resolvedWorkspace "native-desktop-smoke-$PID")
    )
    if (-not $smokePath.StartsWith(
        $resolvedWorkspace + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝使用工作区外的冒烟测试目录：$smokePath"
    }
    if (Test-Path -LiteralPath $smokePath) {
        Remove-Item -LiteralPath $smokePath -Recurse -Force
    }
    New-Item -ItemType Directory -Path $smokePath | Out-Null
    $markerPath = Join-Path $smokePath "native-smoke.json"
    $dataPath = Join-Path $smokePath "data"

    try {
        $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $Executable
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.Arguments = (
            "`"--smoke-test=$markerPath`" `"--data-dir=$dataPath`""
        )
        $process = [System.Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        try {
            if (-not $process.Start()) {
                throw "无法启动原生 LyraDB.exe"
            }
            if (-not $process.WaitForExit(60000)) {
                $process.Kill()
                $process.WaitForExit()
                throw "原生 LyraDB.exe 冒烟测试超时"
            }
            if ($process.ExitCode -ne 0) {
                throw "原生 LyraDB.exe 冒烟测试失败，退出码：$($process.ExitCode)"
            }
        } finally {
            $process.Dispose()
        }

        if (-not (Test-Path -LiteralPath $markerPath)) {
            throw "原生冒烟标记缺失：$markerPath"
        }
        $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
        if ($marker.status -ne "ok" -or
            $marker.architecture -ne "native-swing" -or
            $marker.nativeUiToolkit -ne "javax.swing" -or
            $marker.browserLaunched -ne $false -or
            $marker.webViewEmbedded -ne $false -or
            $marker.localHttpServerStarted -ne $false -or
            $marker.aiConfigAvailable -ne $true -or
            $marker.driverCount -ne 9) {
            throw "原生桌面架构冒烟结果不符合要求：$($marker | ConvertTo-Json -Compress)"
        }
        Write-Host "原生启动验证通过：无浏览器、无 WebView、无本地 HTTP 服务，AI 配置可用。"
    } finally {
        if (Test-Path -LiteralPath $smokePath) {
            Remove-Item -LiteralPath $smokePath -Recurse -Force
        }
    }
}

Assert-Command "mvn"
Assert-Command "jpackage"
Assert-Command "jar"
Assert-Command "jdeps"

Assert-BrandAssets
Clear-ReadOnlyBuildArtifacts
Write-Host "==> [1/5] 运行 core 与原生 desktop 测试并生成应用镜像"
Push-Location $RootPath
try {
    Invoke-Native "mvn" @(
        "-B", "-ntp", "-pl", "desktop", "-am",
        "-Pdesktop-package", "clean", "verify", "-Drevision=$Version"
    )
} finally {
    Pop-Location
}

Write-Host "==> [2/5] 校验 jpackage 原生启动器"
Assert-NativeLauncher

Write-Host "==> [3/5] 独立扫描依赖清单、JAR 内容与核心字节码"
Assert-NativePackageArchitecture

Write-Host "==> [4/5] 执行真实 EXE 原生架构冒烟测试"
Test-NativeDesktop

Write-Host "==> [5/5] 生成 Windows x64 便携包"
if (Test-Path -LiteralPath $ZipPath) {
    Remove-Item -LiteralPath $ZipPath -Force
}
Compress-Archive -Path $ImagePath -DestinationPath $ZipPath -CompressionLevel Optimal
if (-not (Test-Path -LiteralPath $ZipPath)) {
    throw "压缩完成但未找到产物：$ZipPath"
}

Write-Host "==> 完成"
Write-Host "原生应用镜像：$ImagePath"
Write-Host "便携包：$ZipPath"
