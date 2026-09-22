# TermLou

> 一个为**不写代码的人**打造的 Android 终端 —— 把 Debian GNU/Linux、文件管理、笔记待办、网络抓包和脚本浮窗，全部塞进一个"点一下就能用"的 App。

![Version](https://img.shields.io/badge/version-5.0.57-blue)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen)
![minSdk](https://img.shields.io/badge/minSdk-26-orange)
![Language](https://img.shields.io/badge/Kotlin-2.0.21-purple)
![License](https://img.shields.io/badge/license-GPL--3.0-red)

**English** · [English README](README.en.md)

---

## 出发点 Design Philosophy

终端很强，但它的门槛是"背命令、记语法、认路径"。TermLou 的全部设计都指向一件事：**把命令输入变成点按操作，把 Shell 能力变成人人可用**。

- **命令拨轮**：滚一滚、点一下，最可能用到的命令自动滚到中央，不需要知道命令怎么写。
- **命令组**：把一组常用命令压成一张卡片，用"任务"代替"记忆命令层级"。
- **脚本浮窗**：Shell 脚本一句 `termlou-ui` 就能弹出原生 Android 对话框，脚本从此有了界面。
- **零 XML 界面**：全部 UI 由 Kotlin 程序化构建，五 Tab 滑动切换，风格统一、随主题切换。

在这个内核之外，TermLou 还长出了完整的**笔记与待办体系**（可独立于 Linux 使用）和**网络抓包分析**（按 App 抓包、DNS 映射、域名/IP 阻断），并支持中英双语与浅色/深色主题。

---

## 核心特点 Highlights

| | |
|---|---|
| 🎡 **双层命令拨轮** | 无限循环滚动，卡片即按钮，点击即执行；命令组滚到正中自动展开第二层乳黄拨轮，无需理解命令层级 |
| 🐧 **真 Debian 环境** | 内嵌 Debian GNU/Linux 13 (trixie) rootfs + PRoot root 模拟，无需 root、无需刷机 |
| 📁 **文件管理器 = Linux 文件系统窗口** | 手机侧目录与 Linux 的 `/workspace` 同一实体，导入导出即时互通 |
| 📝 **笔记与待办** | 主界面笔记 Tab 与独立笔记页双入口；列表/编辑、标签胶囊、待办看板、退出自动保存；纯文件存储，不依赖 Linux |
| 📌 **笔记磁贴** | 通知栏一键直达独立笔记页，绕开主界面、不唤醒 Linux 会话，用完即走、后台无残留 |
| 💬 **脚本驱动浮窗** | Shell 一句 `termlou-ui --title "确认" --button "OK"` 即弹出原生浮窗，支持输入/单选/多选/开关/ANSI 彩色输出 |
| 🌐 **VPN 抓包与过滤** | 内置 `VpnService` + SOCKS5 代理 + DNS 拦截 + 域名/IP 阻断 + 实时流量日志，按 App 抓包 |
| 🎨 **启动像素工坊** | 手绘 96×80 点阵 + 照片转像素（Sobel/Otsu/高斯/形态学）+ 粒子飞入动画，自定义启动画面 |
| 🧱 **零 XML 布局** | 全部 UI 由 Kotlin 程序化构建，五 Tab 架构 |
| ⚙️ **原生 PTY 组件** | 自写 JNI `libtermux.so`（`termux_pty.cpp`），作为 Termux `terminal-view` 渲染器的 PTY 后端 |
| 🛡️ **安全加固** | 路径逃逸防护、输出流 128KB 截断、命令超时强杀、资源上限 |
| 🌍 **双语 + 双主题** | 中英双语一键切换，浅色/深色主题，重开即生效 |

---

## 技术栈 Tech Stack

| 层 | 技术 |
|----|------|
| 语言 | Kotlin 2.0.21（模块 `app` + `workspace`）+ C++17（JNI / NDK） |
| 构建 | Gradle 8.4 / Android Gradle Plugin 8.3.2 / CMake 3.22.1 / NDK 25–26 |
| Android | compileSdk 34 · targetSdk 34 · minSdk 26 · arm64-v8a |
| 终端 | `com.github.termux.termux-app:terminal-view:v0.118.3`（上游渲染器 + 自写 PTY） |
| UI | 程序化视图（零 XML）；`androidx.core-ktx` / `appcompat` / `material` / `recyclerview` / `lifecycle-runtime` |
| 文件 | `androidx.documentfile`（SAF 导入）、`commons-compress`（tar 解包）、`xz`（tar.xz 流式解压） |
| 数据 | `SharedPreferences` + 自写 JSON（命令库 v2、笔记索引） |
| 静态分析 | detekt 1.23.1 |
| 测试 | JUnit 4 · MockK · kotlinx-coroutines-test · Espresso（235 个单元测试） |
| CI | GitHub Actions（lint + test + assembleDebug） |

---

## 架构 Architecture

### 模块结构 Module Layout

```text
TermLou
├── app/        (com.workspace.proot)  产品层 —— 面向用户的 App
│   ├── MainActivity          单 Activity 五 Tab 壳，实现 TerminalSessionClient / TerminalViewClient
│   ├── AppScope + *Controller 状态与业务控制器（Terminal/Notes/Workspace/Network/Lan/Settings/Status）
│   ├── UiBuilder             程序化 UI 工厂（状态栏 / 五 Tab / 拨轮 / 设置 / 文件列表）
│   ├── TerminalManager       proot 会话装配、.bashrc 幂等注入、Ctrl 模式、runInProot
│   ├── TermlouDirs           .termlou IPC 目录定义（filesDir/.termlou → /termlou）
│   ├── DistroVersion         从 rootfs /etc/os-release 解析发行版 codename（trixie）
│   ├── FileListManager       /workspace 文件浏览、导入导出、删除
│   ├── NotesStore            笔记正文 .txt + 隐藏 JSON 索引（标签/待办/时间戳）
│   ├── NotesController       主界面笔记 Tab（列表/编辑，与独立页解耦）
│   ├── NotesStandaloneActivity  独立笔记页（磁贴直起，不依赖 Linux）
│   ├── NotesTodoActivity / NotesTagsActivity  待办看板 / 标签筛选子页
│   ├── ShortcutManager       快捷键行 + 命令拨轮装配 + 命令执行（转义解析）
│   ├── WheelController/Adapter  无限循环拨轮（Int.MAX_VALUE 取模 + 空槽位）+ TUI 感知推荐
│   ├── CommandRecommender    多信号加权推荐（频次 + 序列 + 近因）
│   ├── ScriptDialogSpec/Renderer/Overlay  脚本浮窗协议 + 渲染 + 覆盖层
│   ├── DialogMakerActivity   弹窗工坊（图形化设计 termlou-ui 对话框）
│   ├── OverlayBridge / ClipboardBridge  文件 IPC 桥（req/res + FileObserver + 轮询兜底）
│   ├── SplashView/Letters/Tokens/Maker  启动画面渲染 + 像素工坊（手绘 + CV 管线）
│   ├── NetVpnService / MiniSocks5Server / DnsParser / DnsMap / BlockRules  VPN 抓包栈
│   ├── CommandTileService / LauncherTileService / NotesTileService  快捷设置磁贴
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
  └─ SettingsManager.load()（字号 / 命令 / 磁贴命令 / 保活 / 命令库 JSON / 主题 / 语言）
  ├─ rootfs 不存在 → 显示 Install RootFS 界面
  │      └─ RootfsExtractor 解包 assets/rootfs.tar.gz → filesDir/workspace/linux
  └─ rootfs 就绪 → SplashView 粒子启动动画
        └─ startShell()（IO 协程）
             ├─ 迁移旧目录 / 清理缓存 / 迁移旧 .termlou
             ├─ .bashrc 幂等注入（只替换受管段，保留用户改动）
             ├─ 安装 termlou-ui / termlou-clipboard 到 /usr/local/bin
             ├─ 同步系统 DNS → RootfsPatcher
             └─ 创建 TerminalSession(proot) → attachSession(TerminalView)
```

### PRoot 启动与终端连接 How the terminal connects

`TerminalSession` 以 `libproot_exec.so` 为可执行程序，参数包含 `-r <rootfs>`、`-w /workspace` 及一系列 bind mount（`workspace→/workspace`、`tmp→/tmp`、`.termlou→/termlou`、`/dev /proc /sys /etc/hosts`）。会话交由**自写**的 `libtermux.so`（`termux_pty.cpp`）处理：`posix_openpt` 建 PTY → `fork()` → 子进程 `setsid`/`TIOCSCTTY`/`dup2` 后 `execve` proot。该 JNI 的包名与函数签名完全兼容 Termux 上游，因此可直接复用 `terminal-view` 的渲染器。apt 源 codename 从 rootfs 的 `/etc/os-release` 动态解析（`DistroVersion.kt`），自动匹配 trixie 等。

### 文件管理器 ↔ Linux 文件系统

文件管理器**不经过 proot**，直接浏览 `filesDir/workspace` —— 这正是 proot bind mount 到 `/workspace` 的同一目录。所以手机侧导入/导出/删除，Linux 侧即时可见；rootfs 本体（`linux/`）与 `tmp/` 在列表中隐藏。也正因如此，浏览/读写普通文件并不需要启动 Linux 会话。

### 笔记与 Linux 解耦 Notes are Linux-independent

笔记正文保存在 `filesDir/workspace/Notes/*.txt`，索引（标签、待办、时间戳）保存在同目录下的隐藏 JSON。读写全部是主机侧普通文件 IO（落盘用 tmp + 改名原子写），**与 proot / 终端会话零耦合**。因此：主界面笔记 Tab、独立笔记页、笔记磁贴三条入口共用同一份数据，但只有主界面那一条会随 App 启动 Linux。

### 脚本浮窗 IPC

`.termlou` 目录（`filesDir/.termlou` → `/termlou`）作为 Android 与 proot 间的原子 JSON 交换区：`termlou-ui` 脚本写入 `req/<id>.json` → `OverlayBridge` 的 `FileObserver` 触发 → 渲染 `TYPE_APPLICATION_OVERLAY` 浮窗 → 结果写回 `res/<id>.json` → 脚本读取并打印到 stdout。剪贴板同理（`termlou-clipboard`）。

### 安全设计 Security Design

- **路径逃逸防护**：`WorkspaceFileSystem.resolvePath` 规范化路径后做 canonical-path 包含校验；tar 解包拒绝 `..` 段与逃逸路径
- **输出截断**：stdout/stderr 各限 128KB，防止 OOM 与上下文爆炸
- **超时强杀**：命令超时 `destroyForcibly()`，协程取消时 join 收集线程防泄漏
- **注入安全**：proot 内命令以位置参数传递（`cd -- "$1" && eval "$2"`），杜绝 shell 引号/转义问题

---

## 功能详解 Features

### 1. 终端 Terminal

- **快捷键行**：`/` `Tab` `Esc` `Ctrl` 常驻 + 方向键（单按移动光标，Ctrl+方向跳单词）
- **Ctrl 模式**：按 Ctrl 进入模式（按钮变红），再按一键即发送 Ctrl+该键
- **初始命令**：设置中配置启动时自动执行的命令；从磁贴启动时改为执行磁贴命令
- **转义字符**：命令支持 `\n` `\r` `\t` `\e` `\cX`（Ctrl+X）
- **OpenCode AI**：一键安装 CLI，终端输入 `opencode` 即用 AI 助手
- **Tab 介绍页**：点顶部 Tab 图标打开本页介绍（实心页，不切换 Tab，返回键关闭）；终端介绍页顶部是系统信息（发行版 + 存储饼图，保留进入动画）

### 2. 命令拨轮与命令组 Command Wheel & Groups

- **双层拨轮**：下层 = 命令卡片 + 组卡片（📁）；组卡片滚到正对 → 自动展开上层乳黄拨轮
- **点击即执行**：无限循环滚动，中央卡片自动高亮，点击执行
- **TUI 感知推荐**：`TuiStateDetector` 扫 `/proc` 识别前台程序，`CommandRecommender` 按频次/序列/近因把最可能需要的命令滚到中央
- **组内体验**：成员执行后保持展开，组离开高亮才收起；组对正时点组无操作
- **命令库管理**：设置页管理命令与组；拖拽排序、拖拽合并成组、加入/解散组、组内成员排序/编辑/删除
- **组不变式**：组必须 ≥2 成员 —— 删除/移出到剩 1 个时自动还原成命令

### 3. 文件管理器 File Manager

- `/workspace` 完整导航：进入/返回上级、文件夹标记、大小显示、渐入动画
- **导入**：底部按钮导入文件（任意格式）/ 递归导入文件夹
- **导出/分享**：文件直接分享；文件夹自动打包 ZIP 后分享
- **删除**：文件直接删；非空文件夹弹窗确认
- **MIME 识别**：txt/json/xml/html/jpg/png/gif/pdf/zip/md/csv/py/sh 等自动识别

### 4. 笔记与待办 Notes & Todos

- **双入口**：主界面笔记 Tab（可左右滑）、磁贴直起的独立笔记页（无返回顶栏、禁左右滑）
- **列表/编辑**：列表显示标题、更新时间、标签胶囊；编辑页正文、标签区、待办区、[＋待办][＋标签]
- **标签**：按内容哈希着色（同标签天然同色），标签页可搜索并筛选
- **待办看板**：左"已完成"｜灰线｜右"未完成"两列独立滚动、冻结标题与计数；整行点按切换，切走飞出、落位飞入并高亮
- **退出自动保存**：正文在退列表/切笔记/切 Tab/后台/跳子页/重命名前比对落盘，避免逐字写放大
- **不依赖 Linux**：纯文件存储，磁贴入口全程不唤醒 proot / 终端会话

### 5. 脚本浮窗 Script Dialog (termlou-ui)

- Shell 一行命令弹出原生 Android 浮窗：`termlou-ui --title "确认" --message "继续？" --button "确定=ok=primary" --button "取消=cancel"`
- 控件：`--input` 输入框、`--select` 单选、`--check` 多选、`--toggle` 开关、`--output FILE` 带 ANSI 彩色的文本输出
- 主题 `dark/light/glass`、强调色、圆角、位置、动画、超时自动关闭
- **弹窗工坊**（`DialogMakerActivity`）：图形化设计对话框，实时预览，一键导出 `termlou-ui` 命令
- **剪贴板桥**：`termlou-clipboard "文本"` 或 `echo "文本" | termlou-clipboard` 直写系统剪贴板

### 6. 网络抓包与过滤 Network

- **按 App 抓包**：`VpnService` + `addAllowedApplication` 精选要抓的 App
- **DNS**：按系统 DNS 下发给 VPN（取不到才回退公共 DNS）；解析查询/响应建立域名↔IP 映射
- **SOCKS5 代理**：内置 `MiniSocks5Server`（TCP CONNECT + UDP ASSOCIATE），可选上游代理
- **阻断规则**：按 IP / 域名后缀阻断，实时生效
- **流量日志**：实时列表（域名/IP、协议、端口、上下行字节、状态），支持按 IP/域名阻断

### 7. 启动工坊 Splash Workshop

- **手绘点阵**：96×80 孔板，手指绘制像素
- **照片转像素**：灰度 → Sobel 边缘检测 → 非极大值抑制 → 双阈值 → 形态学闭运算 → 阈值量化，支持多风格与反色，捏合缩放取景
- **粒子动画**：亮点从随机边缘飞入，品牌绿→青渐变

### 8. 快速设置磁贴 Quick Settings Tiles

- **命令磁贴**：通知栏一键执行预设命令（冷启动容错、原子 pending 文件、按 ID 去重）
- **启动器磁贴**：一键打开常用 App（单收藏直启，多收藏弹出抽屉）
- **笔记磁贴**：一键直达独立笔记页；独立任务、`excludeFromRecents`，退出即回桌面、后台无残留

### 9. 设置 Settings

| 设置项 | 说明 |
|--------|------|
| 字号 | 五档（极小→极大），终端与文件列表即时同步 |
| 语言 | 中/英切换（重开生效） |
| 主题配色 | 浅色/深色（重开生效） |
| 启动命令 | 启动时自动执行的 Shell 命令 |
| 磁贴命令 | Quick Settings Tile 点击执行的命令 |
| 快捷启动 | 选择常用 App，一键启动（磁贴抽屉） |
| 弹窗工坊 / 启动工坊 | 图形化设计脚本浮窗 / 自定义启动画面 |
| 网络上游代理 | SOCKS5 上游（留空走内置直连 + 抓包/阻断） |
| 后台持久化 | 前台通知服务保活（低优先级、无声无震动） |
| 系统信息 | 已移至终端介绍页（点终端 Tab 图标查看） |

---

## 使用方法 Usage

### 快速开始 Quick Start

1. 安装 APK，首次打开点击 **Install RootFS** 部署 Debian 根文件系统（约 5 分钟，内嵌 rootfs 本地解包）
2. 部署完成自动进入终端（root 权限，工作目录 `/workspace`）
3. 需要后台常驻时在设置开启"后台持久化"
4. 想直接用笔记？把"笔记"磁贴加到快捷面板，点它即可，无需终端

### 手势总览 Gestures

| 操作 | 手势 |
|------|------|
| Tab 切换 | 终端 ↔ 笔记 ↔ 文件 ↔ 网络 ↔ 设置 左右滑动 |
| Tab 介绍页 | 点击顶部 Tab 图标（返回键关闭，不切换 Tab） |
| 打开命令拨轮 | 快捷栏**上下滑动**切换 |
| 打开命令库管理 | 拨轮灰色区域**长按** |
| 退出笔记编辑 | 编辑态按返回键退回列表 |

### 命令拨轮操作 Command Wheel

1. 终端页对快捷栏上下滑动打开拨轮
2. 水平滚动浏览卡片，中央卡片自动高亮
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
| 加入已有组 | 拖拽到组卡片 |
| 解散组 | 命令库中组卡片左滑 → 弹窗确认 |
| 组内管理 | 点击组卡片进入组页：成员排序 / 编辑 / 删除 / 移出 |

---

## 版本历史 Changelog

完整版本历史见 [CHANGELOG.md](CHANGELOG.md)。近期版本：

| 版本 | versionCode | 内容 |
|------|------------|------|
| **5.0.57** | 557 | 笔记txt头部嵌入元数据（标签/待办/时间戳，删库按头部全量恢复；坏头当正文）；连接自愈（外部删库/删目录后重建不崩）；去迁移兜底 |
| **5.0.56** | 556 | 笔记底层换SQLite＋FTS5（bundled驱动，trigram中英可搜；.txt原位保留，旧index.json事务导入后改名.bak；3字以下退化子串扫描） |
| **5.0.55** | 555 | VPN抓包DNS改系统DNS；新增独立笔记页＋笔记磁贴（不唤醒Linux、用完即走） |
| **5.0.54** | 554 | 笔记正文改退出自动保存，去掉逐字全量落盘 |
| **5.0.53** | 553 | 笔记相关搜索框/输入框改单行定高；看板空列提示统一"暂无" |

---

## 构建 Build

前置：JDK 17、Android SDK + NDK（`local.properties` 配置 `sdk.dir`）。

```powershell
# Windows PowerShell
$env:JAVA_HOME = "<path-to-jdk-17>"
.\gradlew.bat :app:assembleRelease --console=plain   # Release APK
.\gradlew.bat :app:testDebugUnitTest :workspace:testDebugUnitTest  # 单元测试
.\gradlew.bat :app:detekt   # 静态分析
```

产物：`app/build/outputs/apk/release/app-release.apk`（Release 需 `keystore.properties`，见 `keystore.properties.example`）

签名：`keystore.properties` 读取签名配置（仓库外，勿提交）。release 构建启用 R8 压缩与 ProGuard。

CI：`.github/workflows/build.yml` 在 push 到 `main` 时执行 `lintDebug` → `test` → `assembleDebug`，产物上传为 artifact。

---

## 常见问题 FAQ

**RootFS 安装失败？**
检查存储空间；安装为本地解包内嵌 rootfs（约 5 分钟）。失败可重启 App 重试。

**终端卡顿或无法输入？**
点击终端区域唤起软键盘；仍不行则重启 App。

**Quick Settings Tile 点击无反应？**
确认对应磁贴已添加到快捷面板；命令磁贴需先在设置里保存命令。

**笔记磁贴进去后，后台还能看到吗？**
看不到。独立笔记页使用独立任务并排除在最近任务之外，退出即回桌面、不驻留后台，也不会启动 Linux。

**改笔记时没存就退出会丢吗？**
正常不会。正文在退回列表、切换笔记、切 Tab、切后台、锁屏、跳转待办/标签、重命名前都会自动落盘；只有"改完还没做任何离开动作就崩溃/被强杀"这一种极端情况会丢最近输入。

**后台持久化耗电吗？**
仅维持一个低优先级前台通知，对电量影响极小（`KeepAliveWakeLock` 90s 超时自释放）。

**如何备份数据？**
用文件管理器「导出文件夹」把整个 `/workspace/` 打包为 ZIP 分享到云盘/电脑。

---

## 许可证 License

本项目基于 [GPL-3.0](LICENSE) 开源。

- 终端渲染基于 [termux-app terminal-view](https://github.com/termux/termux-app)（GPL-3.0）—— 本项目复用其渲染器并自写 PTY 后端（`termux_pty.cpp`），因此整体分发遵循 GPL-3.0。
- 内嵌 Debian rootfs 来自 Debian 官方（见 `app/src/main/assets/rootfs.tar.gz`，`usr/lib/os-release` 为 `Debian GNU/Linux 13 (trixie)`）。
- 静态 `curl`（`assets/curl_aarch64`）与 CA 证书（`cacert.pem`）随 rootfs 注入，仅用于首次启动时的依赖自愈。

---

**Made by Lou with ♥** — 让终端不再吓人。
