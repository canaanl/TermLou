# TermLou

> 一个为**无编程能力的人**打造的 Android 终端 —— 把 Debian GNU/Linux、文件管理、抓包分析、脚本浮窗，全部装进一个"点一下就能用"的 App。
>
> An Android terminal built for **people who don't write code** — a full Debian GNU/Linux environment, file manager, network inspection and script-driven floating UI, all inside a single App.

![Version](https://img.shields.io/badge/version-4.3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen)
![minSdk](https://img.shields.io/badge/minSdk-26-orange)
![Language](https://img.shields.io/badge/Kotlin-2.0.21-purple)
![License](https://img.shields.io/badge/license-GPL--3.0-red)

**TermLou = Termux 终端模拟 + proot Debian 13 (trixie) + 文件管理器 + 命令拨轮 + 脚本浮窗 + VPN 抓包**

本项目从零构建一个 "Android 上的 Debian"：不需要 root、不需要刷机，安装 APK 即可在手机上获得一个完整的 Linux 环境，并通过**命令拨轮（Command Wheel）**把终端操作压缩成"滚动 + 点一下"，通过 **termlou-ui 脚本浮窗**让 Shell 脚本直接弹出原生 Android 对话框。

---

## 📌 目录 Table of Contents

- [核心亮点 Highlights](#-核心亮点-highlights)
- [技术栈 Tech Stack](#-技术栈-tech-stack)
- [架构 Architecture](#-架构-architecture)
- [功能详解 Features](#-功能详解-features)
- [使用方法 Usage](#-使用方法-usage)
- [开发过程中遇到的问题 Problems & Solutions](#-开发过程中遇到的问题-problems--solutions)
- [版本历史 Changelog](#-版本历史-changelog)
- [构建 Build](#-构建-build)
- [常见问题 FAQ](#-常见问题-faq)
- [许可证 License](#-许可证-license)

---

## ✨ 核心亮点 Highlights

| | |
|---|---|
| 🎡 **双层命令拨轮** | 无限循环滚动，卡片即按钮，点击即执行；**命令组**对上自动展开成第二层乳黄拨轮，无需理解命令层级 |
| 🐧 **真 Debian 环境** | 内嵌 Debian GNU/Linux 13 (trixie) rootfs + PRoot root 模拟，无需 root / 刷机 |
| 📁 **文件管理器 = Linux 文件系统窗口** | 手机侧目录与 Linux 的 `/workspace` 同一实体，导入导出即时互通 |
| 💬 **脚本驱动浮窗** | Shell 里一句 `termlou-ui --title "确认" --button "OK"` 就能弹出原生 Android 浮窗（`TYPE_APPLICATION_OVERLAY`），支持输入/单选/多选/开关/ANSI 彩色输出 |
| 🌐 **VPN 抓包与过滤** | 内置 `VpnService` + SOCKS5 代理 + DNS 拦截 + 域名/IP 阻断 + 实时流量日志，按 App 抓包 |
| 🎨 **启动像素工坊** | 手绘 96×80 点阵 + 照片转像素（Sobel/Otsu/高斯/形态学）+ 粒子飞入动画，自定义启动画面 |
| 🧱 **零 XML 布局** | 全部 UI 由 Kotlin 程序化构建（`UiBuilder`），四 Tab 架构 |
| ⚙️ **原生 PTY 组件** | 自写 JNI `libtermux.so`（`termux_pty.cpp`），作为 Termux `terminal-view` 渲染器的 PTY 后端 |
| 🛡️ **安全加固** | 路径逃逸防护、输出流 128KB 截断、命令超时强杀、资源上限 |

**设计理念（Design Philosophy）**：项目的全部努力都指向一个目标 —— **命令输入的快捷化和傻瓜化**。拨轮让"知道该点什么"代替"会打命令"，命令组让"任务"代替"记忆命令层级"，脚本浮窗让"Shell 脚本"拥有原生 UI。

---

## 🛠 技术栈 Tech Stack

| 层 | 技术 |
|----|------|
| 语言 | Kotlin 2.0.21（模块 `app` + `workspace`）+ C++17（JNI / NDK） |
| 构建 | Gradle 8.4 / Android Gradle Plugin 8.3.2 / CMake 3.22.1 / NDK 25–26 |
| Android | compileSdk 34 · targetSdk 34 · minSdk 26 · arm64-v8a |
| 终端 | `com.github.termux.termux-app:terminal-view:v0.118.3`（上游渲染器 + 自写 PTY） |
| UI | 程序化视图（零 XML）；`androidx.core-ktx` / `appcompat` / `material` / `recyclerview` / `lifecycle-runtime` |
| 文件 | `androidx.documentfile`（SAF 导入）、`commons-compress`（tar 解包）、`xz`（tar.xz 流式解压） |
| 数据 | `SharedPreferences` + JSON（命令库 v2 格式） |
| 静态分析 | detekt 1.23.1 |
| 测试 | JUnit 4 · MockK · kotlinx-coroutines-test · Espresso（12 个单元测试） |
| CI | GitHub Actions（lint + test + assembleDebug） |

---

## 🏗 架构 Architecture

### 模块结构 Module Layout

```text
TermLou
├── app/        (com.workspace.proot)  产品层 —— 面向用户的 App
│   ├── MainActivity          单 Activity 四 Tab 壳，实现 TerminalSessionClient / TerminalViewClient
│   ├── UiBuilder             程序化 UI 工厂（状态栏 / 四 Tab / 拨轮 / 设置 / 文件列表）
│   ├── TerminalManager       proot 会话装配、.bashrc 幂等注入、Ctrl 模式、runInProot
│   ├── TermlouDirs           .termlou IPC 目录定义（filesDir/.termlou → /termlou）
│   ├── DistroVersion         从 rootfs /etc/os-release 解析发行版 codename（trixie）
│   ├── FileListManager       /workspace 文件浏览、导入导出、删除
│   ├── ShortcutManager       快捷键行 + 命令拨轮装配 + 命令执行（转义解析）
│   ├── WheelController/Adapter  无限循环拨轮（Int.MAX_VALUE 取模 + 空槽位）+ TUI 感知推荐
│   ├── CommandRecommender    三信号加权推荐（频次 + 序列 + 近因）
│   ├── ShortcutSettingsActivity / ShortcutGroupActivity   命令库 / 命令组管理
│   ├── SettingsManager       设置项 + 命令库 JSON v2 存取
│   ├── ScriptDialogSpec/Renderer/Overlay  脚本浮窗协议 + 渲染 + 覆盖层
│   ├── DialogMakerActivity   弹窗工坊（图形化设计 termlou-ui 对话框）
│   ├── OverlayBridge / ClipboardBridge  文件 IPC 桥（req/res + FileObserver + 轮询兜底）
│   ├── SplashView/Letters/Tokens/Maker  启动画面渲染 + 像素工坊（手绘 + CV 管线）
│   ├── NetVpnService / MiniSocks5Server / DnsParser / DnsMap / BlockRules  VPN 抓包栈
│   ├── CommandTileService / LauncherTileService / TileDrawer  快捷设置磁贴
│   ├── TermlouCommandRunner  磁贴命令无头执行服务
│   └── TermKeepAliveService / KeepAliveWakeLock  前台保活
│
└── workspace/  (me.rerere.workspace)  可复用库层 —— "Android 托管 Linux" 工具箱
    ├── WorkspaceManager / WorkspaceFileSystem   安全文件抽象（严格根目录约束）
    ├── ProotShellRunner / WorkspaceShellRunner  可插拔命令执行器（Host / PRoot）
    ├── RootfsInstaller                           下载 + 手写流式 tar 解包器
    ├── RootfsPatcher                             幂等 rootfs 修补（DNS / hosts / group / 权限）
    └── src/main/cpp/termux_pty.cpp               libtermux.so —— 原生 PTY JNI
```

### 启动流程 Boot Flow

```text
MainActivity.onCreate
  └─ UiBuilder 构建全部界面（零 XML）
  └─ SettingsManager.load()（字号 / 初始命令 / 磁贴命令 / 保活 / 命令库 JSON）
  ├─ rootfs 不存在 → 显示 Install RootFS 界面
  │      └─ RootfsExtractor 解包 assets/rootfs.tar.gz → filesDir/workspace/linux
  └─ rootfs 就绪 → SplashView 粒子启动动画
        └─ startShell()（IO 协程）
             ├─ 迁移旧目录 / 清理缓存 / 迁移旧 .termlou
             ├─ .bashrc 幂等注入（# TERMLOU_V6 标记，保留用户改动）
             ├─ 安装 termlou-ui / termlou-clipboard 到 /usr/local/bin
             ├─ 同步 DNS → RootfsPatcher
             └─ 创建 TerminalSession(proot) → attachSession(TerminalView)
```

### PRoot 启动与终端连接 How the terminal connects

`TerminalSession` 以 `libproot_exec.so` 为可执行程序，参数包含 `-r <rootfs>`、`-w /workspace` 及一系列 bind mount（`workspace→/workspace`、`tmp→/tmp`、`.termlou→/termlou`、`/dev /proc /sys /etc/hosts`）。会话交由**自写**的 `libtermux.so`（`termux_pty.cpp`）处理：`posix_openpt` 建 PTY → `fork()` → 子进程 `setsid`/`TIOCSCTTY`/`dup2` 后 `execve` proot。该 JNI 的包名与函数签名完全兼容 Termux 上游，因此可直接复用 `terminal-view` 的渲染器。apt 源 codename 从 rootfs 的 `/etc/os-release` 动态解析（`DistroVersion.kt`），自动匹配 trixie 等。

### 文件管理器 ↔ Linux 文件系统

文件管理器**不经过 proot**，直接浏览 `filesDir/workspace` —— 这正是 proot bind mount 到 `/workspace` 的同一目录。所以手机侧导入/导出/删除，Linux 侧即时可见；rootfs 本体（`linux/`）与 `tmp/` 在列表中隐藏。

### 脚本浮窗 IPC

`.termlou` 目录（`filesDir/.termlou` → `/termlou`）作为 Android 与 proot 间的原子 JSON 交换区：`termlou-ui` 脚本写入 `req/<id>.json` → `OverlayBridge` 的 `FileObserver` 触发 → 渲染 `TYPE_APPLICATION_OVERLAY` 浮窗 → 结果写回 `res/<id>.json` → 脚本读取并打印到 stdout。剪贴板同理（`termlou-clipboard`）。

### 安全设计 Security Design

- **路径逃逸防护**：`WorkspaceFileSystem.resolvePath` 规范化路径后做 canonical-path 包含校验；tar 解包拒绝 `..` 段与逃逸路径
- **输出截断**：stdout/stderr 各限 128KB，防止 OOM 与上下文爆炸
- **超时强杀**：命令超时 `destroyForcibly()`，协程取消时 join 收集线程防泄漏
- **注入安全**：proot 内命令以位置参数传递（`cd -- "$1" && eval "$2"`），杜绝 shell 引号/转义问题

---

## 📖 功能详解 Features

### 1. 终端 Terminal

- **快捷键行**：`/` `Tab` `Esc` `Ctrl` 常驻 + 方向键（单按移动光标，Ctrl+方向跳单词）
- **Ctrl 模式**：按 Ctrl 进入模式（按钮变红），再按一键即发送 Ctrl+该键
- **初始命令**：设置中配置启动时自动执行的命令；从磁贴启动时改为执行磁贴命令
- **转义字符**：命令支持 `\n` `\r` `\t` `\e` `\cX`（Ctrl+X）
- **OpenCode AI**：一键安装 CLI，终端输入 `opencode` 即用 AI 助手

### 2. 命令拨轮与命令组 Command Wheel & Groups

- **双层拨轮**：下层 = 命令卡片 + 组卡片（📁）；组卡片滚到正对 → 自动展开上层乳黄拨轮（`#FFF3D6` + 琥珀光晕）
- **点击即执行**：无限循环滚动，中央卡片自动高亮，点击执行
- **TUI 感知推荐**：`TuiStateDetector` 扫 `/proc` 识别前台程序，`CommandRecommender` 按频次/序列/近因三信号加权把最可能需要的命令滚到中央
- **组内体验**：成员执行后保持展开，组离开高亮才收起；组对正时点组无操作
- **命令库管理**：设置页管理命令与组；拖拽排序、拖拽合并成组、加入/解散组、组内成员排序/编辑/删除
- **合并双保险**：纵向重合 ≥80% **且** 同卡稳定悬停 ≥1s 才判定合并（armed 橙框），松手弹窗确认；未 armed 松手 = 排序
- **组不变式**：组必须 ≥2 成员 —— 删除/移出到剩 1 个时**自动还原成命令**

### 3. 文件管理器 File Manager

- `/workspace` 完整导航：进入/返回上级、文件夹黄色标记、大小显示、渐入动画
- **导入**：底部按钮导入文件（任意格式）/ 递归导入文件夹
- **导出/分享**：文件直接分享；文件夹自动打包 ZIP 后分享（微信/QQ/邮件等）
- **删除**：文件直接删；非空文件夹弹窗确认
- **MIME 识别**：txt/json/xml/html/jpg/png/gif/pdf/zip/md/csv/py/sh 等自动识别

### 4. 脚本浮窗 Script Dialog (termlou-ui)

- Shell 一行命令弹出原生 Android 浮窗：`termlou-ui --title "确认" --message "继续？" --button "确定=ok=primary" --button "取消=cancel"`
- 控件：`--input` 输入框、`--select` 单选、`--check` 多选、`--toggle` 开关、`--output FILE` 带 ANSI 彩色的文本输出
- 主题 `dark/light/glass`、强调色、圆角、位置（center/bottom）、动画、超时自动关闭
- **弹窗工坊**（`DialogMakerActivity`）：图形化设计对话框，实时预览，真机测试浮窗，一键导出 `termlou-ui` 命令
- **剪贴板桥**：`termlou-clipboard "文本"` 或 `echo "文本" | termlou-clipboard` 直写系统剪贴板

### 5. 网络抓包与过滤 Network

- **按 App 抓包**：`VpnService` + `addAllowedApplication` 精选要抓的 App
- **SOCKS5 代理**：内置 `MiniSocks5Server`（TCP CONNECT + UDP ASSOCIATE），可选上游代理
- **DNS 拦截与映射**：解析 DNS 查询/响应，建立域名↔IP 映射
- **阻断规则**：按 IP / 域名后缀阻断，实时生效
- **流量日志**：实时流量列表（域名/IP、协议、端口、上下行字节、状态），支持按 IP/域名阻断，长按菜单操作

### 6. 启动工坊 Splash Workshop

- **手绘点阵**：96×80 孔板，手指绘制像素
- **照片转像素**：导入照片 → 灰度 → Sobel 边缘检测 → 非极大值抑制 → 双阈值 → 形态学闭运算 → Otsu 自适应阈值，支持轮廓/块面/混合三风格 + 反色，捏合缩放取景
- **粒子动画**：每个亮点从随机边缘飞入，品牌绿→青渐变，呼吸光晕

### 7. 设置 Settings

| 设置项 | 说明 |
|--------|------|
| 字号 | 五档（极小→极大），终端与文件列表即时同步 |
| 初始命令 | 启动时自动执行的 Shell 命令 |
| 磁贴命令 | Quick Settings Tile 点击执行的命令（独立于初始命令，可一键重置为相同） |
| 快捷启动 | 选择常用 App，一键启动（磁贴抽屉） |
| 弹窗工坊 | 图形化设计脚本浮窗 |
| 启动工坊 | 自定义启动画面 |
| 网络上游代理 | SOCKS5 上游（留空走内置直连 + 抓包/阻断） |
| 后台持久化 | 前台通知服务保活（低优先级、无声无震动，需 Android 13+ 通知权限） |
| 存储占用 | 查看 `/workspace/` 磨砂饼图统计 |

### 8. Quick Settings Tile 快速设置磁贴

- **命令磁贴**：通知栏一键执行预设命令（支持冷启动容错、原子 pending 文件）
- **启动器磁贴**：一键打开常用 App（单收藏直启，多收藏弹出抽屉）

---

## 📱 使用方法 Usage

### 快速开始 Quick Start

1. 安装 APK，首次打开点击 **Install RootFS** 部署 Debian 根文件系统（约 5 分钟，内嵌 rootfs 本地解包）
2. 部署完成自动进入终端（root 权限，工作目录 `/workspace`）
3. 需要后台常驻时在设置开启"后台持久化"

### 手势总览 Gestures

| 操作 | 手势 |
|------|------|
| 终端 → 文件 | 终端页向左滑 |
| 文件 → 终端 | 文件页向右滑 |
| 文件 ↔ 网络 ↔ 设置 | 左右滑动切换 |
| 打开命令拨轮 | 快捷栏**短左滑**（>50dp） |
| 打开命令库管理 | 快捷栏**长左滑**（>100dp） |

### 命令拨轮操作 Command Wheel

1. 终端页对快捷栏短左滑打开拨轮
2. 水平滚动浏览卡片，中央卡片自动高亮（黄色霓虹发光）
3. 点击中央卡片执行；组卡片对正时上层自动展开成员拨轮

### 脚本浮窗示例

```bash
# 确认对话框
termlou-ui --title "确认" --message "要继续吗？" --button "确定=ok=primary" --button "取消=cancel"

# 带输入与选择
termlou-ui --title "部署" --input "分支=branch" --select "环境=env" --option "prod" --option "staging" --button "提交=submit=primary"

# 把命令输出以彩色文本展示
ls -l > /tmp/out.txt && termlou-ui --title "列表" --output /tmp/out.txt --button "关闭"

# 写剪贴板
echo "https://example.com" | termlou-clipboard
```

弹窗工坊（设置 → 弹窗工坊）可图形化设计以上对话框并一键导出命令。

### 命令库 / 组管理 Command Library

| 操作 | 方式 |
|------|------|
| 新建命令 | 命令库底部「＋ 新建快捷命令」 |
| 编辑命令 | 点击列表项 |
| 排序 | 长按拖动上下移动 |
| 合并成组 | 拖拽命令卡片到另一命令上，hold 1 秒 → 松手弹窗确认 |
| 加入已有组 | 在组管理页左滑「移出命令组」后……（或拖拽到组卡片） |
| 解散组 | 命令库中组卡片左滑 → 弹窗确认 |
| 组内管理 | 点击组卡片进入组页：成员排序 / 编辑 / 删除 / 移出 |

---

## 🧩 开发过程中遇到的问题 Problems & Solutions

> 记录开发中踩过的关键坑与最终判定，供后续维护参考。

### 1. 拖拽排序 off-by-one 🔴
**现象**：向下拖动排序时，目标落点总是偏一格。
**根因**：`moveItem` 先移除再插回，插回位置向下移动时少了 `to-1` 的偏差。
**解法**：统一插回 `items.add(to, item)`，上下对称，均"占据目标卡槽位"。

### 2. 合并 vs 排序的几何冲突 🟠
**现象**：想实现拖拽"实时换位"（Launcher 式），但合并判定需要纵向重合 ≥80%，而跨 50% 中线必然先触发换位，合并永远无法成立。
**解法**：放弃实时换位，改为**松手触发**；合并采用双保险 —— 纵向重合 ≥80%（天然唯一，与排序互斥）**且** 同卡稳定悬停 ≥1s → `mergeArmed`（橙色高亮），松手弹窗确认合并；未 armed 松手 = 排序。

### 3. 拨轮惯性滚动破坏手感 🟠
**现象**：普通 `RecyclerView` 快速甩动有惯性滑动，卡片难以精确停在中央。
**解法**：`NonFlingRecyclerView.fling()` 返回 false 禁惯性；`SkipEmptySnapHelper` 松手吸附就近格；空槽位自动跳过。

### 4. 命令组仅剩 1 成员 ⚪
**需求**：组内不能只有一项。
**解法**：建立不变式 **组 ≥2 成员** —— 删除/移出使成员降到 1 个时，自动把组还原成单条命令（占原组位置）；0 个时解散。

### 5. 软键盘问题 ⚪
**现象**：终端唤起软键盘时布局被遮挡、偶发闪退。
**解法**：`AndroidManifest.xml` 设置 `windowSoftInputMode="stateAlwaysHidden|adjustResize"`（勿回退）。

### 6. 存储占用统计不准 ⚪
**现象**：`du` 统计把 bind mount 的目录重复计算。
**解法**：`dirSizeExcluding` 排除挂载目录 + try/catch 容错。

### 7. 旧命令库 JSON 兼容 ⚪
**现象**：命令库升级为"命令/组"模型后，旧版单命令 JSON 无法解析。
**解法**：sealed `ShortcutItem { Command / Group }` 模型 + JSON v2 格式，旧格式自动迁移为 `Command`。

### 8. proot 命令注入的引号问题 ⚪
**现象**：把命令拼进 shell 字符串容易遇到空格/引号/转义地狱。
**解法**：`/usr/bin/env -i` + `/bin/bash -l -c "cd -- \"$1\" && eval \"$2\""`，命令与工作目录以**位置参数**传入，彻底规避转义。

### 9. rootfs 安装中断/失败 ⚪
**现象**：下载/解包中途失败会留下残缺目录。
**解法**：下载到暂存目录后原子 `renameTo`；`RootfsPatcher` 全部幂等 —— 先查内容再写，保留用户改动。

### 10. 大文件读入 OOM ⚪
**现象**：超大输出（build 日志等）导致内存暴涨。
**解法**：stdout/stderr 各截断 128KB；超时 `destroyForcibly()`；协程取消时 join 收集线程防泄漏。

### 11. 文件路径逃逸 🛡
**现象**：越权访问 rootfs 边界之外。
**解法**：所有路径规范化后做 canonical-path 包含校验；tar 解包拒绝 `..` 段；列表隐藏 dotfile 与 `linux`/`tmp`。

### 12. .termlou 污染 workspace 🛡
**现象**：`workspace/.termlou` 暴露在文件管理器中，用户可见且易误删。
**解法**：迁至 `filesDir/.termlou`（`TermlouDirs`），proot 绑定为 `/termlou`，文件列表隐藏 dotfile，升级时自动迁移旧数据。

---

## 📋 版本历史 Changelog

| 版本 | versionCode | 内容 |
|------|------------|------|
| **4.3.0** | 430 | 全量中英双语（`strings.xml` + `values-en`，首次跟随系统，设置页语言开关🇨🇳/🇺🇸，含网页/脚本/通知/磁贴；CI 硬编码中文门禁） |
| **4.2.0** | 420 | LAN 服务（`WsServer` 手搓 HTTP+WebSocket 同端口、`xterm.html` 离线终端、文件浏览上传下载、账号密码/token 鉴权、8080→8099 自动避让）；VPN 流量落盘 `workspace/vpn-flows.json`（仅开时创建、开时清、关即删）；三玻璃弹窗统一为原生 `AlertDialog`（删 `FrostedCard`/RenderScript） |
| **4.1.2** | 412 | VPN 流量落盘 `workspace/vpn-flows.json`（仅开时创建、开时清、关即删） |
| **4.1.1** | 411 | VPN 抓包流导出 `workspace/vpn-flows.json` 供 agent 读取 |
| **4.1.0** | 410 | `.termlou` 迁至 `filesDir/.termlou`（`TermlouDirs` + 绑定 `/termlou`，隐藏 dotfile，自动迁移）；弹窗工坊删"保存模板"、底部三按钮品字；`UbuntuVersion` 重命名为 `DistroVersion` |
| **4.0.0** | 400 | rootfs 大裁剪：47.4 MiB → 25.85 MiB（删 2537 文件/60.3MB，保留 bash/libc/gconv/C.utf8/usr-merge） |
| **3.2.8** | 129 | 命令组不变式：成员减到 1 自动还原成命令（统一 删除/移出 两条路径） |
| **3.2.7** | 128 | 修复拖拽排序 off-by-one（`moveItem` 插回位置修正） |
| **3.2.6** | 127 | 交互打磨：合并双保险（80% 重合 + 1s 悬停）、非 armed 松手=排序、组内编辑弹窗、左滑弹窗复原、禁惯性滚动 |
| **3.2.5** | 126 | 命令组全量：双层拨轮 + 组自动展开、组管理页、SettingsManager 新模型、JSON v2 |
| **3.2.4** | 125 | 存储占用统计修复（`dirSizeExcluding` + try/catch） |
| **3.2.3** | 124 | 矢量启动图标（mipmap-anydpi-v26 + drawable） |
| **3.2.2** | 123 | 拨轮卡片改为按钮（点击即执行） |

---

## 🛠 构建 Build

前置：JDK 17、Android SDK + NDK（`local.properties` 配置 `sdk.dir`）。

```powershell
# Windows PowerShell
$env:JAVA_HOME = "<path-to-jdk-17>"
.\gradlew.bat :app:assembleRelease --console=plain   # Release APK
.\gradlew.bat :app:testDebugUnitTest :workspace:testDebugUnitTest  # 单元测试
.\gradlew.bat lintDebug  # Lint
```

产物：`app/build/outputs/apk/release/app-release.apk`（Release 需 `keystore.properties`，见 `keystore.properties.example`）

签名：`keystore.properties` 读取签名配置（仓库外，勿提交）。release 构建启用 R8 压缩与 ProGuard。

CI：`.github/workflows/build.yml` 在 push 到 `main` 时执行 `lintDebug` → `test` → `assembleDebug`，产物上传为 artifact。

---

## ❓ 常见问题 FAQ

**RootFS 安装失败？**
检查存储空间；安装为本地解包内嵌 rootfs（约 5 分钟）。失败可重启 App 重试。

**终端卡顿或无法输入？**
点击终端区域唤起软键盘；仍不行则重启 App。

**Quick Settings Tile 点击无反应？**
确认磁贴命令已保存、磁贴已添加到快捷面板；磁贴为灰色时表示未生效。

**后台持久化耗电吗？**
仅维持一个低优先级前台通知，对电量影响极小（`KeepAliveWakeLock` 90s 超时自释放）。

**文件导出后在哪里？**
导出走 Android 分享菜单，由接收方决定保存位置。

**如何备份数据？**
用文件管理器「导出文件夹」把整个 `/workspace/` 打包为 ZIP 分享到云盘/电脑。

---

## 📄 许可证 License

本项目基于 [GPL-3.0](LICENSE) 开源。

- 终端渲染基于 [termux-app terminal-view](https://github.com/termux/termux-app)（GPL-3.0）—— 本项目复用其渲染器并自写 PTY 后端（`termux_pty.cpp`），因此整体分发遵循 GPL-3.0。
- 内嵌 Debian rootfs 来自 Debian 官方（见 `app/src/main/assets/rootfs.tar.gz`，`usr/lib/os-release` 为 `Debian GNU/Linux 13 (trixie)`）。
- 静态 `curl`（`assets/curl_aarch64`）与 CA 证书（`cacert.pem`）随 rootfs 注入，仅用于首次启动时的依赖自愈。

---

**Made by Lou with ♥** — 让终端不再吓人。
