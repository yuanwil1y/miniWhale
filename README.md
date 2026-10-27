# miniWhale

> 一个只有「她」的 Android AI 陪伴聊天 App：深色、Telegram 风格，她会记事、写日记、按时间主动找你，也会发语音和表情。

作者：[YuanWil1y](https://github.com/yuanwil1y) · Claude（Anthropic）· DeepSeek

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) v3.1.1 修改，按 **AGPL-3.0** 发布（见 [许可与致谢](#许可与致谢)）。

---

## 它是什么

miniWhale 不是通用的 AI 客户端。App 里只有一个对话、一个角色「她」。你给她一份人设（`Soul.md`），她就按这份人设陪你聊天。

和一般的聊天客户端相比，有这些不一样：

- **只有一个她**：没有会话列表，没有助手市场。打开 App 就是和她的对话，历史一直往下接。
- **她有自己的一天**：每天早上醒来、睡前各有一个固定闹钟。她还会自己定闹钟，到点去刷热榜、看天气、写日记，想找你了就主动发消息。不想找你的时候，她可以选择不发。
- **她会记住你**：关于你的事、你们的约定、她自己的事，分三类存成记忆。每晚她会顺手把记忆整理一遍。
- **像在用 Telegram**：深色主题、气泡分组、长按菜单、毛玻璃顶栏，配色和动效数值参考了 Telegram Android。
- **数据只在你手机上**：人设、记忆、日记、聊天记录、表情包都存在本机，可以一键导出成一个 ZIP，换手机再导回来。
- **开箱是空的**：不带示例人设、示例聊天或表情包。她的名字默认是 `default`，头像是一个白色圆形，在她的资料页右上角的铅笔里改。

## 功能

**聊天**
- Telegram 式气泡：同一个人 5 分钟内的消息合成一组，时间嵌在气泡右下角。
- 她的回复每一行单独成一条气泡，一条一条「打」出来，就像真人在手机上连发几句。
- 可以发文字、图片和文件；长按气泡可以回复、复制、删除。
- **语音**：她觉得适合的时候会直接发语音条（MiniMax 语音合成）。设置里可以选音色、调语速。
- **表情包**：导入自己的表情包（WebP），她会按 42 个情绪词挑表情发给你，你也可以从表情面板发给她。

**主动消息与定时任务**
- **Proactive Agent**：用精确闹钟准时叫醒她。她可以主动找你，也可以安静地什么都不发。可以设免打扰时段。
- 定时任务页能看到「她今天的一天」：两个固定任务（早上醒来、睡前）、她自己定的闹钟，还可以添加你自己的任务。

**记忆与日记**
- 记忆分「关于你 / 约定 / 她的」三类，她能自己记、改、忘，你也可以手动添加和编辑。
- 日记是她自己写的，按天存，每段前面带时间。侧边栏会显示她最近的一篇。

**她的资料页**
- 名字、头像、状态，以及一张 **token 热力图**：近 20 周每天和她聊天用掉的 token，像 GitHub 贡献图一样一格一格亮起来。
- 右上角铅笔进入「编辑」：换头像（从相册选，自动裁成正方形）或恢复默认白色头像，改名字（最多 20 个字，留空就是 `default`）。点 ✓ 才保存。
  名字和头像只用于显示（聊天顶栏、侧边栏、资料页、通知），不会写进发给模型的提示词；她是谁由 `Soul.md` 决定。
- 「她」分组显示各个能力是否在运行：
  - **Persona Guard**：每轮对话前注入报时、提醒和防复读；她的回复发出去之前，先做一遍清理（去掉表情符号、括号动作、客服腔）。
  - **狗头军师**：内置技能，来自开源项目 [goutoujunshi（狗头军师）](https://github.com/shengjidaguai-china/goutoujunshi)（MIT）。她用它来「追你」，你也可以找她出感情上的主意。
  - **热点新闻**：她可以刷百度热搜、知乎热榜、B 站热搜和热门视频、IT 之家，看到有意思的再来告诉你。
  - **Proactive Agent**：主动消息开着，而且闹钟、通知等权限都给了，她才能按时找你。

**联网能力**
- **DeepSeek** 负责聊天。她可以用 DeepSeek 的联网搜索和网页读取工具查资料、读网页。内置的供应商只有 DeepSeek。
- **MiniMax**（可选）负责语音合成和画画。她画自己的时候，如果你给她设了头像，会把头像当参考图。
- 天气（Open-Meteo）、每日一诗（今日诗词）：在设置里填上「常住城市」，她就知道你那边的天气。

## 构建与安装

需要：

- JDK 17 或更高（推荐 21；Gradle 9.6 和 AGP 9.4 都要求至少 17）。
- Android SDK Platform 37。
- 首次构建要联网下载依赖：Google Maven、Maven Central、JitPack。

```bash
git clone https://github.com/yuanwil1y/miniWhale.git
cd miniWhale
./gradlew assembleGithubRelease        # Windows 用 gradlew.bat assembleGithubRelease
```

APK 在 **`app/build/outputs/apk/github/release/`**：每个 ABI 一个（`arm64-v8a` 给手机，`x86_64` 给模拟器），另有一个 universal APK。直接装到手机上就能用。

> 没配置自己的签名时，release 包用本机 Android SDK 的**调试密钥**签名（构建日志里会提示一句）。
> 这样的包能正常安装和使用，但别人用他的调试密钥构建的包不能覆盖安装你的，反之亦然。要长期使用或分发，请用自己的密钥签名（见下）。

**用自己的密钥签名**

签名信息不放在仓库里。准备一个 `keystore.properties`：

```properties
storeFile=<你的密钥文件>.jks
storePassword=<密码>
keyAlias=<别名>
keyPassword=<密码>
```

`storeFile` 可以写绝对路径，也可以写相对这个 properties 文件所在目录的路径。然后用下面任意一种方式告诉构建它在哪（按顺序找，找到第一个就用）：

1. 环境变量 `WHALE_KEYSTORE_PROPERTIES=/path/to/keystore.properties`；
2. Gradle 属性 `whale.keystoreProperties=/path/to/keystore.properties`（写在 `~/.gradle/gradle.properties`，或命令行 `-Pwhale.keystoreProperties=…`）；
3. 仓库**上一级**目录的 `_signing/keystore.properties`。

再运行 `./gradlew assembleGithubRelease` 即可。配置了签名时，debug 包也用同一把密钥。

> 生成密钥：`keytool -genkeypair -v -keystore <你的密钥文件>.jks -keyalg RSA -keysize 2048 -validity 10000 -alias <别名>`。**妥善备份，丢了就没法覆盖升级。**

**渠道（product flavor）**

| 渠道 | 说明 |
|---|---|
| `github`（默认） | 正常使用的版本 |
| `play` | 上游留下来的 Google Play 渠道，本项目不上架，一般用不到 |

<sub>`./gradlew assembleGithubDebug` 也能出 debug 包（`app/build/outputs/apk/github/debug/`），只在改代码时用得到。</sub>

## 系统要求

- Android 8.0（API 26）及以上。
  - `targetSdk` / `compileSdk` 37。
  - 动图表情需要 Android 9（API 28）及以上。Android 8.x 上表情只显示静态图。
- CPU：arm64-v8a 或 x86_64（只打包了这两种 ABI）。
- API 密钥：
  - **DeepSeek API Key（必需）**：聊天和联网搜索都用它。到 [DeepSeek 开放平台](https://platform.deepseek.com/) 申请。
  - **MiniMax API Key（可选）**：语音和画画用。不填的话，她只能打字，不能发语音、不能画画。到 [MiniMax 开放平台](https://platform.minimaxi.com/) 申请。

### 填写密钥

密钥只在一个地方填：

**侧边栏 → 设置 → ⚙️ 高级（连接、模型与日志）→ 供应商 → DeepSeek / MiniMax 下的「密钥」行**

点「密钥」行会弹出输入框，粘贴后保存即可。

- 没填 DeepSeek 密钥时，聊天顶栏和「高级」那一行会显示「没填密钥」。
- 同一页还能改接口地址、聊天模型、思考强度、代理，也能看最近的请求日志（日志只存在内存里）。

### 开始

1. 填好 DeepSeek 密钥。
2. **设置 → 📥 导入与数据 → 导入 → Soul.md**，导入你写的人设（纯文本或 Markdown，格式见 [docs/IMPORT_FORMAT.md](docs/IMPORT_FORMAT.md)）。没导入人设时，她只有一句占位人设。
3. 可选：在她的资料页给她起名字、换头像；导入 User.md（关于你的事）、记忆、日记、表情包。

## 权限

| 权限 | 用来做什么 |
|---|---|
| `INTERNET` | 连 DeepSeek、MiniMax，以及热榜、天气、诗词这些公开接口 |
| `ACCESS_NETWORK_STATE` | 定时任务触发前先看有没有网；没网就排队等网 |
| `POST_NOTIFICATIONS` | 她主动发来的消息、回复完成时的通知（Android 13+ 需要你同意） |
| `USE_EXACT_ALARM` | Android 13+：她的闹钟准时响（安装时自动授予） |
| `SCHEDULE_EXACT_ALARM` | 仅 Android 12 / 12L：同上，这两个版本没有 `USE_EXACT_ALARM` |
| `RECEIVE_BOOT_COMPLETED` | 开机后重新登记闹钟；开了「后台常驻」的话，重新拉起常驻服务 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导你把 miniWhale 加入电池优化白名单，避免闹钟被系统杀掉 |
| `FOREGROUND_SERVICE` | 生成回复、后台常驻时运行前台服务 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 「正在生成回复」的前台服务类型 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 「后台常驻」保活服务的类型（你在设置里打开才会运行） |
| `WAKE_LOCK` | 由 WorkManager 自动加入：后台任务运行时保持 CPU 唤醒 |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | 由 AndroidX 自动加入的内部权限，只给本 App 自己用，防止别的 App 冒充系统广播 |

App 里的「权限」页会逐项列出哪些已开启，并带你去系统设置里开。

## 隐私

- **不收集任何数据**：没有统计、没有崩溃上报、没有广告 SDK。上游的 Firebase / Crashlytics / Analytics 和自动更新检查都删了。
- **密钥只存在本机**：API Key 和代理密码用 Android Keystore（AES-GCM）加密后存在应用私有目录，界面上不显示明文，「导出全部数据」也不会导出密钥。
- **数据只存在本机**：人设、记忆、日记、聊天记录、表情包、她的头像都在应用私有目录里。关闭了系统备份和换机迁移（`allowBackup=false`），这些数据不会进云备份。
- **会发到网上的东西，以及发给谁**：

| 发给谁 | 发什么 | 什么时候 |
|---|---|---|
| DeepSeek（`api.deepseek.com`，可在高级设置里改地址） | 人设、相关记忆、聊天上下文、你发的图片 / 文件内容；搜索词、要读的网址 | 每次聊天、主动消息、联网搜索 |
| MiniMax（`api.minimax.cn`） | 要转成语音的文字；画画的描述，她画自己时还会带上你给她设的头像；额度查询 | 发语音、画画时 |
| Open-Meteo | 城市名或坐标 | 查天气时 |
| 百度、知乎、B 站、IT 之家、今日诗词 | 普通的公开请求，不带你的任何信息 | 刷热榜、抽诗时 |
| 你设置的代理 | 以上全部流量 | 开了代理时 |

这些服务怎么处理你的数据，以它们各自的隐私政策为准。

## 导入与导出

**设置 → 📥 导入与数据**

- **导入**：点开是一个底部面板，分两组：
  - **她的人设**：📄 Soul.md / 📄 User.md
  - **内容**：日记 / 记忆 / 聊天记录 / 表情包 ›

  所有导入都只合并、不覆盖（重新导入 Soul.md / User.md 会替换原来那份人设文件）。
- **导出全部数据**：把人设、记忆、日记、聊天记录、表情包打成一个 ZIP。这个 ZIP 就是标准格式，换手机后在各导入项里选它即可。她的名字和头像不在导出范围内。

文件格式见 [docs/IMPORT_FORMAT.md](docs/IMPORT_FORMAT.md)。

## 许可与致谢

### 作者

[YuanWil1y](https://github.com/yuanwil1y)、Claude（Anthropic）、DeepSeek 三位共同开发：从需求、设计、代码到测试，都是三方一起完成的。

### 上游与许可

- 本项目基于 **[RikkaHub](https://github.com/rikkahub/rikkahub) v3.1.1**（commit `ad2c4430`）修改。RikkaHub 以 **GNU AGPL-3.0** 发布，本项目沿用 **AGPL-3.0**，完整许可见 [LICENSE](LICENSE)。
  - 本仓库以单个提交公开，不包含开发历史。对 RikkaHub 做了哪些修改，以及第三方组件清单，见 [NOTICE.md](NOTICE.md)。
  - 构建骨架、`ai` / `common` / `document` 模块，以及数据层、聊天引擎等代码都来自 RikkaHub。感谢 RikkaHub 的作者。
- App 里「设置 → 关于」有「源代码」一行，点开就是本仓库。
- 分发本 App 的二进制（APK）时，请同时提供对应的完整源代码（AGPL-3.0 §6）。

### 第三方

- **[goutoujunshi（狗头军师）](https://github.com/shengjidaguai-china/goutoujunshi)**（原 powerycy/goutoujunshi）：MIT，内置技能「狗头军师」。许可原文在 `app/src/main/assets/builtin_skills/goutoujunshi/LICENSE`。
- **[Lucide](https://lucide.dev)** 图标：ISC。
- **[Noto Serif SC](https://github.com/notofonts/noto-cjk)**（思源宋体）标题子集：SIL Open Font License 1.1，全文在 `app/src/main/fonts-src/OFL.txt`。
- **[wangfenjin/simple](https://github.com/wangfenjin/simple)** SQLite 中文分词扩展：MIT（MIT / GPL-3.0+ 双许可）。
- **[models.dev](https://github.com/anomalyco/models.dev)** 模型数据快照：MIT。
- 其余开源库（AndroidX、Kotlin、OkHttp、Coil、Koin、Room 等）见 [NOTICE.md](NOTICE.md)。
- app 图标由 YuanWil1y 提供（AI 生成），聊天背景图案为本项目自绘，均按 AGPL-3.0 发布。
- 界面的配色、尺寸、动效曲线参考了 **Telegram Android** 的公开数值，全部是自行实现，**没有使用 Telegram 的任何代码、图片或图标**。

## 免责声明

- 本项目是个人开源项目，与 **DeepSeek**、**MiniMax**、**Anthropic**、**Telegram**、**LINE** 都没有任何隶属、合作或背书关系。提到的产品名和商标归各自所有者。
- 你需要用自己的 API 密钥。调用产生的费用，以及你和服务商之间的使用条款，由你自己负责。
- 「她」是 AI 生成的虚构角色，说的话不构成任何专业建议（包括狗头军师给的感情建议）。
- 表情包和头像请只用你有权使用的图片，仅限个人使用，不要分发。

---

## English

**miniWhale** is an Android AI companion chat app built around a single character, "her". It has a dark, Telegram-style UI.

Authors: [YuanWil1y](https://github.com/yuanwil1y) · Claude (Anthropic) · DeepSeek, credited equally.

**What it does**

- One continuous conversation with one character, defined by your own `Soul.md`. The app ships with no persona, chats, memories or stickers.
- Her display name defaults to `default` and her avatar to a plain white circle. Change both from the pencil on her profile page. They are display-only and never sent to the model.
- Grouped bubbles, voice messages (MiniMax TTS) and stickers (your own WebP packs).
- Proactive messages:
  - She has fixed morning and night alarms and can also set her own.
  - She may reach out to you, or decide to stay silent.
  - Quiet hours are respected.
- Memories in three categories, plus a diary she writes herself.
- A profile page with a 20-week token heatmap and the status of Persona Guard, 狗头军师 (a built-in skill from [goutoujunshi](https://github.com/shengjidaguai-china/goutoujunshi), MIT), 热点新闻 (trending news) and Proactive Agent.
- DeepSeek (the only built-in provider) handles chat, web search and page reading. MiniMax (optional) handles voice and drawing.
- All data stays on the device. "Export all data" produces one ZIP that every import row accepts.

**Build and install**

- You need JDK 17+ and Android SDK 37.
- Run `./gradlew assembleGithubRelease`. The APKs are in `app/build/outputs/apk/github/release/`.
- Without your own keystore, the release APK is signed with your local Android debug key, so it installs and runs as is.
- To sign with your own keystore, point the `WHALE_KEYSTORE_PROPERTIES` environment variable or the `whale.keystoreProperties` Gradle property to a `keystore.properties` file (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; `storeFile` may be relative to that file).
- Requirements: Android 8.0+ (minSdk 26, targetSdk 37), arm64-v8a or x86_64. A DeepSeek API key is required and a MiniMax key is optional. Enter them in Settings → ⚙️ 高级 → 供应商 → 密钥.

**Privacy**

- No analytics, crash reporting or ads.
- API keys are encrypted with the Android Keystore and never exported.
- Network traffic goes only to the services listed in the table above.

**License**

- miniWhale is a modified version of [RikkaHub](https://github.com/rikkahub/rikkahub) v3.1.1 and is licensed under the **GNU AGPL-3.0**; see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
- The repository is published as a single commit, without the development history.
- Third-party components:
  - Lucide icons (ISC)
  - Noto Serif SC subset (OFL-1.1)
  - goutoujunshi (MIT)
  - wangfenjin/simple (MIT)
  - models.dev data snapshot (MIT)
- Visual design values were inspired by Telegram for Android. No Telegram code or assets are used.

**Disclaimer**

- miniWhale is not affiliated with or endorsed by DeepSeek, MiniMax, Anthropic, Telegram or LINE.
- You are responsible for your own API usage, its costs, and the rights to any images you import.
