# TermLou

> 一个为**无编程能力的人**打造的 Android 终端 —— 把 Debian GNU/Linux、文件管理、抓包分析、脚本浮窗，全部装进一个"点一下就能用"的 App。
>
> An Android terminal built for **people who don't write code** — a full Debian GNU/Linux environment, file manager, network inspection and script-driven floating UI, all inside a single App.

![Version](https://img.shields.io/badge/version-5.0.42-blue)
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
| **5.0.19** | 519 | 小字说明统一（启动/磁贴/快捷/弹窗/启动页/上游代理，中英同步）；命令组页首个命令与标题栏的 4dp 间隙条去掉；编辑/新建/命名弹窗输入框背景改 FieldStyle 空心描边（不再用 outline 实填，浅色主题不再突兀）；DialogStyler 补 setItems 列表行着色，浅色下「移出命令组/删除命令」弹窗不再白字 |
| **5.0.20** | 520 | 默认 Pi 安装/更新命令重写：加 `--retry 5 --retry-all-errors --retry-delay 2` 网络容错、`tar -tzf` 完整性校验、解到 `/tmp/pi.new` 落盘前验活、按新旧清单差集清理旧版本残留文件与空目录、收尾用 `$HOME/.local/bin/pi` 绝对路径校验（命令以 `\r` 结尾） |
| **5.0.21** | 521 | 命令组左滑「移出命令组/删除命令」弹窗浅色下白字修复（ListView 行着色延后到布局完成）；分段按钮外描边改用 onSurface 40% 透明（深色下边框可见，快捷命令管理/两个工坊二级页一致）；终端/文件/网络三个 tab 键区顶部加分隔线与显示区分离 |
| **5.0.22** | 522 | 应用图标重绘：纯黑背景 + 大品牌绿文件夹，内嵌黑边白线闪电+下划线终端标志；单色图标镂空同步 |
| **5.0.23** | 523 | 应用图标微调：文件夹缩小居中、四周留黑边；闪电与下划线黑边统一等粗；闪电填充改闪电黄，下划线保持白色 |
| **5.0.24** | 524 | 应用图标再调：文件夹收至显示宽度 70%（x29–79、y32–76）；顶部两段台阶压到 8 高；黑边统一单侧 1.0（闪电线宽 2，下划线黑 8 + 白 6） |
| **5.0.25** | 525 | 推荐加轮换分：并列带内由可学习的轮换分裁决（点+0.1封顶1.0、其他×0.95衰减，冷启动回退按最近使用）；后台记猜中率与回放日志（各500条内），备份带轮换分 |
| **5.0.26** | 526 | bashrc 不再整文件覆盖：改为只替换受管段（TERMLOU_START/END），用户与安装器追加的内容永远保留；新增 `~/.term_lou_env` 用户环境兜底文件（app 永不覆盖）；PATH 内建 `~/.opencode/bin`；新增 opencode 官方安装默认命令（curl 安装 + 自动软链到 ~/.local/bin） |
| **5.0.42** | 542 | 图标改横排三迷你标：直接复用 tab 原路径（终端黑／笔记奶白 `#FDF5E6`／地球蓝 `#42A5F5`），monochrome 同步文件夹＋终端 |
| **5.0.41** | 541 | 修换位遗留：showTab 双列表顺序对齐新下标（卡死＋错位根因），状态栏通知守卫放行笔记页；图标三符号居左对齐、铅笔改 45°书写态、下划线细到 1/5 短到 2/3 |
| **5.0.40** | 540 | Tab 换位终端→笔记→文件（1↔2 全量迁移：图标顺序/滑动链/返回键/状态栏/打开直达）；App 图标文件夹内三排小符号（缩小终端＋黄身红尖铅笔＋双蓝地球，monochrome 同步简化）；FAB 改拟态凸起（表层底色＋右下暗投影＋左上高光，＋号保持品牌绿） |
| **5.0.39** | 539 | 修孤儿待办：外部删除笔记文件后 reload 只保留无归属或归属笔记仍在磁盘的待办，并落盘自愈索引（此前只有连索引一起删才干净） |
| **5.0.38** | 538 | 标签胶囊超 8 字循环滚动（短词静态），推荐标签改共用胶囊（同尺寸同色）；编辑页与笔记列表的标签行改自写 TagFlowLayout 换行（不再横向滚动），列表日期移到标题行右侧，编辑页标签区限高（屏高 1/3）超出竖向滚动防挤正文 |
| **5.0.37** | 537 | 待办收进笔记 header 归属：＋待办只写本笔记索引不再插正文，编辑器标签行下设待办区（点行切换/点字改/长按删），重命名重映射、删笔记级联；待办页整行点按切换＋右飞出行动画（下方上滑补位），筛选改 error 红、默认未完成、去“全部”，顶部补横线，行加归属笔记副行；笔记名全局去 .txt，编辑器标题居中 18sp |
| **5.0.36** | 536 | 笔记列表行标签改胶囊＋日期右对齐；通知全迁状态栏（`Notes ｜ …「X」`）：主界面直发，待办/标签子页暂存返回再弹（60 秒超期丢弃）；删各页 transient 提示行 |
| **5.0.35** | 535 | 标签胶囊按内容哈希着色：色相 360 桶、淡色填充＋同色系描边，同标签天然同色；标签筛选页点标签进入后笔记行不再显示标签 |
| **5.0.34** | 534 | 修笔记 tab 内滑动卡死：区域级 OnTouchListener 挂 ViewGroup 上会被消费触摸的 children 屏蔽而失聪，改 TabSwipeLayout 容器级拦截（与文件/网络页同机制） |
| **5.0.33** | 533 | 修笔记显示区消失：内容列误用 LinearLayout 参数挂在 FrameLayout 下、高被压成 0，改 FrameLayout 全幅；根容器补全幅参数让 weight 有界可分 |
| **5.0.32** | 532 | 修滑动链跳过笔记：文件左滑与网络右滑目标改回笔记 tab（5.0.30 下标迁移遗留），恢复 0↔1↔2↔3↔4 双向全通 |
| **5.0.31** | 531 | 笔记并入主界面成正式第 2 位 tab（滑动全通、图标恢复纯指示）：列表 [待办][标签]＋右下角毛玻璃新建 FAB（品牌绿＋），编辑 [＋待办][＋标签]；正文实时落盘、删保存键/草稿/脏标记；状态栏恒 Notes、自带标题栏移除；返回键仅编辑态拦截退列表；标签页点笔记跳回主界面直开 |
| **5.0.30** | 530 | 笔记独立成第 3 个 tab（终端/文件/笔记/网络/设置）：点图标打开笔记页，滑动链保持终端↔文件↔网络↔设置；笔记 tab 复用原文件图标，文件 tab 换文件夹形图标；文件底栏去掉“笔记”键回到两键；标签搜索页与编辑页 header 同款胶囊（列表行 [胶囊][N篇]，笔记头 [胶囊][N篇]，点胶囊回全部标签） |
| **5.0.29** | 529 | 笔记底部加顶部分隔线并改成两套三键：列表 [新建][待办][标签]，编辑 [保存][＋待办][＋标签]；编辑页 header 标签改胶囊显示，新增标签只写 header 索引不再插正文，点击胶囊移除 header 的同时清理正文中的同名 #标签；文件/网络/笔记固定底部键英文用小字号避免换行省略 |
| **5.0.28** | 528 | 笔记本四修：①修正文写不了（编辑区/列表区挂进页面时漏 weight，正文 EditText 高度塌成 0）②底部键全改文件页底栏同款扁平分段条（Bar 无圆角无外框、分隔线全高贯通、同字号同内边距）③删总览页，底栏固定 [新建][保存][待办][标签]（保存只写盘、列表态点保存仅提示文件空；新建在保存左边、列表/编辑态均可建）④编辑态底部上行新增 [＋标签][＋待办]（列表态隐藏）；列表长按只剩重命名；待办页顶部改真搜索（实时按文字过滤），标签页在笔记视图下输入自动退回过滤 |
| **5.0.27** | 527 | 新增笔记本：文件页底部栏第三键进入，workspace/Notes 纯 .txt + 隐藏索引（标签/待办/时间戳，索引丢了打开即按目录重建）；列表态默认（不自动开最近），无文件时点保存提示「文件空」并弹新建窗（留空=时间戳，重名自动加后缀）；编辑态标题下 [＋标签][＋待办] 工具行（挂索引+正文光标处插 #标签/- [ ] 行）；底部 [保存][总览][待办][标签] 四键开工坊式新页：总览（计数+标签饼图图例+待办进度）、待办（顶部新增/行内改/底部已完成未完成过滤）、标签（搜索+点标签筛选） |
| **5.0.18** | 518 | 语言/主题配色改为与后台持久化同款并排行（粗体小标题+动态 emoji 在左、开关在右），去掉独立标题行；两行描述统一为「自动重启后生效 / Takes effect after restart」，删除旧的 lang_sub_zh/lang_sub_en/settings_night_sub |
| **5.0.17** | 517 | 分割线按大标题归位：后台持久化归入「高级」，其与系统信息之间补线；工坊区加「工坊/WORKSHOP」大标题，弹窗/启动页降为小标题（去「工坊」二字）并去掉两者之间的线；全部输入框 hint 调淡（50% 透明），不再像已输入的真实值 |
| **5.0.16** | 516 | 设置页分割线按大标题划分（去掉上游代理↔LAN、启动命令↔磁贴命令间的线，补后台持久化↔系统信息间的线）；命令区加「初始命令/INITIAL COMMANDS」大标题，原「初始命令」改名「启动命令」并与磁贴命令同列小标题；字号滑块由 MD2 绿圆点改 M3 竖条 handle（4dp×44dp，Material3 sliderStyle） |
| **5.0.15** | 515 | 安全审查12项全修：workspace根守卫按解析后路径做（+move target校验+6单测）；RootfsExtractor补tar逃逸校验与链接映射；runInProot复用buildProotArgs（带kill-on-exit）+128KB截断；SOCKS5中继可abort；multipart boundary截断；resize显式子协议；Runner引用配平；OverlayBridge原子计数+rename检查；DnsMap首次映射；globalN分母+绝对平局带；net_spawn关fd；Patcher原子写+组名循环 |
| **5.0.14** | 514 | 修滑动闪烁：删掉5.0.13的filler view，空行色（霜→面板罩→框罩三层离线算好）直接写进容器背景上层，下半格仍用霜色，两层静态LayerDrawable永不切换，无view无显隐，闪烁结构性消失 |
| **5.0.13** | 513 | 空行底与两侧同色：加filler view（上半格等大，仅单层wheel露空行时出现），底色按霜→面板罩→框罩三层叠离线算好逐位一致，显隐挂wheel/upper可见性回调 |
| **5.0.12** | 512 | 回滚：TerminalController整文件回到5.0.9（塌缩机制与按键底改色一次清掉，双层wheel与霜行设计原样回来，去绿等已验收不动） |
| **5.0.11** | 511 | 藏缝：按键区底改与仿真器底同色（夜间从默认配色表实时读257槽、日间纯白），余边上下无色差，肉眼无边界；像素还在，只是看不见 |
| **5.0.10** | 510 | 去绿到底+空半格结构消除：BAND容器改自适应高度（单层wheel收尾塌格、切回按键行恢复，显示区weight吸收，空半格不存在）；全App去绿色填充（分段选中段全空心、实心绿键改绿边框空心，Ctrl武装/LAN运行/抓包红状态保留） |
| **5.0.9** | 509 | 分隔线挪到空心段左边缘：填充段全幅顶到接缝不再被线压，绿填满、线在旁 |
| **5.0.8** | 508 | 修复日间启动崩溃：emulator 在首次layout的onSizeChanged里才创建，构造后直读mColors必空指针；改就绪即刷（未就绪挂一次性全局布局监听重试，全程空安全） |
| **5.0.7** | 507 | 工坊绿填充圆角嵌套（填充半径=边框半径-线宽，拐角不再透底）；返回改名撤销；日间终端白底黑字（session创建后覆写termux配色表11槽：前景/背景/光标+7个浅底不可读ANSI加深变体，夜间沿用默认，还原wrapper钉死） |
| **5.0.6** | 506 | 快捷管理列表顶缝清零；终端显示区钉死深色底（日间白字可读，仿真器不动）；分隔线按左段填充区分深浅（压绿用深色切断、压空心用浅色）；闪屏row2 padding改margin等高；两工坊Radio圆点显式tint两档可见；反选开关换M3 MaterialSwitch与外面一致 |
| **5.0.5** | 505 | 分隔线结构根治（独立分隔View删掉，线直接画进段右边缘压住填充，永无缝隙）；工坊四行回标准圆角胶囊；网络两行纵padding清零绿顶住横线；夜间模式改名主题配色；Splash/洞洞板/预览遮罩跟随夜间（点阵绿保留）；对话框标题消息中央着色（排行/编辑/LAN标题日间可见）；快捷管理底栏改分段胶囊；系统信息与域名端口按钮改主题绿圆角边框 |
| **5.0.4** | 504 | 根因修复夜间模式不生效：setNightMode 用 apply()（异步落盘）后紧跟杀进程重启，落盘根本没执行，新进程读旧值；改 commit() 同步落盘（同 setLangExplicit），重启即生效 |
| **5.0.3** | 503 | 根因修复4项：①夜间模式不生效——主题加载处用未调 load() 的 SettingsManager 新实例读 nightMode 恒为 true，6 处改直读落盘值；②输入框/按钮边框重叠+填充错位——胶囊行拿顶部 padding 当间距，边框圈住含 padding 的盒子致上沿浮空重叠，4 行改 padding 清零 + topMargin 8dp；③工坊底栏四行显式全宽+对话框 row2 加 8dp 间距；④分隔线不可见——1dp outline 在深底对比度不足，改走 onSurface 40% 透明（两档自适应）；状态栏字号 15sp 改 13sp |
| **5.0.2** | 502 | 重大修复8合1：①根治开关消失——MaterialSwitch 在 AppCompat/MaterialComponents 主题下 materialSwitchStyle 落空致 thumb/track 全 null（0尺寸隐形），新增 Theme.TermLou.Switch（M3深色）给4处开关包主题；②分段条填充/边框错位——边框改 foreground 浮层，填充全幅顶满像素对齐；③工坊二级页底栏改文件/网络式无边框全宽分段条；④终端两键行/网络两行之间加横向分隔线；⑤段间分隔线核验；⑥所有分段选中段填充统一品牌绿 cPrimary；⑦命令输入框与按钮行间距 8px 改 8dp；⑧设置页新增"界面设置"大标题（字号/语言/夜间模式三个小标题，语言上移），完整浅色主题（Scheme.light 映射+全站硬编码白字走主题色，终端模拟器与wheel特效保持深色，切换重启生效） |
| **5.0.1** | 501 | 崩溃修复：MaterialSwitch 在 AppCompat/MaterialComponents 主题下无 materialSwitchStyle，SwitchCompat 的 showText 代码默认 true + textOn/textOff 全 null，onMeasure 里 StaticLayout(null) 必崩（网络→设置滑动、网络选应用两处），4 处开关显式 showText=false 根治；前三 tab 底部按键去圆角去边框改全宽分段条（终端 8 键、文件导入、网络两行，选中填充+分隔线保留） |
| **5.0.0** | 500 | 外观对齐 Material Design（纯样式）：成对按钮改 M3 分段按钮（圆角描边容器+段间分隔线，主段填充副段透明），终端十二键、文件页两键、LAN 启动/认证、网络两行、设置保存/重置与上游代理同格化；磁贴开关/保留后台/语言与 App 选择对话框换 M3 MaterialSwitch（品牌绿保留）；Tab 指示器改 3dp 主题色下划亮线；输入框改 8dp 圆角 1dp 描边空心字段；状态条改 surfaceContainer+onSurface 常规字体；按钮去掉 3D 唇边改圆角胶囊+涟漪，主题色不变 |
| **4.9.8** | 491 | 根因修复磁贴双跑：每次点击生成唯一意图ID（pending文件名+EXTRA透传），消费端按ID精确去重，同一次点击只执行一次，不丢任何意图 |
| **4.9.6** | 489 | 彻底去掉白帧：辉光扩散与松手补齐上限均为0.92亮白blend，删除整白绘制分支与onEnd，全链路无纯白帧；单层wheel上半区空行底色改霜罩合成色，与非聚焦区同色接平 |
| **4.9.5** | 488 | 快捷栏入口空条：预排版与监听器统一公式（去+8dp）+纠正后显式requestLayout；长按收尾改线性补齐到0.92取消整白帧；BAND空白区（白框以外）长按进设置；遮罩改冷灰霜色+淡噪点颗粒，中心不动 |
| **4.9.4** | 487 | BAND 切换改共享 ValueAnimator：上下层 wheel 由同一位置曲线逐帧驱动、严格同趟推入；长按进设置的最后辉光补齐 120ms→300ms Decelerate，收尾不突兀；单层 wheel 上层空白区直触本容器路径补竖滑判定，上下滑动可正常切换；detached 预量快捷行高度回填面板尺寸，消除开屏再 measure 的固定卡顿 |
| **4.9.3** | 486 | 崩溃修复：`TerminalSession` 内部用无参 `Handler()` 构造绑定创建线程的 Looper，只能在主线程创建——4.9.2 误将其搬到 IO 线程导致 `Can't create handler ... Looper.prepare()` 每次启动必崩，现移回主线程构造；保留 4.9.2 的拨轮交互时序与启动流畅度修复 |
| **4.9.2** | 485 | 拨轮交互时序与启动流畅度修复：长按灰色 1s 才从按住点起亮（灰区蓄力不碰卡片），辉光 1s 扩散罩满 BAND 期间手指仍可按着可随时滑动取消，松手才补齐辉光进设置；进设置/滑出/切走均复位辉光叠加层，返回后 BAND 不再整条残留白亮；BAND 推送切换期间上层 wheel 随带同帧推入（禁用独立入场/退场动画），上下层不再先后出现；`TerminalSession` 构建移出主线程，启动页聚合完成后的纯色点阵停止逐帧阴影模糊绘制（`setShadowLayer` 仅聚合动画期间按需启用），消除启动固定卡顿 |
| **4.9.1** | 484 | 拨轮交互修复与平滑过渡：长按灰色进设置改挂 `RecyclerView.OnItemTouchListener`（可命中非居中的命令卡片），轻点灰色收回复有效；恢复中央辉光框外暗膜与 BAND 固定双层高度+不透明底色，单层 wheel 不再塌成单行；快捷栏↔拨轮切换改为沿滑动方向的推送过渡（新内容从对侧推入、旧内容同向滑出），快速来回划无缝重入 |
| **4.9.0** | 483 | 终端底部交互改为「固定双层 BAND」：快捷栏（A）与拨轮（B）共用同一固定高度区域（=两层按键高，终端永不渲染其中），区域内**任意方向竖向滑动**（上/下均可、可一路滑出区域）在 A/B 间循环切换，滑完不再停留中间态）；拨轮一层高与快捷栏单键等高对齐。拨轮非聚焦卡片/空白槽仍为「轻点收回、长按 2s」：长按改为从按住点发亮、白色辉光波纹扩散至整条双层 BAND 后进入拨轮设置（原卡片变色脉动反馈移除）。删除 4.8.2 的弹簧替换开合（`androidx.dynamicanimation`），wheel 开合回到带内显隐+淡入淡出，移除 `SwipeableContainer` 横划检测/点击透传通道 |
| **4.8.1** | 481 | 拨轮推荐升级为多阶后缀预测（Multi-Order Markov）：从最近 24 条命令序列中提取 3 阶/2 阶/1 阶 suffix，优先使用最高阶且样本≥3 的条件概率，不足时自动降阶；`MIN_TRANSITIONS=3` 防止噪声干扰，Laplace 平滑 + 置信度衰减保留不变 |
| **4.8.0** | 480 | 系统可靠性修复轮（第三方审查 25 项中已确认属实的全部修订）：SOCKS5 转发 20 秒空闲断连改为仅握手期超时+转发期不设超时，并在停止时关闭在途 socket（防扫描/句柄泄漏）；UDP 中继键改为「源↔目标」，同客户端访问多目标不再串流；过载不再内联执行任务，一律丢弃并关闭连接；VPN 启动/停止改为状态机（启动中收到停止也会回收刚创建的 TUN/tun2socks），避免双重起停与 teardown 竞态；LAN 服务器头部读取加 15 秒绝对截止；WebSocket 会话改独立线程并支持真实分片、强制客户端掩码、帧 1MB/消息 4MB 上限；上传改为落盘再流式解析，内存占用从约 3× 降到 1×；根文件系统安装改为事务化（staging 解压+补丁→原子切换，失败回滚），根内绝对符号链接改写为相对链接、缺失的硬链接源直接报错、PAX 增加 size/mtime/mode 覆盖；各会话临时目录隔离（主终端/磁贴/LAN 各自独立并在结束时清理）；浮窗关闭时把等待中的请求写回 dismiss 结果；磁贴命令改唯一文件队列，连续点击不互相覆盖；grep 跳过二进制文件、导入文件增加 64MB 上限；过期的会话临时目录自动清理 |
| **4.7.1** | 471 | 抓包体验打磨：每条连接新增流量行为标签（`FlowClassifier` 纯元数据精确判定，标签按流量大小、方向与时长推断，每条连接恒有一个标签，非内容解析）——DNS 解析（端口 53/5353，独占）/ 挂起心跳（长连接极低流量）/ 大流量媒体（下行≥上行且体积≥256KB）/ 上行传输（上行≥64KB 且上行≥下行×4）/ 其他（兜底）；连接列表改为活跃连接（进行中/UDP）置顶、关闭后自动下沉；同时勾选多 App 抓包时顶部闪现提示无法按 App 区分流量；抓包页底部操作栏收敛为 2×2：开始/停止抓包、选择应用、清空记录、流量排行 |
| **4.7.0** | 470 | 网络抓包深度分析：TCP/QUIC SNI 提取（`SniParser` 分片重组 + QUIC Initial CRYPTO 帧）、明文端口极简 HTTP（首包首行 `method path → status`）、DNS 事件记录；网络页新增实时看板（复用系统信息饼图：↑↓实时速率/会话总量/活跃/屏蔽计数 + Top3 流量饼图图例，展示区:连接区 1:1，搜索即时过滤，滚动区更新看板同帧刷新）；新建连接详情显示 SNI/HTTP/时长；新增流量排行（域名/端口）+ DNS 历史弹窗；只吃明文：无 TLS 中间人。单文件导出仍为 `vpn-flows.json`（新增可选 `sni`/`http`/`durMs` 字段），清空按钮彻底清内存+落盘 `[]` |
| **4.6.0** | 460 | 移除意图工坊（Integrator 相关清理），版本号提升 |
| **4.5.3** | 453 | LAN 网页终端修复"选中即提示已复制但实际未复制"：`index.html` 新增 `term.onSelectionChange` 真实复制（secure context 用 `navigator.clipboard.writeText`，明文 http 回退隐藏 textarea + `execCommand('copy')`），复制成功后在右上角显示 `已复制` 提示（Web 多语言 `web_copy_ok`）。仅浏览器侧复制，不同步手机剪贴板 |
| **4.5.2** | 452 | 修复拨号框整体泛灰：框外 85% 暗膜保持 `#D91E1E1E`，挖洞从 EVEN_ODD 路径改为系统级裁剪 `clipOutRoundRect`（洞矩形+圆角与白框完全一致），保证框内绝对正常不染色、圆角处暗色与边框零缝隙 |
| **4.5.1** | 451 | 修 4.5.0 四角空隙：框外暗膜由"左右两块直角矩形"改为高度带矩形 + 与白色描边同矩形同圆角的 `EVEN_ODD` 镂空路径，灰与边框外缘完全吻合，框内亮区由圆角限定、无残留亮三角 |
| **4.5.0** | 450 | 拨号框框外显著变暗（~85% 黑 `#D91E1E1E`），暗膜仅限框的高度带内、跟随 frameH 平滑伸缩（短一行/长两行）；框视图改全宽居中，中央框区不透膜保持原样，辉光圈叠加在上；仍为纯视觉单组件，无触摸不挡操作 |
| **4.4.9** | 449 | 拨号框收敛为细白微辉光（2dp 线宽 + 1dp 模糊）；显隐与 wheel 完全同步：`WheelController` 新增 `onWheelPhase` 钩子，框以与 wheelPanel 完全一致的参数（220ms/180ms、同插值器、translationY+alpha）做镜像进出动画，关闭不再滞后；closing 期间高度动画让位 |
| **4.4.8** | 448 | 拨号框外观改白色张扬辉光：单层全白 `BlurMaskFilter` 泛光（线宽/模糊各 12dp）内外一致、带范围扩散，软件层渲染；视图四周预留 24dp 辉光空间防裁切，几何/动画/层级观察/无触摸全不变 |
| **4.4.7** | 447 | 拨轮去掉居中高亮（双轮统一暗底白字、保留按距离缩放），改为独立纯视觉固定拨号框组件（`WheelLevelFrame`）：奶油描边圆角框、仅观察轮盘层级自动高度切换（一层短/二层长、180ms 平滑伸缩）、无触摸行为不挡操作；按压缩放脉冲保留 |
| **4.4.6** | 446 | 呼出推荐升级为四因素评分：当前TUI频次＋上文后继（上一次执行命令在当前TUI下的马尔可夫后继，Laplace平滑＋置信系数）＋全局累计（冷状态兜底，随状态频次轻阻尼防双倍计喜）＋最近使用（半衰期7天）；会话级 prevKey 内存锚点，跨层级命令 id 平铺评分，无新增持久化 |
| **4.4.5** | 445 | 页面切换动画由 3D 翻转改为纯平移滑动（与顶部标签条一致；去除 DIM 淡化、cameraDistance/rotationY，改 translationX 并行跟随滑动） |
| **4.4.4** | 444 | 修复 4.4.3 回归：拨轮呼出/组展开即时高亮（`applyGlow` 首评分门槛由 `isSelected` 改为按已应用文本色判定，避免与 `WheelAdapter` 默认选中态撞车导致居中卡片不高亮） |
| **4.4.3** | 443 | 拨轮性能与逻辑打磨：滚动中卡片缩放改直设（去掉逐 tick 重启 120ms 动画的滞后感）、双轮光效合并为共享实现、上层组轮盘按中心槽位变化节流且仅静止时回正推荐位；TuiStateDetector 单遍读 `/proc`（每次候选 4 次文件读→1 次，消除父进程过滤的重复读） |
| **4.4.2** | 442 | 存储弹窗改双列系统信息（标题/入口改名系统信息；左列发行版/版本/代号/架构，读 rootfs `etc/os-release`，饼图缩至 140dp；中英字串同步） |
| **4.4.1** | 441 | 语言切换改整进程重启（分身副用户下热重建会被系统二次重建盖回旧语言；冷启动读盘是唯一无竞态路径；`setLangExplicit` 改 `commit()` 保落盘；灰字注明切换将重启） |
| **4.4.0** | 440 | MainActivity 解耦（2682→~480 行）：拆出 AppScope + Status/Terminal/Workspace/Network/Lan/Settings/Overlay 共 8 控制器；startShell 去重收归 TerminalManager；按🇨🇳/🇺🇸开关刷新服务与磁贴 |
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
