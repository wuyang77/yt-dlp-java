@echo off
setlocal
chcp 65001 >nul 2>&1

set "PROJECT_DIR=%~dp0"
set "PROXY_HOME=D:\Chrome153_AllNew_2026.9.12"
set "ALLTOOLS_LAUNCHER=%PROXY_HOME%\Launch-AllTools.cmd"

where.exe java >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Java was not found. Install Java 17 or add it to PATH, then try again.
  pause
  exit /b 1
)

where.exe mvn >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Maven was not found. Install Maven or add mvn.cmd to PATH, then try again.
  pause
  exit /b 1
)

where.exe node >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Node.js 20.19+ or 22.12+ was not found. Install Node.js and try again.
  pause
  exit /b 1
)

where.exe npm.cmd >nul 2>&1
if errorlevel 1 (
  echo [ERROR] npm.cmd was not found. Repair the Node.js installation and try again.
  pause
  exit /b 1
)

if not exist "%ALLTOOLS_LAUNCHER%" (
  echo [ERROR] AllTools launcher not found: "%ALLTOOLS_LAUNCHER%"
  pause
  exit /b 1
)

if not exist "%PROJECT_DIR%frontend\node_modules\.bin\vite.cmd" (
  echo Installing frontend dependencies...
  call npm.cmd --prefix "%PROJECT_DIR%frontend" ci
  if errorlevel 1 (
    echo [ERROR] Frontend dependency installation failed.
    pause
    exit /b 1
  )
)

echo Building the frontend...
call npm.cmd --prefix "%PROJECT_DIR%frontend" run build
if errorlevel 1 (
  echo [ERROR] Frontend build failed. Check the Node.js version and npm output.
  pause
  exit /b 1
)

set "YTDLP_HTTP_PROXY=socks5://127.0.0.1:3080"
echo Launching AllTools in this console. Select Mieru (option 8) and its route there.
echo AllTools will open its own shared Chrome profile; this script will not start another browser.
pushd "%PROXY_HOME%"
call "%ALLTOOLS_LAUNCHER%"
set "ALLTOOLS_EXIT_CODE=%errorlevel%"
popd
if not "%ALLTOOLS_EXIT_CODE%"=="0" (
  echo [ERROR] AllTools did not complete successfully. Exit code: %ALLTOOLS_EXIT_CODE%
  pause
  exit /b 1
)

echo Waiting for the Mieru SOCKS5 proxy on 127.0.0.1:3080...
powershell.exe -NoLogo -NoProfile -Command "$deadline = [DateTime]::UtcNow.AddSeconds(180); while ([DateTime]::UtcNow -lt $deadline) { $client = [Net.Sockets.TcpClient]::new(); try { $client.Connect('127.0.0.1', 3080); if ($client.Connected) { $client.Dispose(); exit 0 } } catch { } finally { $client.Dispose() }; Start-Sleep -Milliseconds 500 }; exit 1"
if errorlevel 1 (
  echo [ERROR] Mieru SOCKS5 did not become ready. Check the Mieru window and try again.
  pause
  exit /b 1
)

powershell.exe -NoLogo -NoProfile -Command "try { $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 2; if ($response.Content.Trim() -eq 'OK') { exit 0 } } catch { }; exit 1"
if not errorlevel 1 (
  echo [ERROR] yt-dlp-java is already running on port 8080.
  echo Stop the existing instance first, then run this launcher again so it inherits the proxy setting.
  pause
  exit /b 1
)

cd /d "%PROJECT_DIR%"

echo.
echo Starting yt-dlp-java with the Mieru SOCKS5 proxy.
echo When startup completes, open http://localhost:8080 and enter the video URL.
echo Press Ctrl+C in this window to stop yt-dlp-java.
echo.
call mvn spring-boot:run
exit /b %errorlevel%
