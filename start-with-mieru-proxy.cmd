@echo off
rem ============================================================
rem 脚本名称：start-with-mieru-proxy.cmd
rem 脚本作用：先通过 AllTools 菜单启动 Mieru 代理，等待本�?SOCKS5
rem           代理端口就绪后，再携带代理设置启�?yt-dlp-java 服务
rem ============================================================
rem 关闭命令回显：运行脚本时不逐条打印所执行的命令本身，只显示命令输�?
setlocal
rem 开启局部环境变量作用域：脚本中�?set 定义的变量仅在本脚本内有效，
rem 脚本结束后自动还原，避免污染系统环境变量
chcp 65001 >nul 2>&1
rem 将控制台代码页切换为 UTF-8�?5001），保证中文正常显示
rem 其中 >nul 表示丢弃正常输出�?>&1 表示把错误输出一并重定向丢弃，即静默执行

rem ============================================================
rem 第一部分：定义关键路径变�?
rem ============================================================
set "PROJECT_DIR=%~dp0"
rem PROJECT_DIR 为本脚本所在目录（批处理修饰符 ~dp0 自带结尾反斜杠），即项目根目�?
set "PROXY_HOME=D:\Chrome153_AllNew_2026.9.12"
rem PROXY_HOME �?Mieru �?AllTools 代理工具包所在的安装目录
set "ALLTOOLS_LAUNCHER=%PROXY_HOME%\Launch-AllTools.cmd"
rem ALLTOOLS_LAUNCHER �?AllTools 一键启动菜单脚本的完整路径

rem ============================================================
rem 第二部分：检查运行环境依赖是否齐�?
rem 说明：where.exe 用于�?PATH 环境变量中查找命令，>nul 2>&1 静默其输出；
rem       找不到命令时 errorlevel �?1，进入报错分支：�?pause 等待用户
rem       查看提示并按键，再以退出码 1 终止脚本
rem ============================================================
where.exe java >nul 2>&1
if errorlevel 1 (
  rem 系统中未找到 java 命令，需要安�?Java 17 或将其加�?PATH
  echo [ERROR] Java was not found. Install Java 17 or add it to PATH, then try again.
  pause
  exit /b 1
)

where.exe mvn >nul 2>&1
if errorlevel 1 (
  rem 系统中未找到 mvn 命令，需要安�?Maven 或将 mvn.cmd 所在目录加�?PATH
  echo [ERROR] Maven was not found. Install Maven or add mvn.cmd to PATH, then try again.
  pause
  exit /b 1
)

where.exe node >nul 2>&1
if errorlevel 1 (
  rem 系统中未找到 node 命令，前端构建依�?Node.js 20.19 及以上或 22.12 及以上版�?
  echo [ERROR] Node.js 20.19+ or 22.12+ was not found. Install Node.js and try again.
  pause
  exit /b 1
)

where.exe npm.cmd >nul 2>&1
if errorlevel 1 (
  rem 找到�?node 却找不到 npm.cmd，通常说明 Node.js 安装不完整，需要修复安�?
  echo [ERROR] npm.cmd was not found. Repair the Node.js installation and try again.
  pause
  exit /b 1
)

rem 检�?AllTools 启动脚本文件是否真实存在，路径缺失则后续无法启动代理
if not exist "%ALLTOOLS_LAUNCHER%" (
  rem 指定�?Launch-AllTools.cmd 文件不存�?
  echo [ERROR] AllTools launcher not found: "%ALLTOOLS_LAUNCHER%"
  pause
  exit /b 1
)

rem ============================================================
rem 第三部分：准备前端依赖并构建前端
rem ============================================================
rem 判断前端依赖是否已经安装：vite.cmd 存在说明 node_modules 完整、npm ci 已执行过
if not exist "%PROJECT_DIR%frontend\node_modules\.bin\vite.cmd" (
  echo Installing frontend dependencies...
  rem 调用 npm.cmd 安装依赖�?-prefix 指定 frontend 目录，ci 严格按照
  rem package-lock.json 进行干净、可复现的安�?
  call npm.cmd --prefix "%PROJECT_DIR%frontend" ci
  if errorlevel 1 (
    rem 前端依赖安装失败
    echo [ERROR] Frontend dependency installation failed.
    pause
    exit /b 1
  )
)

echo Building the frontend...
rem 执行前端生产构建，对�?package.json 中的 build 脚本，内部调�?vite build
rem 构建产物默认输出�?frontend\dist 目录，随后由 Spring Boot 作为静态资源托�?
call npm.cmd --prefix "%PROJECT_DIR%frontend" run build
if errorlevel 1 (
  rem 前端构建失败，常见原因为 Node.js 版本过低或编译报错，需查看上方 npm 输出
  echo [ERROR] Frontend build failed. Check the Node.js version and npm output.
  pause
  exit /b 1
)

rem ============================================================
rem 第四部分：启�?Mieru 代理
rem ============================================================
rem 设置下载代理环境变量：yt-dlp-java 的下载流量经本地 SOCKS5 代理发出�?
rem 代理地址�?127.0.0.1，端口为 3080
set "YTDLP_HTTP_PROXY=socks5://127.0.0.1:3080"
echo Launching AllTools in this console. Select Mieru (option 8) and its route there.
echo AllTools will open its own shared Chrome profile; this script will not start another browser.
rem pushd 保存当前工作目录，并切换到代理工具目�?
pushd "%PROXY_HOME%"
rem 在当前控制台中调�?AllTools 菜单脚本，需要手动选择 Mieru，即选项 8，并选择其路�?
call "%ALLTOOLS_LAUNCHER%"
rem 立即保存 AllTools 的退出码，因为后�?popd 等操作可能改�?errorlevel 的�?
set "ALLTOOLS_EXIT_CODE=%errorlevel%"
rem popd 恢复�?pushd 执行前所保存的工作目�?
popd
if not "%ALLTOOLS_EXIT_CODE%"=="0" (
  rem AllTools 未正常完成，打印其退出码并终�?
  echo [ERROR] AllTools did not complete successfully. Exit code: %ALLTOOLS_EXIT_CODE%
  pause
  exit /b 1
)

rem ============================================================
rem 第五部分：等�?Mieru �?SOCKS5 代理端口就绪
rem ============================================================
echo Waiting for the Mieru SOCKS5 proxy on 127.0.0.1:3080...
rem 调用 PowerShell 轮询探测 3080 端口�?
rem   截止时间为当�?UTC 时间�?180 秒后；每�?500 毫秒尝试建立一�?
rem   TCP 连接，连接成功立即以退出码 0 返回，超�?180 秒仍失败则返�?1
rem -NoLogo 去掉启动版权标志�?NoProfile 不加载用户配置文件，加快启动速度
powershell.exe -NoLogo -NoProfile -Command "$deadline = [DateTime]::UtcNow.AddSeconds(180); while ([DateTime]::UtcNow -lt $deadline) { $client = [Net.Sockets.TcpClient]::new(); try { $client.Connect('127.0.0.1', 3080); if ($client.Connected) { $client.Dispose(); exit 0 } } catch { } finally { $client.Dispose() }; Start-Sleep -Milliseconds 500 }; exit 1"
if errorlevel 1 (
  rem 等待超时，Mieru SOCKS5 代理仍未就绪，需要检�?Mieru 窗口状态后重试
  echo [ERROR] Mieru SOCKS5 did not become ready. Check the Mieru window and try again.
  pause
  exit /b 1
)

rem ============================================================
rem 第六部分：检�?8080 端口是否已有 yt-dlp-java 实例在运�?
rem ============================================================
rem 请求健康检查接�?http://127.0.0.1:8080/api/health ，超时时�?2 秒；
rem 返回内容去除首尾空白后等�?OK 则以退出码 0 返回，其他情况均返回 1
powershell.exe -NoLogo -NoProfile -Command "try { $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 2; if ($response.Content.Trim() -eq 'OK') { exit 0 } } catch { }; exit 1"
if not errorlevel 1 (
  rem 健康检查成功，说明已有实例占用 8080 端口；必须先停止旧实例，
  rem 再由本脚本重新启动，新进程才能继承代理环境变�?
  echo [ERROR] yt-dlp-java is already running on port 8080.
  echo Stop the existing instance first, then run this launcher again so it inherits the proxy setting.
  pause
  exit /b 1
)

rem ============================================================
rem 第七部分：启�?yt-dlp-java 后端服务
rem ============================================================
rem 切换到项目根目录�?d 参数允许同时切换驱动器，保证 Maven 能找到根目录下的 pom.xml
cd /d "%PROJECT_DIR%"

echo.
echo Starting yt-dlp-java with the Mieru SOCKS5 proxy.
echo App will auto-open in AllTools Chrome when ready (proxy enabled for YouTube).
echo Press Ctrl+C in this window to stop yt-dlp-java.
echo.
rem 以前台方式运�?Spring Boot 应用；该 Java 进程会继承本脚本中设置的
rem YTDLP_HTTP_PROXY 代理环境变量，启动后可在浏览器访�?http://localhost:8080
rem ��̨�ȴ�Ӧ�þ������ڴ������� AllTools Chrome �д�ҳ��
rem Chrome �� --proxy-server ��������Զ��ƹ� localhost�����Ա���ҳ��ֱ����
rem YouTube ���ⲿ��Դ�ߴ�����ʵ��ǰ��ҳ��Ҳ�ܷ�ǽ
start /b "" powershell.exe -NoProfile -WindowStyle Hidden -Command "$d=[DateTime]::UtcNow.AddSeconds(120); while([DateTime]::UtcNow -lt $d){try{$r=Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 2; if($r.Content.Trim() -eq 'OK'){break}}catch{};Start-Sleep -Seconds 2}; if([DateTime]::UtcNow -lt $d){Start-Process -FilePath 'D:\Chrome153_AllNew_2026.9.12\App\chrome.exe' -ArgumentList '--user-data-dir=D:\Chrome153_AllNew_2026.9.12\Data','--proxy-server=socks5://127.0.0.1:3080','--proxy-bypass-list=localhost;127.0.0.1','http://localhost:8080'}"
call mvn spring-boot:run
rem �?Maven 的退出码透传作为本脚本的退出码�?b 表示仅退出当前批处理脚本�?
rem 而不关闭父级 cmd 窗口
exit /b %errorlevel%
