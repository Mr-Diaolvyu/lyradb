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
$TargetPath = Join-Path $BackendPath "target"
$StaticPath = Join-Path $BackendPath "src\main\resources\static"
$ImagePath = Join-Path $BackendPath "target\desktop\LyraDB"
$ZipPath = Join-Path $BackendPath "target\LyraDB-$Version-windows-x64-portable.zip"
$WorkspacePath = Join-Path $RootPath "数据架构师工作空间"

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

function Test-DesktopLauncher(
    [string]$Executable,
    [string]$LauncherConfig,
    [string]$MainJar
) {
    if (-not (Test-Path -LiteralPath $LauncherConfig)) {
        throw "jpackage 启动配置缺失：$LauncherConfig"
    }
    $LauncherText = Get-Content -LiteralPath $LauncherConfig -Raw
    if ($LauncherText -notmatch '(?m)^app\.mainjar=') {
        throw "jpackage 启动配置未声明主 JAR"
    }
    if ($LauncherText -match
        '(?m)^app\.mainclass=io\.github\.lexaquila\.lyradb\.LyraDbApplication') {
        throw "jpackage 错误地绕过了 Spring Boot JarLauncher"
    }
    if (-not (Test-Path -LiteralPath $MainJar)) {
        throw "jpackage 主 JAR 缺失：$MainJar"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $Archive = [System.IO.Compression.ZipFile]::OpenRead($MainJar)
    try {
        $ManifestEntry = $Archive.GetEntry("META-INF/MANIFEST.MF")
        if (-not $ManifestEntry) {
            throw "主 JAR 缺少 MANIFEST.MF"
        }
        $Reader = [System.IO.StreamReader]::new($ManifestEntry.Open())
        try {
            $Manifest = $Reader.ReadToEnd()
        } finally {
            $Reader.Dispose()
        }
    } finally {
        $Archive.Dispose()
    }
    if ($Manifest -notmatch
        '(?m)^Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\r?$') {
        throw "主 JAR Manifest 未使用 Spring Boot JarLauncher"
    }

    $ResolvedWorkspace = [System.IO.Path]::GetFullPath($WorkspacePath)
    New-Item -ItemType Directory -Path $ResolvedWorkspace -Force | Out-Null
    $SmokePath = [System.IO.Path]::GetFullPath(
        (Join-Path $ResolvedWorkspace "desktop-launcher-smoke-$PID")
    )
    if (-not $SmokePath.StartsWith(
        $ResolvedWorkspace + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝使用工作区外的冒烟测试目录：$SmokePath"
    }
    if (Test-Path -LiteralPath $SmokePath) {
        Remove-Item -LiteralPath $SmokePath -Recurse -Force
    }
    New-Item -ItemType Directory -Path $SmokePath | Out-Null

    $Listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback, 0
    )
    $Listener.Start()
    try {
        $Port = $Listener.LocalEndpoint.Port
    } finally {
        $Listener.Stop()
    }

    $PreviousH2Path = $env:LYRADB_H2_PATH
    $PreviousKeyPath = $env:LYRADB_DESKTOP_KEY_PATH
    $PreviousLogPath = $env:LOGGING_FILE_NAME
    $env:LYRADB_H2_PATH = Join-Path $SmokePath "data\lyradb"
    $env:LYRADB_DESKTOP_KEY_PATH = Join-Path $SmokePath "master.key"
    $env:LOGGING_FILE_NAME = Join-Path $SmokePath "lyradb.log"
    $Process = $null
    try {
        $StartInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $StartInfo.FileName = $Executable
        $StartInfo.Arguments = (
            "--server.port=$Port --app.desktop.tray-enabled=false"
        )
        $StartInfo.UseShellExecute = $false
        $StartInfo.CreateNoWindow = $true
        $Process = [System.Diagnostics.Process]::new()
        $Process.StartInfo = $StartInfo
        if (-not $Process.Start()) {
            throw "无法启动 LyraDB.exe"
        }

        $Ready = $false
        $Deadline = (Get-Date).AddSeconds(90)
        do {
            $Process.Refresh()
            if ($Process.HasExited) {
                $Process.WaitForExit()
                throw "LyraDB.exe 提前退出，退出码：$($Process.ExitCode)"
            }
            try {
                $Response = Invoke-WebRequest `
                    -Uri "http://127.0.0.1:$port/api/app/info" `
                    -UseBasicParsing `
                    -TimeoutSec 3
                if ($Response.StatusCode -eq 200) {
                    $Ready = $true
                    break
                }
            } catch {
                # 服务启动期间连接失败属于预期，继续轮询。
            }
            Start-Sleep -Seconds 1
        } while ((Get-Date) -lt $Deadline)

        if (-not $Ready) {
            throw "LyraDB.exe 在 90 秒内未通过 HTTP 就绪检查"
        }
        Write-Host "桌面启动验证通过：http://127.0.0.1:$port/api/app/info"
    } catch {
        $LogPath = Join-Path $SmokePath "lyradb.log"
        if (Test-Path -LiteralPath $LogPath) {
            Get-Content -LiteralPath $LogPath -Tail 200
        }
        throw
    } finally {
        if ($Process -and -not $Process.HasExited) {
            $Process.Kill()
            $Process.WaitForExit()
        }
        if ($Process) {
            $Process.Dispose()
        }
        $env:LYRADB_H2_PATH = $PreviousH2Path
        $env:LYRADB_DESKTOP_KEY_PATH = $PreviousKeyPath
        $env:LOGGING_FILE_NAME = $PreviousLogPath
        for ($Attempt = 1;
             $Attempt -le 10 -and (Test-Path -LiteralPath $SmokePath);
             $Attempt++) {
            try {
                Remove-Item -LiteralPath $SmokePath -Recurse -Force -ErrorAction Stop
            } catch {
                if ($Attempt -eq 10) {
                    throw
                }
                Start-Sleep -Milliseconds 500
            }
        }
    }
}

Assert-Command "npm"
Assert-Command "mvn"
Assert-Command "jpackage"

Write-Host "==> [1/5] 校验并构建前端"
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

Write-Host "==> [2/5] 嵌入前端静态资源"
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

Write-Host "==> [3/5] 验证后端并生成桌面应用镜像"
$ResolvedTarget = [System.IO.Path]::GetFullPath($TargetPath)
$ResolvedBackend = [System.IO.Path]::GetFullPath($BackendPath)
if (-not $ResolvedTarget.StartsWith(
    $ResolvedBackend + [System.IO.Path]::DirectorySeparatorChar,
    [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "拒绝清理意外构建路径：$ResolvedTarget"
}
if (Test-Path -LiteralPath $ResolvedTarget) {
    Get-ChildItem -LiteralPath $ResolvedTarget -Recurse -Force -File |
        Where-Object { $_.IsReadOnly } |
        ForEach-Object { $_.IsReadOnly = $false }
}
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

Write-Host "==> [4/5] 验证桌面应用真实启动"
Test-DesktopLauncher `
    -Executable $Executable `
    -LauncherConfig (Join-Path $ImagePath "app\LyraDB.cfg") `
    -MainJar (Join-Path $ImagePath "app\lyradb-backend-$Version.jar")

Write-Host "==> [5/5] 压缩便携包"
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
