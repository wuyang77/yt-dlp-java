# yt-dlp-java

> 基于 yt-dlp 的增强版 Spring Boot 下载服务，集成 PO Token Provider 以获取高清格式。

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 运行环境](#2-运行环境)
- [3. 项目结构](#3-项目结构)
- [4. 配置说明](#4-配置说明)
- [5. 安装与构建](#5-安装与构建)
- [6. 启动服务](#6-启动服务)
- [7. 发布上线](#7-发布上线)
- [8. API 使用指南](#8-api-使用指南)
- [9. 下载示例](#9-下载示例)
- [10. 架构设计](#10-架构设计)
- [11. 常见问题](#11-常见问题)

---

## 1. 项目概述

本项目是一个基于 **Spring Boot 4.1.1** 构建的 YouTube 视频下载服务。它通过封装 [yt-dlp](https://github.com/yt-dlp/yt-dlp) 命令行工具，提供 RESTful API 接口，支持以下核心功能：

- 自动遍历多个 YouTube 客户端（`web_embedded`、`tv`、`tv_downgraded`、`android_vr`）获取可用格式列表
- 集成 [bgutil-ytdlp-pot-provider](https://github.com/jim60105/bgutil-ytdlp-pot-provider-rs) 解决 PO Token 限制，获取高清格式
- 自动选取最高码率的视频流和音频流，合并输出为 MP4
- 支持仅视频、仅音频、指定格式 ID 等多种下载模式
- 支持 cookies.txt 文件、Firefox 浏览器、匿名三种 Cookie 来源

---

## 2. 运行环境

### 2.1 必需环境

| 组件 | 要求版本 | 实测版本 | 用途 |
|------|----------|----------|------|
| **JDK** | 17+ | JDK 17.0.4.1 (Temurin) | 编译和运行 Spring Boot 应用 |
| **Maven** | 3.8+ | Apache Maven 3.9.11 | 项目构建和依赖管理 |
| **Node.js** | 20.19+ 或 22.12+ | Node.js 24.x | 前端构建及 yt-dlp 的 JS 挑战求解运行时 |
| **操作系统** | Windows 10/11 x64 | Windows 11 23H2 | 宿主操作系统（二进制为 Windows x86_64） |

### 2.2 内置二进制依赖

以下二进制文件位于 `src/main/resources/` 目录，随项目分发，无需额外安装：

| 文件 | 版本 | 用途 |
|------|------|------|
| `yt-dlp.exe` | nightly | YouTube 视频下载核心引擎 |
| `ffmpeg.exe` | 7.0+ | 视频音频合并、格式转换 |
| `ffprobe.exe` | 7.0+ | 媒体文件探测（ffmpeg 附带） |
| `bgutil-pot.exe` | latest | PO Token Provider，解决 YouTube 高清格式限制 |

### 2.3 环境变量配置

Maven Toolchains 插件要求在 `~/.m2/toolchains.xml` 中配置 JDK 路径：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
    <toolchain>
        <type>jdk</type>
        <provides>
            <version>17</version>
            <vendor>temurin</vendor>
        </provides>
        <configuration>
            <jdkHome>D:/dev/jdk-17.0.4.1</jdkHome>
        </configuration>
    </toolchain>
</toolchains>
```

> **注意：** 请将 `jdkHome` 替换为你本机的实际 JDK 17 安装路径。Windows 系统中路径使用正斜杠 `/`。

---

## 3. 项目结构

```
yt-dlp-java/
├── pom.xml                                  # Maven 构建文件 (Spring Boot 4.1.1)
├── frontend/                                # Vue 3 + Vite + TypeScript 前端
│   ├── src/
│   │   ├── components/                      # 页面组件
│   │   ├── composables/                     # Vue 组合式状态与业务逻辑
│   │   ├── services/                        # 后端 API 请求
│   │   ├── types/                           # 前后端数据类型
│   │   ├── views/                           # 页面级视图
│   │   ├── App.vue                          # 页面组合入口
│   │   ├── main.ts                          # Vue 应用入口
│   │   └── style.css                        # 全局样式
│   ├── index.html                           # Vite HTML 入口
│   ├── package.json                         # 前端依赖与脚本
│   └── vite.config.ts                       # Vite 开发代理和构建配置
├── frontend-dist/                            # 前端构建产物（自动生成）
├── .gitignore                               # Git 忽略规则
├── README.md                                # 本文件
├── src/
│   ├── main/
│   │   ├── java/org/wuyang/ytdlp/
│   │   │   ├── YouTubeDownloaderApplication.java  # Spring Boot 启动类
│   │   │   ├── config/                            # 配置层
│   │   │   │   ├── YtDlpProperties.java           #   @ConfigurationProperties 属性绑定
│   │   │   │   └── YtDlpConfiguration.java        #   配置注册
│   │   │   ├── model/                             # 数据模型层
│   │   │   │   ├── Format.java                     #   媒体格式 record
│   │   │   │   ├── FormatParser.java               #   yt-dlp -F 输出解析器
│   │   │   │   ├── DownloadMode.java               #   下载方式枚举
│   │   │   │   ├── DownloadRequest.java            #   请求 DTO
│   │   │   │   ├── DownloadResponse.java           #   响应 DTO
│   │   │   │   └── FormatListResponse.java         #   格式列表响应 DTO
│   │   │   ├── service/                           # 服务层
│   │   │   │   ├── DownloadService.java            #   核心业务逻辑
│   │   │   │   ├── PotProvider.java                #   PO Token 管理器
│   │   │   │   └── YtDlpRunner.java                 #   命令构建/执行器
│   │   │   └── controller/                        # 控制器层
│   │   │       └── DownloadController.java         #   REST API 端点
│   │   └── resources/
│   │       ├── application.yml                    # Spring Boot 配置文件
│   │       ├── yt-dlp.exe                         # yt-dlp 二进制
│   │       ├── ffmpeg.exe                         # ffmpeg 二进制
│   │       ├── ffprobe.exe                        # ffprobe 二进制
│   │       ├── bgutil-pot.exe                     # PO Token Provider 二进制
│   │       └── cookies.txt                        # YouTube cookies 文件
└── target/                                      # 构建产物（自动生成）
    └── yt-dlp-java-1.0.0.jar                     # 可执行 JAR
```

---

## 4. 配置说明

所有配置集中在 `src/main/resources/application.yml` 中，按功能分区：

### 4.1 服务端配置

```yaml
server:
  port: 8080                    # HTTP 服务端口
  servlet:
    encoding:
      charset: UTF-8
      force: true
```

### 4.2 业务配置

```yaml
ytdlp:
  # --- 输出目录 ---
  output:
    dir: F:/学习资料/套图/打碟          # 下载文件保存目录
    template: "%(title)s [%(resolution)s].%(ext)s"  # 完整标题 + 分辨率，保留 Unicode

  # --- 二进制依赖路径 ---
  bin:
    yt-dlp: src/main/resources/yt-dlp.exe
    ffmpeg: src/main/resources/ffmpeg.exe
    cookies: src/main/resources/cookies.txt
    node: D:/dev/nvm/nodejs/node.exe     # Node.js 可执行文件路径

  # --- PO Token Provider ---
  pot:
    path: src/main/resources/bgutil-pot.exe
    port: 49300                          # PO Provider HTTP 服务端口
    download-url: https://github.com/... # 自动下载地址

  # --- HTTP 头 ---
  http:
    user-agent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ..."
    referer: https://www.youtube.com/

  # --- YouTube 客户端策略 ---
  youtube:
    clients: web_embedded,tv,tv_downgraded,android_vr

  # --- 下载参数 ---
  download:
    retries: 3                           # 下载失败重试次数

  # --- 本地音轨语种识别（Whisper.cpp） ---
  speech-recognition:
    whisper-cli: whisper-cli              # whisper.cpp 命令行程序
    model: models/ggml-base.bin           # 多语言 Whisper 模型路径
    parallelism: 2                        # 同时识别的音轨数（最大 4）
    sample-seconds: 12                    # 每条音轨取样秒数（5-30）
```

未标注语言的音轨会并行提取开头片段，并由本机 Whisper.cpp 自动识别；音频不会上传到第三方。需自行准备 Whisper.cpp 的 `whisper-cli` 和多语言模型，并设置 `ytdlp.speech-recognition.whisper-cli` 与 `ytdlp.speech-recognition.model`。模型未配置时界面会显示“未配置本地识别”；识别失败或语音不足以判断时会显示对应状态。

### 4.3 配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `ytdlp.output.dir` | String | `F:/学习资料/套图/打碟` | 下载文件保存目录，需有写入权限 |
| `ytdlp.output.template` | String | `%(title)s [%(resolution)s].%(ext)s` | 完整标题 + 分辨率的文件名模板 |
| `ytdlp.bin.yt-dlp` | String | `src/main/resources/yt-dlp.exe` | yt-dlp 二进制路径 |
| `ytdlp.bin.ffmpeg` | String | `src/main/resources/ffmpeg.exe` | ffmpeg 二进制路径 |
| `ytdlp.bin.cookies` | String | `src/main/resources/cookies.txt` | cookies 文件路径 |
| `ytdlp.bin.node` | String | `D:/dev/nvm/nodejs/node.exe` | Node.js 路径，需根据实际安装位置修改 |
| `ytdlp.speech-recognition.whisper-cli` | String | `whisper-cli` | Whisper.cpp 命令行程序路径 |
| `ytdlp.speech-recognition.model` | String | 空 | 多语言 Whisper 模型文件路径 |
| `ytdlp.speech-recognition.parallelism` | int | `2` | 并发识别数，最大 4；提高会增加内存占用 |
| `ytdlp.speech-recognition.sample-seconds` | int | `12` | 每条音轨用于语种识别的开头采样秒数 |
| `ytdlp.pot.port` | int | `49300` | PO Token Provider HTTP 端口 |
| `ytdlp.youtube.clients` | String | `web_embedded,tv,...` | 客户端策略，逗号分隔 |
| `ytdlp.download.retries` | int | `3` | 下载重试次数 |

> **提示：** 可创建 `application-local.yml` 覆盖个人配置（已加入 `.gitignore`）。

---

## 5. 安装与构建

### 5.1 前置条件

确保已安装 JDK 17、Maven 3.8+、Node.js 20.19+ 或 22.12+，并完成 [环境变量配置](#23-环境变量配置)。

### 5.2 克隆项目

```bash
git clone <repository-url>
cd yt-dlp-java
```

### 5.3 放置二进制文件

将以下文件放入 `src/main/resources/` 目录（如尚未存在）：

```
src/main/resources/
├── yt-dlp.exe          # 从 https://github.com/yt-dlp/yt-dlp/releases 下载
├── ffmpeg.exe          # 从 https://ffmpeg.org/download.html 下载
├── ffprobe.exe         # ffmpeg 附带
├── bgutil-pot.exe      # 首次启动时自动下载（也可手动放置）
└── cookies.txt         # 从浏览器导出的 YouTube cookies（可选）
```

### 5.4 构建

先构建前端静态文件（首次安装前端依赖时执行 `npm.cmd install`）：

```bash
cd frontend
npm.cmd run build
cd ..
```

开发前端时，可在 `frontend` 目录运行 `npm.cmd run dev`。Vite 会将 `/api` 请求代理到本机 Spring Boot 服务 `http://localhost:8080`。

```bash
# 编译
mvn clean compile

# 打包为可执行 JAR
mvn package
```

Maven 会将 `frontend-dist/` 中的前端构建产物打包进 Spring Boot 的 `static/` 目录。构建成功后，JAR 文件位于 `target/yt-dlp-java-1.0.0.jar`。

---

## 6. 启动服务

### 6.1 Maven 直接运行

```bash
# 先构建一次前端（后续前端源码变化后重新构建）
cd frontend
npm.cmd run build
cd ..

mvn spring-boot:run
```

### 6.2 JAR 运行

```bash
java -jar target/yt-dlp-java-1.0.0.jar
```

### 6.3 IDE 运行

在 IDE（如 IntelliJ IDEA、CodeBuddy）中直接运行 `YouTubeDownloaderApplication.main()` 方法。

### 6.4 验证启动

服务启动后，访问健康检查端点：

```bash
curl http://localhost:8080/api/health
```

预期返回：

```
OK
```

### 6.5 启动日志

正常启动时，控制台将输出以下信息：

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::               (v3.0.0)
```

PO Token Provider 将在应用启动时自动初始化（下载二进制 → 启动进程 → 健康检查）。

控制台日志使用 ANSI 颜色辅助区分级别：错误为红色、警告为黄色、信息为绿色；线程和 Logger 名称使用青色、洋红色。支持 ANSI 的终端（如 Windows Terminal、VS Code 集成终端）会显示颜色。

---

## 7. 发布上线

项目提供 Windows PowerShell 发布脚本 `deploy.ps1`。脚本会先用 `npm ci` 安装前端依赖并构建 Vue 应用，再执行 `mvn clean verify`，将可执行 JAR、yt-dlp/ffmpeg 运行文件和 yt-dlp 插件复制到部署目录，启动 Spring Boot，并等待 `/api/health` 返回 `OK`。Maven 打包会排除本机运行二进制、插件目录和 `cookies.txt`；发布脚本也不会复制登录凭据，避免将其打包或覆盖。以下命令仅对本次 PowerShell 进程临时绕过执行策略，不会修改系统级策略。

**发布机要求：** Windows、JDK 17+、Maven 3.8+、Node.js 20.19+ 或 22.12+；项目运行所需的 `yt-dlp.exe`、`ffmpeg.exe`、`ffprobe.exe` 和 `src/main/resources/yt-dlp-plugins/` 必须已准备好。

```powershell
# 首次发布（默认目录：%LOCALAPPDATA%\yt-dlp-java；默认只监听本机 127.0.0.1:8080）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1

# 指定发布目录、端口、Node.js 和下载保存目录
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 `
  -InstallDir "D:\apps\yt-dlp-java" -Port 8080 `
  -NodePath "D:\tools\node.exe" -OutputDir "D:\media\downloads"

# 常用服务操作
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -Action Status
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -Action Stop
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -Action Start
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -Action Restart

# 如使用可信反向代理并已配置 HTTPS 与访问控制，可改为监听所有网卡
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -BindAddress "0.0.0.0"
```

`-Action Deploy`（默认）会重新构建、发布并启动；`Start`/`Stop`/`Restart`/`Status` 用于管理已发布的实例。管理已有实例时，应继续使用发布时相同的 `-InstallDir` 和 `-Port`。可用 `-MaxHeap "2g"` 调整 JVM 最大内存（默认 `1g`）。

部署目录内的 `cookies.txt` 可由管理员手动放入；脚本默认引用该文件但不会从源码目录复制它。日志位于部署目录 `logs/`，下载文件位于 `downloads/`（或 `-OutputDir` 指定的位置）。停止/重启会结束该服务进程，正在进行的下载可能中断；更新前请先确认没有重要任务。

此脚本用于 Windows 主机上的发布与后台启动，不会注册 Windows 服务或设置开机自动启动；主机重启后可再次运行 `-Action Start`。项目目前包含 Windows 运行二进制，Linux 部署需要准备与服务器平台匹配的 yt-dlp/ffmpeg/PO Token Provider 并调整应用配置。

**公网安全：** 服务默认只绑定本机。应用没有内置用户认证，不要直接暴露在公网；需要远程访问时，应放在配置了 HTTPS、认证和访问控制的反向代理后，并自行配置服务器防火墙。YouTube 访问、Cookies 与平台对视频的限制仍适用。

---

## 8. API 使用指南

### 7.1 API 端点总览

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/health` | 健康检查 |
| `GET` | `/api/formats` | 获取视频可用格式列表 |
| `GET` | `/api/preview/options` | 查询 yt-dlp 预览格式与字幕选项 |
| `GET` | `/api/preview` | 解析 yt-dlp 预览媒体流 |
| `GET` | `/api/preview/subtitles` | 获取源字幕并转换为 WebVTT |
| `POST` | `/api/download` | 执行下载 |

### 7.2 视频预览

前端顶部的“预览”按钮会打开 YouTube 官方嵌入式播放器。播放、清晰度、字幕/自动翻译和音轨/配音选择均由 YouTube 原生播放器提供，用户可像在 YouTube 一样使用底部控制栏和齿轮菜单；项目只提供播放器外部的关闭按钮，以返回下载页。

```
https://www.youtube.com/embed/{videoId}?controls=1&hl=zh-CN
```

预览支持标准 YouTube、短链接、Shorts、直播及嵌入链接。是否可播放、是否允许嵌入，以及视频本身提供哪些清晰度、字幕翻译和配音选项，仍由 YouTube 和视频发布者决定。

### 7.3 获取格式列表

```
GET /api/formats?url={url}&cookieMode={cookieMode}
```

**参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `url` | String | 是 | - | YouTube 视频 URL |
| `cookieMode` | String | 否 | `FILE` | Cookie 来源：`FILE` / `FIREFOX` / `NONE` |

**示例：**

```bash
curl "http://localhost:8080/api/formats?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**响应示例：**

```json
{
  "success": true,
  "message": "获取成功",
  "formats": [
    {
      "id": "137",
      "ext": "mp4",
      "res": "1920x1080",
      "fps": "30",
      "size": "45.50MiB",
      "tbr": "2800k",
      "vcodec": "avc1.640028",
      "acodec": "",
      "abr": "",
      "audioOnly": false
    },
    {
      "id": "251",
      "ext": "webm",
      "res": "audio only",
      "fps": "",
      "size": "3.20MiB",
      "tbr": "160k",
      "vcodec": "",
      "acodec": "opus",
      "abr": "160k",
      "audioOnly": true
    }
  ]
}
```

### 7.4 执行下载

```
POST /api/download
Content-Type: application/json
```

**请求体字段：**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `url` | String | 是 | - | YouTube 视频 URL |
| `mode` | String | 否 | `BEST_MERGE` | 下载方式（见下表） |
| `cookieMode` | String | 否 | `FILE` | Cookie 来源：`FILE` / `FIREFOX` / `NONE` |
| `formatId` | String | 否 | - | 自定义格式 ID（`mode` 为 `CUSTOM` 时使用） |

**下载方式（`mode`）枚举值：**

| 枚举值 | 说明 |
|--------|------|
| `BEST_MERGE` | 最高码率合并：自动选取最高码率视频 + 最高码率音频，合并输出 MP4 |
| `VIDEO_ONLY` | 仅视频：下载最高码率的视频流 |
| `AUDIO_ONLY` | 仅音频：下载最高码率的音频流 |
| `CUSTOM` | 自定义格式 ID：使用 `formatId` 字段指定的 yt-dlp 格式表达式 |

---

## 9. 下载示例

### 8.1 最高码率合并下载（推荐）

```bash
curl -X POST http://localhost:8080/api/download \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "mode": "BEST_MERGE",
    "cookieMode": "FILE"
  }'
```

**响应示例：**

```json
{
  "success": true,
  "message": "下载完成",
  "filePath": "F:/学习资料/套图/打碟/Rick_Astley_Never_Gonna_Give__You_Up_[1080p].mp4",
  "fileName": "Rick_Astley_Never_Gonna_Give_You_Up_[1080p].mp4",
  "bestVideo": "137 mp4 | 1920x1080 | 2800k | 45.50MiB",
  "bestAudio": "251 webm | 160k | opus | 3.20MiB",
  "diagnostics": [
    "当前客户端: web_embedded",
    "最佳视频: 137 mp4 | 1920x1080 | 2800k | 45.50MiB",
    "最佳音频: 251 webm | 160k | opus | 3.20MiB"
  ]
}
```

### 8.2 仅下载音频

```bash
curl -X POST http://localhost:8080/api/download \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "mode": "AUDIO_ONLY",
    "cookieMode": "FILE"
  }'
```

### 8.3 指定格式 ID 下载

先通过 [获取格式列表](#72-获取格式列表) API 查看可用格式 ID，然后指定下载：

```bash
curl -X POST http://localhost:8080/api/download \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "mode": "CUSTOM",
    "formatId": "137+251"
  }'
```

### 8.4 使用 Firefox 浏览器 Cookie

适用于年龄限制视频，需要登录态：

```bash
curl -X POST http://localhost:8080/api/download \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://www.youtube.com/watch?v=xxx",
    "mode": "BEST_MERGE",
    "cookieMode": "FIREFOX"
  }'
```

### 8.5 匿名下载（不使用 Cookie）

```bash
curl -X POST http://localhost:8080/api/download \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://www.youtube.com/watch?v=xxx",
    "mode": "BEST_MERGE",
    "cookieMode": "NONE"
  }'
```

---

## 10. 架构设计

### 9.1 分层架构

本项目遵循标准的 Spring Boot 分层架构：

```
┌─────────────────────────────────────────────┐
│              Controller 层                   │
│  DownloadController                          │
│  接收 HTTP 请求，参数校验，返回 JSON 响应      │
├─────────────────────────────────────────────┤
│              Service 层                      │
│  DownloadService                             │
│  核心业务逻辑：格式获取、排序、下载编排         │
│  PotProvider        YtDlpRunner              │
│  PO Token 生命周期    命令构建与进程执行        │
├─────────────────────────────────────────────┤
│              Model 层                        │
│  Format / FormatParser / DTO                 │
│  数据模型、解析器、请求/响应对象               │
├─────────────────────────────────────────────┤
│              Config 层                       │
│  YtDlpProperties / YtDlpConfiguration        │
│  配置属性绑定，环境相关路径外置                 │
└─────────────────────────────────────────────┘
```

### 9.2 核心设计原则

| 原则 | 实现 |
|------|------|
| **配置外置** | 所有环境相关路径通过 `application.yml` 配置，代码中零硬编码 |
| **单一职责** | 每个类只负责一个职责（解析、命令构建、生命周期管理等） |
| **依赖注入** | 通过 Spring 容器管理 Bean 生命周期，构造器注入 |
| **线程安全** | `YtDlpRunner` 使用 `ThreadLocal` 隔离请求级状态 |
| **降级容错** | PO Provider 未就绪时自动降级，不阻断服务启动 |
| **健康检查** | PO Provider 启动时通过 HTTP `/ping` 确认真连通 |

### 9.3 下载流程

```
1. 客户端发送 POST /api/download 请求
2. DownloadController 校验参数 → 调用 DownloadService
3. DownloadService 重置诊断状态 → 设置 Cookie 模式
4. 遍历多个 YouTube 客户端获取格式列表：
   a. YtDlpRunner 构建基础命令（UA、Cookie、PO Token）
   b. 执行 yt-dlp -F 获取格式列表
   c. FormatParser 解析文本输出为 Format 列表
   d. 合并去重 + 排序（视频按画质降序，音频按码率降序）
5. 自动选取最高码率视频 ID + 最高码率音频 ID
6. 构建 yt-dlp 格式表达式（带回退链）
7. 执行 yt-dlp -f 下载 + ffmpeg 合并为 MP4
8. 提取输出文件路径，返回 DownloadResponse
```

---

## 11. 常见问题

### 10.1 启动时报 "找不到 config.properties"

本项目已从 `config.properties` 迁移到 `application.yml`。如果出现此错误，说明使用了旧版本代码，请确保项目中不存在旧的 `Config.java` 文件。

### 10.2 "无可用格式: cookies / PO Token / 客户端问题"

此错误表示所有客户端均未获取到格式列表。排查步骤：

1. **检查 cookies**：确认 `cookies.txt` 文件存在且有效，或改用 `cookieMode: FIREFOX`
2. **检查 PO Token Provider**：访问 `http://127.0.0.1:49300/ping`，确认返回 200
3. **检查 Node.js**：确认 `ytdlp.bin.node` 路径指向有效的 `node.exe`
4. **检查网络**：确认能正常访问 YouTube

若错误提示包含 **“Sign in to confirm you're not a bot”**，说明 YouTube 要求进行机器人验证，当前 cookies 可能无效、过期或未成功加载。请从已登录 YouTube 的浏览器重新导出有效的 `cookies.txt`，或改用 `cookieMode: FIREFOX`；若仍失败，可能是当前网络/IP 受到 YouTube 的临时验证限制。不要将 cookies 内容发到日志或公开渠道。

### 10.3 PO Token Provider 启动失败

- 确认 `src/main/resources/bgutil-pot.exe` 文件存在（首次启动会自动下载）
- 确认端口 `49300` 未被占用
- 手动启动测试：`bgutil-pot.exe server --host 127.0.0.1 --port 49300`

### 10.4 下载文件未找到

- 检查 `ytdlp.output.dir` 目录是否存在且有写入权限
- 服务会在目录不存在时自动创建
- 下载完成后，响应中的 `filePath` 字段包含完整路径

### 10.5 年龄限制视频无法下载

- 使用 `cookieMode: FIREFOX`（需已安装 Firefox 并登录 YouTube）
- 或使用 `cookieMode: FILE` 并提供有效的 `cookies.txt` 文件
- 诊断信息中会提示 "检测到年龄限制视频"

### 10.6 Maven 编译失败 "No toolchain found"

确认 `~/.m2/toolchains.xml` 文件配置正确，JDK 路径指向有效的 JDK 17 安装目录。详见 [环境变量配置](#23-环境变量配置)。

---

## 许可证

本项目仅供个人学习和研究使用。请遵守 YouTube 的服务条款和当地法律法规。
