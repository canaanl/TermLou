# TermLou

> An Android terminal built for **people who don't code** — Debian GNU/Linux, file management, notes & todos, network inspection and script-driven floating UI, all inside one tap-and-go App.

![Version](https://img.shields.io/badge/version-5.0.57-blue)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen)
![minSdk](https://img.shields.io/badge/minSdk-26-orange)
![Language](https://img.shields.io/badge/Kotlin-2.0.21-purple)
![License](https://img.shields.io/badge/license-GPL--3.0-red)

**English** · [中文 README](README.md)

---

## Design Philosophy

Terminals are powerful, but the barrier is memorizing commands, syntax and paths. Everything in TermLou points at one goal: **turn command input into tapping, and turn shell power into something anyone can use**.

- **Command Wheel** — scroll and tap; the most likely command rolls to the center, no need to know how to type it.
- **Command Groups** — compress a set of common commands into one card; "tasks" replace "remembering command hierarchy".
- **Script Dialog** — a shell script can pop a native Android dialog with a single `termlou-ui` call, giving scripts a UI.
- **Zero-XML UI** — the whole interface is built programmatically in Kotlin, five tabs with swipe switching, consistent style and theming.

Beyond that core, TermLou also grew a complete **notes & todo system** (usable independently of Linux) and **network capture/analysis** (per-App capture, DNS mapping, domain/IP blocking), plus bilingual UI and light/dark themes.

---

## Highlights

| | |
|---|---|
| 🎡 **Two-level Command Wheel** | Infinite looping scroll, cards are buttons, tap to run; a group card scrolled to center expands a second amber wheel — no need to understand command hierarchy |
| 🐧 **Real Debian** | Bundled Debian GNU/Linux 13 (trixie) rootfs + PRoot root emulation; no root, no flashing |
| 📁 **File Manager = Linux FS window** | On-device folders and Linux `/workspace` are the same entity; import/export is instantly visible to both sides |
| 📝 **Notes & Todos** | Notes tab and standalone notes page; list/editor, tag capsules, todo kanban, save-on-exit; plain-file storage, Linux-independent |
| 📌 **Notes Tile** | One tap from Quick Settings to the standalone notes page; bypasses the main UI, does not boot Linux, leaves nothing in the background |
| 💬 **Script-driven Dialogs** | One line of shell (`termlou-ui --title "Confirm" --button "OK"`) pops a native overlay: input/single-choice/multi-choice/toggle/ANSI-colored output |
| 🌐 **VPN Capture & Filtering** | Built-in `VpnService` + SOCKS5 proxy + DNS interception + domain/IP blocking + live flow log, per-App capture |
| 🎨 **Splash Pixel Workshop** | Hand-drawn 96×80 dot matrix + photo-to-pixels (Sobel/Otsu/Gaussian/morphology) + particle fly-in animation |
| 🧱 **Zero-XML Layout** | Entire UI constructed programmatically in Kotlin, five-tab architecture |
| ⚙️ **Native PTY** | Custom JNI `libtermux.so` (`termux_pty.cpp`) as the PTY backend for the Termux `terminal-view` renderer |
| 🛡️ **Hardening** | Path-escape guards, 128KB output truncation, command timeout kill, resource caps |
| 🌍 **Bilingual + Dual Theme** | Chinese/English switch, light/dark theme, applied on restart |

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Language | Kotlin 2.0.21 (modules `app` + `workspace`) + C++17 (JNI / NDK) |
| Build | Gradle 8.4 / Android Gradle Plugin 8.3.2 / CMake 3.22.1 / NDK 25–26 |
| Android | compileSdk 34 · targetSdk 34 · minSdk 26 · arm64-v8a |
| Terminal | `com.github.termux.termux-app:terminal-view:v0.118.3` (upstream renderer + custom PTY) |
| UI | Programmatic views (zero XML); `androidx.core-ktx` / `appcompat` / `material` / `recyclerview` / `lifecycle-runtime` |
| Files | `androidx.documentfile` (SAF import), `commons-compress` (tar unpacking), `xz` (streaming tar.xz) |
| Data | `SharedPreferences` + hand-written JSON (command library v2, notes index) |
| Static analysis | detekt 1.23.1 |
| Testing | JUnit 4 · MockK · kotlinx-coroutines-test · Espresso (235 unit tests) |
| CI | GitHub Actions (lint + test + assembleDebug) |

---

## Architecture

### Module Layout

```text
TermLou
├── app/        (com.workspace.proot)  Product layer — the user-facing App
│   ├── MainActivity          Single-Activity five-tab shell, implements TerminalSessionClient / TerminalViewClient
│   ├── AppScope + *Controller  State & feature controllers (Terminal/Notes/Workspace/Network/Lan/Settings/Status)
│   ├── UiBuilder             Programmatic UI factory (status bar / five tabs / wheel / settings / file list)
│   ├── TerminalManager       proot session assembly, idempotent .bashrc injection, Ctrl mode, runInProot
│   ├── TermlouDirs           .termlou IPC dir definition (filesDir/.termlou → /termlou)
│   ├── DistroVersion         Parses distro codename (trixie) from rootfs /etc/os-release
│   ├── FileListManager       /workspace browsing, import/export, delete
│   ├── NotesStore            Note bodies as .txt + hidden JSON index (tags/todos/timestamps)
│   ├── NotesController       Main-UI notes tab (list/editor, decoupled from standalone page)
│   ├── NotesStandaloneActivity  Standalone notes page (opened by the tile, Linux-independent)
│   ├── NotesTodoActivity / NotesTagsActivity  Todo kanban / tag filter sub-page
│   ├── ShortcutManager       Shortcut row + wheel assembly + command execution (escape parsing)
│   ├── WheelController/Adapter  Infinite looping wheel (Int.MAX_VALUE modulo + empty slots) + TUI-aware suggestion
│   ├── CommandRecommender    Multi-signal ranking (frequency + sequence + recency)
│   ├── ScriptDialogSpec/Renderer/Overlay  Script dialog protocol + rendering + overlay
│   ├── DialogMakerActivity   Dialog workshop (design termlou-ui dialogs graphically)
│   ├── OverlayBridge / ClipboardBridge  File IPC bridge (req/res + FileObserver + polling fallback)
│   ├── SplashView/Letters/Tokens/Maker  Splash rendering + pixel workshop (drawing + CV pipeline)
│   ├── NetVpnService / MiniSocks5Server / DnsParser / DnsMap / BlockRules  VPN capture stack
│   ├── CommandTileService / LauncherTileService / NotesTileService  Quick Settings tiles
│   ├── TermlouCommandRunner  Headless tile-command execution service
│   └── TermKeepAliveService / KeepAliveWakeLock  Foreground keep-alive
│
└── workspace/  (me.rerere.workspace)  Reusable library layer — "Android-hosted Linux" toolkit
    ├── WorkspaceManager / WorkspaceFileSystem   Safe file abstraction (strict root confinement)
    ├── ProotShellRunner / WorkspaceShellRunner  Pluggable command runners (Host / PRoot)
    ├── RootfsInstaller                           Download + hand-written streaming tar unpacker
    ├── RootfsPatcher                             Idempotent rootfs patching (DNS / hosts / group / perms)
    └── src/main/cpp/termux_pty.cpp               libtermux.so — native PTY JNI
```

### Boot Flow

```text
MainActivity.onCreate
  └─ UiBuilder builds the whole UI (zero XML)
  └─ SettingsManager.load() (font size / commands / tile command / keep-alive / library JSON / theme / language)
  ├─ rootfs missing → show Install RootFS screen
  │      └─ RootfsExtractor unpacks assets/rootfs.tar.gz → filesDir/workspace/linux
  └─ rootfs ready → SplashView particle animation
        └─ startShell() (IO coroutine)
             ├─ migrate old dirs / clean caches / migrate old .termlou
             ├─ idempotent .bashrc injection (managed section only, keeps user edits)
             ├─ install termlou-ui / termlou-clipboard into /usr/local/bin
             ├─ sync system DNS → RootfsPatcher
             └─ create TerminalSession(proot) → attachSession(TerminalView)
```

### How the terminal connects

`TerminalSession` uses `libproot_exec.so` as the executable, with `-r <rootfs>`, `-w /workspace` and bind mounts (`workspace→/workspace`, `tmp→/tmp`, `.termlou→/termlou`, `/dev /proc /sys /etc/hosts`). The session is handled by our **own** `libtermux.so` (`termux_pty.cpp`): `posix_openpt` creates the PTY → `fork()` → child does `setsid`/`TIOCSCTTY`/`dup2` then `execve`s proot. Its package name and function signatures are fully compatible with upstream Termux, so the `terminal-view` renderer can be reused directly. The apt codename is parsed dynamically from rootfs `/etc/os-release` (`DistroVersion.kt`).

### File Manager ↔ Linux filesystem

The file manager goes **around proot**, browsing `filesDir/workspace` directly — the very directory bind-mounted to `/workspace`. So import/export/delete on the device side is instantly visible in Linux; the rootfs (`linux/`) and `tmp/` are hidden from the list. For the same reason, browsing and reading/writing ordinary files does not require a running Linux session.

### Notes are Linux-independent

Note bodies live in `filesDir/workspace/Notes/*.txt`, with the index (tags, todos, timestamps) in a hidden JSON in the same directory. All I/O is plain host-side file I/O (atomic tmp+rename writes), **completely decoupled from proot / the terminal session**. The main-UI notes tab, the standalone notes page and the notes tile share the same data, but only the main UI path brings up Linux.

### Script Dialog IPC

The `.termlou` directory (`filesDir/.termlou` → `/termlou`) is the atomic JSON exchange area between Android and proot: the `termlou-ui` script writes `req/<id>.json` → `OverlayBridge`'s `FileObserver` fires → a `TYPE_APPLICATION_OVERLAY` dialog is rendered → the result is written back to `res/<id>.json` → the script reads it and prints to stdout. The clipboard works the same way (`termlou-clipboard`).

### Security Design

- **Path-escape guards**: `WorkspaceFileSystem.resolvePath` normalizes and then canonical-path-contains-checks; tar unpacking rejects `..` segments and escaping paths
- **Output truncation**: stdout/stderr capped at 128KB to prevent OOM
- **Timeout kill**: commands are `destroyForcibly()`-ed on timeout; collector threads joined on cancellation
- **Injection safety**: in-proot commands are passed as positional args (`cd -- "$1" && eval "$2"`), eliminating shell quoting/escaping issues

---

## Features

### 1. Terminal

- **Shortcut row**: `/` `Tab` `Esc` `Ctrl` plus arrow keys (single tap moves cursor, Ctrl+arrow jumps words)
- **Ctrl mode**: press Ctrl to arm (button turns red), then any key sends Ctrl+that key
- **Initial command**: run automatically at startup; when launched from the tile, the tile command runs instead
- **Escape sequences**: commands support `\n` `\r` `\t` `\e` `\cX` (Ctrl+X)
- **OpenCode AI**: one-tap CLI install; type `opencode` in the terminal for an AI assistant

### 2. Command Wheel & Groups

- **Two-level wheel**: lower level = command cards + group cards (📁); a group card centered auto-expands the amber members wheel
- **Tap to run**: infinite looping scroll, the centered card is highlighted, tap to execute
- **TUI-aware suggestion**: `TuiStateDetector` scans `/proc` for the foreground program; `CommandRecommender` ranks by frequency/sequence/recency
- **In-group UX**: stays expanded after a member runs; collapses when the group leaves the highlight zone
- **Library management**: manage commands and groups in Settings; drag to sort, drag onto another to merge, join/dissolve groups, sort/edit/delete members
- **Group invariant**: a group must have ≥2 members — dropping to 1 auto-restores it as a plain command

### 3. File Manager

- Full `/workspace` navigation: enter/up, folder markers, size display, fade-in
- **Import**: files (any format) or recursive folders
- **Export/share**: files shared directly; folders zipped then shared
- **Delete**: files deleted directly; non-empty folders ask for confirmation
- **MIME detection**: txt/json/xml/html/jpg/png/gif/pdf/zip/md/csv/py/sh, etc.

### 4. Notes & Todos

- **Two entries**: main-UI notes tab (swipeable) and a tile-launched standalone page (no back header, swipe disabled)
- **List/editor**: list shows title, update time and tag capsules; editor has body, tag area, todo area, [＋Todo][＋Tag]
- **Tags**: colored by content hash (same tag always same color); the tags page supports search and filtering
- **Todo kanban**: "Done" left | divider | "Open" right, each column scrolls independently with frozen headers and counts; tap a row to toggle — it flies out and the destination row flies in and flashes
- **Save on exit**: the body is compared and written before leaving the list, switching notes, switching tabs, backgrounding, jumping to sub-pages, renaming — avoiding per-keystroke write amplification
- **Linux-independent**: plain-file storage; the tile path never boots proot / the terminal session

### 5. Script Dialog (termlou-ui)

- One shell line pops a native Android dialog
- Controls: `--input`, `--select`, `--check`, `--toggle`, `--output FILE` (ANSI colors)
- Theme `dark/light/glass`, accent color, corner radius, position, animation, auto-timeout
- **Dialog workshop** (`DialogMakerActivity`): design dialogs graphically, live preview, export the `termlou-ui` command
- **Clipboard bridge**: `termlou-clipboard "text"` or `echo "text" | termlou-clipboard`

### 6. Network Capture & Filtering

- **Per-App capture**: `VpnService` + `addAllowedApplication`
- **DNS**: system DNS pushed to the VPN (falls back to public DNS only if unavailable); queries/responses parsed into a domain↔IP map
- **SOCKS5 proxy**: built-in `MiniSocks5Server` (TCP CONNECT + UDP ASSOCIATE), optional upstream proxy
- **Blocking rules**: by IP / domain suffix, effective immediately
- **Flow log**: live list (domain/IP, protocol, port, up/down bytes, state) with per-entry blocking

### 7. Splash Workshop

- **Hand-drawn matrix**: 96×80 grid, draw pixels with your finger
- **Photo to pixels**: grayscale → Sobel edge detection → non-max suppression → dual threshold → morphological closing → quantization, multiple styles and inversion, pinch to zoom/crop
- **Particle animation**: dots fly in from random edges, brand-green→cyan gradient

### 8. Quick Settings Tiles

- **Command tile**: run a preset command from the notification shade (cold-start tolerant, atomic pending files, dedup by ID)
- **Launcher tile**: open favorite Apps (direct when one, drawer when many)
- **Notes tile**: jump straight to the standalone notes page; separate task, `excludeFromRecents`, leaves nothing behind

### 9. Settings

| Setting | Description |
|---------|-------------|
| Font size | Five levels; terminal and file list stay in sync |
| Language | Chinese/English (applied on restart) |
| Theme | Light/dark (applied on restart) |
| Startup command | Shell command run automatically at launch |
| Tile command | Command run when the Quick Settings tile is tapped |
| Quick launch | Pick favorite Apps for one-tap launch (tile drawer) |
| Dialog/Splash workshop | Graphical script-dialog design / custom splash |
| Network upstream proxy | SOCKS5 upstream (empty = built-in direct + capture/blocking) |
| Background keep-alive | Foreground notification service (low priority, silent) |
| System info / Storage | Distro info and `/workspace/` pie chart |

---

## Usage

### Quick Start

1. Install the APK and tap **Install RootFS** on first launch (about 5 minutes, unpacked locally from the bundled rootfs)
2. When done it drops into the terminal (root, workdir `/workspace`)
3. Enable "Background keep-alive" in Settings if you need it to stay resident
4. Want notes directly? Add the **Notes** tile to Quick Settings and tap it — no terminal needed

### Gestures

| Action | Gesture |
|--------|---------|
| Tab switching | Terminal ↔ Notes ↔ Files ↔ Network ↔ Settings, swipe left/right |
| Open the command wheel | Short left swipe on the shortcut row (>50dp) |
| Open library management | Long left swipe on the shortcut row (>100dp) |
| Leave the notes editor | Back key returns to the list |

### Command Wheel

1. Short left swipe on the shortcut row to open the wheel
2. Scroll horizontally; the centered card is highlighted
3. Tap the centered card to run; a centered group card expands its member wheel

### Script Dialog Examples

```bash
# Confirmation dialog
termlou-ui --title "Confirm" --message "Continue?" --button "OK=ok=primary" --button "Cancel=cancel"

# With input and choice
termlou-ui --title "Deploy" --input "branch=branch" --select "env=env" --option "prod" --option "staging" --button "Submit=submit=primary"

# Show command output as colored text
ls -l > /tmp/out.txt && termlou-ui --title "Listing" --output /tmp/out.txt --button "Close"

# Write to clipboard
echo "https://example.com" | termlou-clipboard
```

The Dialog workshop (Settings → Dialog workshop) lets you design these graphically and export the command.

### Command Library / Groups

| Action | How |
|--------|-----|
| New command | "＋ New shortcut command" at the bottom of the library |
| Edit command | Tap a list item |
| Sort | Long-press and drag |
| Merge into a group | Drag a command card onto another, hold 1s → confirm on release |
| Join an existing group | Drag onto the group card |
| Dissolve a group | Left swipe the group card → confirm |
| In-group management | Tap the group card: sort / edit / delete / remove members |

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full history. Recent versions:

| Version | versionCode | Summary |
|---------|-------------|---------|
| **5.0.57** | 557 | Note .txt files now embed metadata headers (tags/todos/timestamps, full restore after db loss; broken headers read as body); connection self-heal (no crash after external db/dir deletion); migration fallback removed |
| **5.0.56** | 556 | Notes storage moved to SQLite + FTS5 (bundled driver, trigram search; .txt files kept, legacy index.json imported then renamed to .bak; sub-3-char queries fall back to substring scan) |
| **5.0.55** | 555 | VPN capture now uses system DNS; standalone notes page + notes tile (no Linux boot, leaves nothing behind) |
| **5.0.54** | 554 | Notes save on exit instead of full rewrite per keystroke |
| **5.0.53** | 553 | Notes search/input fields made single-line; empty-column hint unified to "Empty" |

---

## Build

Prerequisites: JDK 17, Android SDK + NDK (`sdk.dir` in `local.properties`).

```powershell
# Windows PowerShell
$env:JAVA_HOME = "<path-to-jdk-17>"
.\gradlew.bat :app:assembleRelease --console=plain   # Release APK
.\gradlew.bat :app:testDebugUnitTest :workspace:testDebugUnitTest  # Unit tests
.\gradlew.bat :app:detekt   # Static analysis
```

Output: `app/build/outputs/apk/release/app-release.apk` (Release needs `keystore.properties`, see `keystore.properties.example`)

Signing: configuration is read from `keystore.properties` (outside the repo, never commit it). Release builds enable R8 shrinking and ProGuard.

CI: `.github/workflows/build.yml` runs `lintDebug` → `test` → `assembleDebug` on pushes to `main` and uploads the artifact.

---

## FAQ

**RootFS install failed?**
Check free storage; the install unpacks the bundled rootfs locally (about 5 minutes). Restart the App to retry.

**Terminal laggy or can't type?**
Tap the terminal area to raise the keyboard; if it persists, restart the App.

**Quick Settings tile does nothing?**
Make sure the tile is added to the Quick Settings panel; the command tile also needs a command saved in Settings first.

**Does the notes tile stay in the background?**
No. The standalone notes page runs in its own task excluded from Recents; exiting returns to the launcher and leaves nothing behind — and it never boots Linux.

**Will I lose note edits if I exit without saving?**
Normally no. The body is auto-saved before returning to the list, switching notes, switching tabs, backgrounding, locking the screen, jumping to todos/tags, or renaming. Only an abrupt crash right after typing — before any way of leaving — can lose the most recent input.

**Does background keep-alive drain the battery?**
It only maintains a low-priority foreground notification; impact is minimal (`KeepAliveWakeLock` auto-releases after 90s).

**How do I back up my data?**
Use the file manager's "Export folder" to zip the whole `/workspace/` and share it to a cloud drive or PC.

---

## License

This project is open source under [GPL-3.0](LICENSE).

- Terminal rendering is based on [termux-app terminal-view](https://github.com/termux/termux-app) (GPL-3.0) — this project reuses its renderer and writes its own PTY backend (`termux_pty.cpp`), so distribution follows GPL-3.0.
- The bundled Debian rootfs comes from Debian official (see `app/src/main/assets/rootfs.tar.gz`, `usr/lib/os-release` is `Debian GNU/Linux 13 (trixie)`).
- A static `curl` (`assets/curl_aarch64`) and CA cert (`cacert.pem`) are injected with the rootfs, used only for first-boot dependency self-healing.

---

**Made by Lou with ♥** — making the terminal less scary.
