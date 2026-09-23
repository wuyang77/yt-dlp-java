# yt-dlp-java

> 基于 yt-dlp 的增强版 Spring Boot 下载服务，集成 PO Token Provider 以获取高清格式。

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 运行环境](#2-运行环境)
- [3. 项目结构](#3-项目结构)
- [4. 配置说明](#4-配置说明)
- [5. 安装与构建](#5-安装与构建)
- [6. 启动服务](#6-启动服务)
- [7. API 使用指南](#7-api-使用指南)
- [8. 下载示例](#8-下载示例)
- [9. 架构设计](#9-架构设计)
- [10. 常见问题](#10-常见问题)

---

## 1. 项目概述

本项目是一个基于 **Spring Boot 3.0.0** 构建的 YouTube 视频下载服务。它通过封装 [yt-dlp](https://github.com/yt-dlp/yt-dlp) 命令行工具，提供 RESTful API 接口，支持以下核心功能：

- 自动遍历多个 YouTube 客户端（`web_embedded`、`tv`、`mweb` 等）获取完整格式列表
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
| **Node.js** | 18+ | Node.js 20.x | yt-dlp 的 JS 挑战求解运行时 |
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
├── pom.xml                                  # Maven 构建文件 (Spring Boot 3.0.0)
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
    template: "%(title).80B [%(height)sp].%(ext)s"  # 文件名模板

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
    clients: web_embedded,tv,tv_downgraded,mweb,android_vr

  # --- 下载参数 ---
  download:
    retries: 3                           # 下载失败重试次数
```

### 4.3 配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `ytdlp.output.dir` | String | `F:/学习资料/套图/打碟` | 下载文件保存目录，需有写入权限 |
| `ytdlp.output.template` | String | `%(title).80B [%(height)sp].%(ext)s` | yt-dlp 文件名模板 |
| `ytdlp.bin.yt-dlp` | String | `src/main/resources/yt-dlp.exe` | yt-dlp 二进制路径 |
| `ytdlp.bin.ffmpeg` | String | `src/main/resources/ffmpeg.exe` | ffmpeg 二进制路径 |
| `ytdlp.bin.cookies` | String | `src/main/resources/cookies.txt` | cookies 文件路径 |
| `ytdlp.bin.node` | String | `D:/dev/nvm/nodejs/node.exe` | Node.js 路径，需根据实际安装位置修改 |
| `ytdlp.pot.port` | int | `49300` | PO Token Provider HTTP 端口 |
| `ytdlp.youtube.clients` | String | `web_embedded,tv,...` | 客户端策略，逗号分隔 |
| `ytdlp.download.retries` | int | `3` | 下载重试次数 |

> **提示：** 可创建 `application-local.yml` 覆盖个人配置（已加入 `.gitignore`）。

---

## 5. 安装与构建

### 5.1 前置条件

确保已安装 JDK 17、Maven 3.8+、Node.js 18+，并完成 [环境变量配置](#23-环境变量配置)。

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

```bash
# 编译
mvn clean compile

# 打包为可执行 JAR（跳过测试）
mvn package -DskipTests
```

构建成功后，JAR 文件位于 `target/yt-dlp-java-1.0.0.jar`。

---

## 6. 启动服务

### 6.1 Maven 直接运行

```bash
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

---

## 7. API 使用指南

### 7.1 API 端点总览

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/health` | 健康检查 |
| `GET` | `/api/formats` | 获取视频可用格式列表 |
| `POST` | `/api/download` | 执行下载 |

### 7.2 获取格式列表

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

### 7.3 执行下载

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

## 8. 下载示例

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

## 9. 架构设计

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

## 10. 常见问题

### 10.1 启动时报 "找不到 config.properties"

本项目已从 `config.properties` 迁移到 `application.yml`。如果出现此错误，说明使用了旧版本代码，请确保项目中不存在旧的 `Config.java` 文件。

### 10.2 "无可用格式: cookies / PO Token / 客户端问题"

此错误表示所有客户端均未获取到格式列表。排查步骤：

1. **检查 cookies**：确认 `cookies.txt` 文件存在且有效，或改用 `cookieMode: FIREFOX`
2. **检查 PO Token Provider**：访问 `http://127.0.0.1:49300/ping`，确认返回 200
3. **检查 Node.js**：确认 `ytdlp.bin.node` 路径指向有效的 `node.exe`
4. **检查网络**：确认能正常访问 YouTube

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
