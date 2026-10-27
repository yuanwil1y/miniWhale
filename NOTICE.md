# NOTICE / 来源与许可

本应用（「miniWhale」，包名 `ltd.nightsky.whale`）是开放源代码软件，按 **GNU Affero General Public License v3.0**
（AGPL-3.0）发布，完整许可文本见仓库根目录的 [`LICENSE`](LICENSE)。

## 作者

miniWhale 由以下三位作者共同开发，排名不分先后：

- [YuanWil1y](https://github.com/yuanwil1y)
- Claude（Anthropic）
- DeepSeek

源代码：https://github.com/yuanwil1y/miniWhale

本项目与 Anthropic、DeepSeek、MiniMax、Telegram、LINE 均无隶属或合作关系；
文中提到的产品名称和商标归各自所有者。

## 一、衍生的上游项目

### RikkaHub

- 项目：RikkaHub
- 来源：https://github.com/rikkahub/rikkahub
- 使用版本：v3.1.1，commit `ad2c4430d50ba52392b2941367f98ef59614cb73`（`master`，2026-10-24）
- 许可：AGPL-3.0
- 使用方式：本项目的构建骨架（Gradle、`build-logic`、版本目录）、`ai`/`common`/`document` 三个库模块，
  以及 `app` 模块中的数据层、聊天引擎、设置、通知等代码，均直接来自 RikkaHub。
  原始版权归 RikkaHub 作者所有。
  本仓库以单个提交公开发布，不包含开发历史；与上游的血统关系以本文件和 README 为准。

**对本仓库中 RikkaHub 代码所做的修改**（AGPL-3.0 §5 要求注明）：

1. 移除 Firebase / Crashlytics / Analytics 及其 Gradle 插件与 `google-services.json` 依赖；
2. 移除应用内自更新检查（`UpdateChecker`、更新卡片、更新提醒设置项）；
3. 移除 `:web` 模块（内置 Ktor Web 服务与静态前端）及其全部引用；
   原 `me.rerere.rikkahub.web.ApiException` 系列异常搬到 `me.rerere.rikkahub.service` 并去掉 ktor 依赖；
4. `applicationId` 改为 `ltd.nightsky.whale`；应用名改为「miniWhale」；启动图标换成自绘图标；
   深链 scheme 由 `rikkahub` 改为 `whale`；关闭自动备份（`allowBackup=false`）；
5. 新增 `whaleui.nightsky.ltd` 包（原有界面代码与本次新增的接线、工具、调度、记忆等能力），
   以及在 RikkaHub 扩展点（`InputMessageTransformer`、`OutputMessageTransformer`、
   `ChatToolFactory`、Skills、`ChatNotificationManager`）上的接入代码；
6. 精简（2026-10）：删除 `highlight`、`material3`（含 material-color-utilities）、`mediagen`、`oauth`、`search`、
   `speech`、`terminal`、`ui`、`workspace` 库模块，以及 RikkaHub 原版的全部界面、导航、多会话管理、工具审批、
   本地工具、MCP、备份同步、账号登录等功能；聊天服务、请求组装与设置读写按 miniWhale 的单一对话改写。

### goutoujunshi（狗头军师）

- 项目：goutoujunshi（狗头军师）
- 来源：https://github.com/shengjidaguai-china/goutoujunshi（原 powerycy/goutoujunshi）
- 使用版本：commit `6db7354`
- 许可：MIT（许可原文随技能一起放在 `app/src/main/assets/builtin_skills/goutoujunshi/LICENSE`）
- 使用方式：作为内置技能 `goutoujunshi` 打进 APK。相对官方原版做了两处改动：
  把 `skill_view` 的调用写法换成内置工具 `use_skill`，并删除了两篇用不上的参考文档
  （长期记忆脚本、ChatLab）。

## 二、其他第三方组件

本项目还使用了下列开源组件，各自的许可条款随依赖一起分发：

- AndroidX / Jetpack Compose（Apache-2.0）
- Kotlin、kotlinx.coroutines、kotlinx.serialization（Apache-2.0）
- OkHttp（Apache-2.0）
- Room、Paging、DataStore、WorkManager（Apache-2.0）
- Koin（Apache-2.0）
- Coil（Apache-2.0）
- Haze（Apache-2.0）
- Pebble Templates（BSD-3-Clause）
- SnakeYAML（Apache-2.0）
- Apache Commons Text（Apache-2.0）
- SLF4J（MIT）
- requery/sqlite-android（Apache-2.0）
- Guava listenablefuture（Apache-2.0）
- wangfenjin/simple：SQLite FTS5 中文 / 拼音分词扩展，预编译为 `app/src/main/jniLibs/*/libsimple.so`
  （https://github.com/wangfenjin/simple，MIT 或 GPL-3.0+ 双许可，本项目按 MIT 使用；内含 cppjieba（MIT）与拼音数据）。随 RikkaHub 带入
- models.dev 模型数据快照 `app/src/main/assets/models_dev.json`（https://github.com/anomalyco/models.dev，原 sst/models.dev，MIT）。随 RikkaHub 带入

字体：Noto Serif SC（思源宋体）的标题用子集 `app/src/main/res/font/noto_serif_sc_title.ttf`，
由 `app/src/main/fonts-src/` 里的脚本从 notofonts/noto-cjk 的字体裁出字符子集（未改字形）。
Copyright (c) 2017-2024 Adobe (http://www.adobe.com/)。
许可：SIL Open Font License 1.1，全文见 `app/src/main/fonts-src/OFL.txt`（从 notofonts/noto-cjk 的 `Serif/LICENSE` 原样复制）。正文使用系统字体。

图标：**Lucide**（https://lucide.dev，`lucide-static@0.460.0`），ISC License。
只把用到的约 60 个图标的 SVG 路径数据转写进 `app/src/main/java/whaleui/nightsky/ltd/ui/components/WhaleIcons.kt`
（线宽改为 1.75），没有引入整个图标库。许可原文：

```
ISC License

Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT). All other copyright (c) for Lucide are held by Lucide Contributors 2022.

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

自有素材：

- app 图标由 YuanWil1y 提供，AI 生成，按 AGPL-3.0 发布。源图 `app/src/main/icon-src/miniwhale-icon.png`，
  各密度的 `mipmap-*/ic_launcher*.png` 由它裁剪缩放得到；主题图标用的 `ic_launcher_monochrome.xml` 是据此手绘的月牙 + 星剪影。
- 聊天背景图案 `chat_bg_tile.png` 为本项目自绘（海洋小物件线稿），随本项目以 AGPL-3.0 发布。
- 她的默认头像是代码里画的纯白圆形，不附带任何图片。

界面的配色、尺寸、动效数值参考了 Telegram Android（GPL-2.0）的公开主题与源码中的数值，只借用数值与交互规律、
全部自行实现，没有复制其代码、图片或图标资源。本项目与 Telegram 无任何关联。

## 三、分发义务提醒

AGPL-3.0 要求：以二进制形式分发本应用时，必须同时向接收者提供对应的完整源代码；
若通过网络向用户提供服务，也应向该用户提供源代码。修改过的文件必须保留原版权声明并注明修改。
