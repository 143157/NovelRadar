<p align="center">
  <img src="docs/assets/user-app-icon.png" width="112" alt="NovelRadar 应用图标" />
</p>

<h1 align="center">NovelRadar 小说雷达</h1>

<p align="center">
  Android 8.0+ 的本地小说阅读器 · 多书源聚合搜索 · 书架与离线下载
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" />
  <img alt="License" src="https://img.shields.io/badge/License-AGPL--3.0-blue" />
</p>

> **当前 Release 是 Debug 测试包，不是生产签名版本。** 仅用于开发验证与个人试用。

## 项目简介

NovelRadar 是一个纯手机端的 Android 小说阅读器。它把搜索、解析、阅读、书架和整本下载都放在设备本地完成，不依赖自建服务器。

应用以 SoNovel JSON 书源规则为配置层：用户可以导入、启停和测试书源，应用并发搜索所有可用来源，将同一本书的多来源结果聚合展示，并在单个来源失败时保持其他来源可用。

## 主要功能

- **多书源聚合搜索**：并发请求已启用书源，流式展示搜索进度，按书名与作者聚合、去重和排序。
- **书源管理**：导入 / 校验 / 启停 SoNovel JSON 规则，单源测试，展示健康状态、协议与最近响应。
- **书籍详情与目录**：封面、作者、简介、更新时间、来源切换、目录正倒序与章节搜索。
- **双模式阅读器**：分页 / 纵向滚动，字号、行距、页边距、亮度、日间 / 纸张 / 夜间主题。
- **阅读进度**：按书籍、来源、章节和字符位置保存，支持章节内位置恢复与跨来源回退。
- **整本下载**：WorkManager 前台任务，支持暂停、继续、取消、失败重试、断点恢复和离线阅读。
- **本地缓存**：Room 保存元数据，章节正文独立文件原子写入，避免中断产生半文件。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 语言与 UI | Kotlin 2.4.10、Jetpack Compose（Material 3）、Navigation Compose、ViewModel / StateFlow、Coroutines |
| 数据持久化 | Room 2.8.5 + KSP（数据库 1→5 迁移）、DataStore Preferences |
| 后台任务 | WorkManager 2.11.2、前台服务、通知、可恢复下载检查点 |
| 网络与解析 | OkHttp 5.3.0、Jsoup 1.23.2、re2j 1.8、Moshi 1.15.2、Coil 3.3.0 |
| 规则与脚本 | SoNovel JSON 书源、CSS / XPath、分页、正文净化、QuickJS 隔离运行时 |
| 工程 | AGP 9.4.0、Gradle 9.6.0、compileSdk 37.2、targetSdk 36、minSdk 26 |

### 关键实现

- **书源引擎**：`SourceEngine` 负责规则请求构造、字符集识别（UTF-8 / GB18030 等）、搜索 / 详情 / 目录 / 正文解析、分页与错误分类。
- **脚本安全**：`@js` 规则在 QuickJS 独立运行时执行，不注册 Java / Android 对象桥接；限制 300ms 执行时间、32MB 内存和输入输出大小。
- **并发搜索**：`AggregateSearchUseCase` 使用 `channelFlow` + `Semaphore(6)` 流式汇总各来源状态，单源失败只更新该源状态。
- **阅读器**：`TextPaginator` 按中文标点与 UTF-16 代理对边界切页；分页与滚动共用同一份布局模型，保证字符位置一致。
- **下载**：`BookDownloadWorker` + `BookDownloadRunner` 按书源限流，逐章持久化检查点，章节正文原子写入。

## 架构

```text
Jetpack Compose UI
        ↓
ViewModel / UI State（StateFlow）
        ↓
Use Cases（搜索 / 详情 / 目录 / 阅读 / 下载 / 书源）
        ↓
Repositories
   ├── Source Engine（规则解析 + 网络）
   ├── Room（书籍 / 章节 / 进度 / 下载任务）
   ├── Chapter File Store（正文文件）
   └── WorkManager（后台下载）
        ↓
OkHttp / Jsoup / XPath / QuickJS Sandbox
```

## 构建

### 环境要求

- JDK 17
- Android SDK Platform 37.2（`compileSdk = 37.2`）
- Android SDK Build-Tools 与 platform-tools
- Gradle 使用仓库自带 Wrapper，无需单独安装

### 本地构建

```powershell
# Windows
.\gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

生成路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

首次构建前，在项目根目录创建 `local.properties`：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

> `local.properties`、签名文件、构建产物和本地 SDK 均已被 `.gitignore` 排除，不应提交。

### 运行单元测试

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

当前测试规模约 **596 个单元测试 / 54 个测试套件**，覆盖书源解析、聚合搜索、Room 迁移、下载恢复、阅读器分页与进度恢复等；另有 Compose / 仪器化测试源码。

## 下载

- Release 页面：<https://github.com/143157/NovelRadar/releases>
- 当前版本：`v0.1.0`（Debug 测试签名，minSdk 26 / targetSdk 36，约 34MB）

安装前请允许浏览器或文件管理器安装未知来源应用。Debug 包与未来正式签名包可能不能原地覆盖安装，升级前请先备份书架与本地章节。

## 目录结构

```text
.
├── app/
│   ├── src/main/java/com/novelradar/app/
│   │   ├── app/          # Application、依赖容器、导航
│   │   ├── domain/       # 领域模型、仓库接口、用例
│   │   ├── data/         # Room、DataStore、文件、下载
│   │   ├── source/       # 书源规则、HTTP、解析、QuickJS
│   │   ├── feature/      # 书架 / 搜索 / 详情 / 阅读器 / 下载 / 书源
│   │   └── ui/           # 主题与通用组件
│   ├── src/test/         # JVM 单元测试
│   └── src/androidTest/  # Compose / 设备测试
├── docs/                 # 设计、计划、书源格式、验证记录
├── gradle/               # 版本目录与 Wrapper
├── vendor/               # QuickJS 本地依赖与许可证
└── tools/                # 本地 Android 工具链脚本
```

## 开发方式

本项目采用**规格驱动 + TDD**的交付方式：先写设计与实施计划，再按任务补测试、实现、回归和验收。

开发过程中使用 **Codex AI 编程代理**协助需求拆解、代码生成、测试补齐与回归验证；维护者负责架构决策、范围控制、代码验收与发布。仓库的 Git 历史中也保留了这一协作过程的记录。

## 许可与第三方

本项目整体以 **GNU Affero General Public License v3.0（AGPL-3.0）** 发布，完整许可文本见 [LICENSE](LICENSE)。

内置的 12 条书源规则移植自 [freeok/so-novel](https://github.com/freeok/so-novel)（AGPL-3.0），来源、变更与上游链接见 [docs/third-party-rule-notice.md](docs/third-party-rule-notice.md)。QuickJS、jsoup、OkHttp 等第三方组件的许可证见 [vendor/quickjs/licenses](vendor/quickjs/licenses) 及各依赖仓库。

如果你希望复用本项目的原创代码但不希望继承 AGPL 义务，请自行移除 `app/src/main/assets/sources/main.json` 中的第三方书源规则，并重新评估整体许可。

## 免责声明

- 本项目不内置小说正文，不提供绕过验证码、登录、付费墙、Cloudflare 或其他访问控制的能力。
- 书源指向的站点内容、可用性与合法性由第三方站点决定；请仅访问你有权访问的公开内容。
- 书源可能失效、改版或限流，应用不保证“全网搜书”的覆盖率或实时性。
- 当前 APK 为开发测试包，尚未完成完整的真机验收；生产使用前请自行评估签名、分发与合规要求。
