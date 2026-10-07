[CmdletBinding()]
param(
    [ValidateSet("Deploy", "Start", "Stop", "Restart", "Status")]
    [string]$Action = "Deploy",

    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA "yt-dlp-java"),
    [string]$OutputDir,
    [string]$CookiesPath,
    [string]$NodePath,
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [string]$BindAddress = "127.0.0.1",
    [ValidatePattern("^\d+[mMgG]$")]
    [string]$MaxHeap = "1g",
    [ValidateRange(1, 65535)]
    [int]$PotPort = 49300,
    [ValidateRange(5, 180)]
    [int]$HealthTimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"
$ProjectDir = $PSScriptRoot
$JarNamePattern = "yt-dlp-java-*.jar"
$RuntimeFiles = @("yt-dlp.exe", "ffmpeg.exe", "ffprobe.exe", "bgutil-pot.exe")
$PluginSource = Join-Path $ProjectDir "src\main\resources\yt-dlp-plugins"

if (-not $OutputDir) {
    $OutputDir = Join-Path $InstallDir "downloads"
}
if (-not $CookiesPath) {
    $CookiesPath = Join-Path $InstallDir "cookies.txt"
}

$InstallDir = [IO.Path]::GetFullPath($InstallDir)
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
$CookiesPath = [IO.Path]::GetFullPath($CookiesPath)
$RunDir = Join-Path $InstallDir "run"
$LogDir = Join-Path $InstallDir "logs"
$PidFile = Join-Path $RunDir "yt-dlp-java.pid"
$JarPath = $null

function Get-ManagedProcess {
    if (-not (Test-Path -LiteralPath $PidFile)) {
        return $null
    }
    $rawPid = (Get-Content -LiteralPath $PidFile -Raw).Trim()
    $processId = 0
    if (-not [int]::TryParse($rawPid, [ref]$processId)) {
        throw "Invalid PID file: $PidFile"
    }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    if (-not $process) {
        Remove-Item -LiteralPath $PidFile -Force
        return $null
    }
    $expectedJarPrefix = Join-Path $InstallDir "yt-dlp-java-"
    if (-not $process.CommandLine -or
            $process.CommandLine.IndexOf($expectedJarPrefix, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "PID $processId is not the application started by this script. Refusing to stop an unrelated process."
    }
    return $process
}

function Get-ProcessDescendants {
    param([int]$ParentId)
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $ParentId" -ErrorAction SilentlyContinue)
    foreach ($child in $children) {
        Get-ProcessDescendants -ParentId ([int]$child.ProcessId)
        $child
    }
}

function Stop-ManagedProcess {
    $managed = Get-ManagedProcess
    if (-not $managed) {
        Write-Host "The service is not running."
        return
    }
    $processId = [int]$managed.ProcessId
    Write-Host "Stopping service process $processId ..."
    $descendants = @(Get-ProcessDescendants -ParentId $processId)
    foreach ($child in $descendants) {
        Write-Host "Stopping application child process $($child.ProcessId) ($($child.Name)) ..."
        Stop-Process -Id ([int]$child.ProcessId) -Force -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $processId
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Process -Id $processId -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
    }
    if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
        Write-Warning "The process did not exit within 20 seconds. Forcing it to stop may interrupt active downloads."
        Stop-Process -Id $processId -Force
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    Write-Host "The service has stopped."
}

function Get-JavaExecutable {
    $candidate = $null
    if ($env:JAVA_HOME) {
        $javaHomeCandidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path -LiteralPath $javaHomeCandidate) {
            $candidate = $javaHomeCandidate
        }
    }
    if (-not $candidate) {
        $command = Get-Command "java.exe" -ErrorAction SilentlyContinue
        if (-not $command) {
            throw "Java was not found. Install JDK 17+ or set JAVA_HOME."
        }
        $candidate = $command.Source
    }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $candidate
    $startInfo.Arguments = "-version"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    $probe = New-Object System.Diagnostics.Process
    $probe.StartInfo = $startInfo
    $null = $probe.Start()
    $versionOutput = $probe.StandardOutput.ReadToEnd() + $probe.StandardError.ReadToEnd()
    $probe.WaitForExit()
    if ($probe.ExitCode -ne 0 -or $versionOutput -notmatch 'version\s+"?(\d+)') {
        throw "Could not read the Java version: $candidate"
    }
    if ([int]$Matches[1] -lt 17) {
        throw "Java 17+ is required. Current runtime: $versionOutput"
    }
    return $candidate
}

function Get-NodeExecutable {
    if ($NodePath) {
        $candidate = [IO.Path]::GetFullPath($NodePath)
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "The specified Node.js executable does not exist: $candidate"
        }
    } else {
        $command = Get-Command "node.exe" -ErrorAction SilentlyContinue
        if (-not $command) {
            throw "Node.js was not found. Install Node.js 20.19+ or specify node.exe with -NodePath."
        }
        $candidate = $command.Source
    }
    $versionOutput = & $candidate --version
    if ($LASTEXITCODE -ne 0 -or $versionOutput -notmatch "^v?(\d+)\.(\d+)") {
        throw "Could not read the Node.js version: $candidate"
    }
    $nodeMajor = [int]$Matches[1]
    $nodeMinor = [int]$Matches[2]
    $supportedVersion = ($nodeMajor -eq 20 -and $nodeMinor -ge 19) -or
        ($nodeMajor -eq 22 -and $nodeMinor -ge 12) -or
        $nodeMajor -gt 22
    if (-not $supportedVersion) {
        throw "Node.js 20.19+ or 22.12+ is required. Current version: $versionOutput"
    }
    return $candidate
}

function ConvertTo-NativeArgument {
    param([string]$Value)
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    $escaped = $Value -replace '(\\*)"', '$1$1\"'
    $escaped = $escaped -replace '(\\+)$', '$1$1'
    return '"' + $escaped + '"'
}

function Get-ApplicationJar {
    $jars = @(Get-ChildItem -LiteralPath $InstallDir -Filter $JarNamePattern -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending)
    if ($jars.Count -eq 0) {
        throw "No runnable application JAR was found in: $InstallDir"
    }
    if ($jars.Count -gt 1) {
        throw "Multiple application JARs were found. Remove old versions from: $InstallDir"
    }
    return $jars[0].FullName
}

function Test-ServiceHealth {
    param([int]$TimeoutSeconds)
    $healthUrl = "http://127.0.0.1:$Port/api/health"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $managed = Get-ManagedProcess
        if (-not $managed) {
            throw "The service process exited. Check logs in: $LogDir"
        }
        try {
            $response = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200 -and $response.Content.Trim() -eq "OK") {
                Write-Host "Health check passed: $healthUrl"
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "Timed out waiting for the health check. Check logs in: $LogDir"
}

function Start-ManagedProcess {
    $existing = Get-ManagedProcess
    if ($existing) {
        throw "The service is already running (PID $($existing.ProcessId)). Use -Action Restart to restart it."
    }
    $script:JarPath = Get-ApplicationJar
    $java = Get-JavaExecutable
    $node = Get-NodeExecutable

    New-Item -ItemType Directory -Force -Path $RunDir, $LogDir, $OutputDir | Out-Null
    $stdout = Join-Path $LogDir "app.out.log"
    $stderr = Join-Path $LogDir "app.err.log"
    $arguments = @(
        "-Xms256m",
        "-Xmx$MaxHeap",
        "-jar",
        $JarPath,
        "--server.address=$BindAddress",
        "--server.port=$Port",
        "--app.open-browser=false",
        "--ytdlp.output.dir=$OutputDir",
        "--ytdlp.bin.yt-dlp=$(Join-Path $InstallDir 'yt-dlp.exe')",
        "--ytdlp.bin.ffmpeg=$(Join-Path $InstallDir 'ffmpeg.exe')",
        "--ytdlp.bin.cookies=$CookiesPath",
        "--ytdlp.bin.node=$node",
        "--ytdlp.pot.path=$(Join-Path $InstallDir 'bgutil-pot.exe')",
        "--ytdlp.pot.port=$PotPort"
    )

    Write-Host "Starting service on $BindAddress port $Port ..."
    $quotedArguments = @($arguments | ForEach-Object { ConvertTo-NativeArgument $_ })
    $process = Start-Process -FilePath $java -ArgumentList $quotedArguments -WorkingDirectory $InstallDir `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $PidFile -Value $process.Id -Encoding ascii
    Test-ServiceHealth -TimeoutSeconds $HealthTimeoutSeconds

    if (-not (Test-Path -LiteralPath $CookiesPath)) {
        Write-Warning "Cookies file not found: $CookiesPath. Public videos may work, but login-restricted videos may not."
    }
    if ($BindAddress -eq "127.0.0.1" -or $BindAddress -eq "localhost") {
        Write-Host "The service is bound to localhost. Use a trusted reverse proxy with HTTPS and access control for remote access."
    } else {
        Write-Warning "The service is bound to $BindAddress and has no built-in authentication. Do not expose it directly to the public internet."
    }
    Write-Host "Service is online: http://127.0.0.1:$Port/"
    Write-Host "Log directory: $LogDir"
}

function Publish-Application {
    $maven = Get-Command "mvn.cmd" -ErrorAction SilentlyContinue
    if (-not $maven) {
        $maven = Get-Command "mvn" -ErrorAction SilentlyContinue
    }
    if (-not $maven) {
        throw "Maven was not found. Install Maven 3.8+ and add mvn to PATH."
    }

    $npm = Get-Command "npm.cmd" -ErrorAction SilentlyContinue
    if (-not $npm) {
        $npm = Get-Command "npm" -ErrorAction SilentlyContinue
    }
    if (-not $npm) {
        throw "npm was not found. Install Node.js 20.19+ and add npm to PATH."
    }

    $node = Get-NodeExecutable
    $previousPath = $env:PATH
    $env:PATH = "$(Split-Path -Parent $node);$previousPath"
    Push-Location $ProjectDir
    try {
        Write-Host "Installing frontend dependencies and building the Vue application..."
        & $npm.Source --prefix (Join-Path $ProjectDir "frontend") ci
        if ($LASTEXITCODE -ne 0) {
            throw "Frontend dependency installation failed with exit code $LASTEXITCODE."
        }
        & $npm.Source --prefix (Join-Path $ProjectDir "frontend") run build
        if ($LASTEXITCODE -ne 0) {
            throw "Frontend build failed with exit code $LASTEXITCODE."
        }

        Write-Host "Running Maven verification and packaging..."
        & $maven.Source -B clean verify
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
        $env:PATH = $previousPath
    }

    $sourceJar = Get-ChildItem -LiteralPath (Join-Path $ProjectDir "target") -Filter $JarNamePattern -File |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $sourceJar) {
        throw "Maven succeeded but no executable JAR was found."
    }
    if (-not (Test-Path -LiteralPath $PluginSource -PathType Container)) {
        throw "The yt-dlp plugin directory was not found: $PluginSource"
    }
    foreach ($file in @("yt-dlp.exe", "ffmpeg.exe", "ffprobe.exe")) {
        if (-not (Test-Path -LiteralPath (Join-Path $ProjectDir "src\main\resources\$file") -PathType Leaf)) {
            throw "Missing runtime dependency src\main\resources\$file. Add the required binary before publishing."
        }
    }

    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    $staging = Join-Path $InstallDir "staging"
    if (Test-Path -LiteralPath $staging) {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
    New-Item -ItemType Directory -Path $staging | Out-Null
    Copy-Item -LiteralPath $sourceJar.FullName -Destination $staging
    foreach ($file in $RuntimeFiles) {
        $source = Join-Path $ProjectDir "src\main\resources\$file"
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            Copy-Item -LiteralPath $source -Destination $staging
        } elseif ($file -eq "bgutil-pot.exe") {
            Write-Warning "bgutil-pot.exe is not present in the project. The application may download it at startup."
        }
    }
    Copy-Item -LiteralPath $PluginSource -Destination (Join-Path $staging "yt-dlp-plugins") -Recurse

    Stop-ManagedProcess
    Get-ChildItem -LiteralPath $InstallDir -Filter $JarNamePattern -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*.original" } |
        Remove-Item -Force
    Get-ChildItem -LiteralPath $staging -Force | ForEach-Object {
        $destination = Join-Path $InstallDir $_.Name
        if (Test-Path -LiteralPath $destination) {
            Remove-Item -LiteralPath $destination -Recurse -Force
        }
        Move-Item -LiteralPath $_.FullName -Destination $destination
    }
    Remove-Item -LiteralPath $staging -Force
    Write-Host "Release files installed to: $InstallDir"
}

switch ($Action) {
    "Deploy" {
        Publish-Application
        Start-ManagedProcess
    }
    "Start" {
        Start-ManagedProcess
    }
    "Stop" {
        Stop-ManagedProcess
    }
    "Restart" {
        Stop-ManagedProcess
        Start-ManagedProcess
    }
    "Status" {
        $managed = Get-ManagedProcess
        if ($managed) {
            Write-Host "Service is running (PID $($managed.ProcessId))."
            Test-ServiceHealth -TimeoutSeconds 5
        } else {
            Write-Host "The service is not running."
        }
    }
}
