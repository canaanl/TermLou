#!/bin/bash
# termlou-clipboard - 开放写入 Android 系统剪贴板
#
# 用法:
#   termlou-clipboard [文本]              # 参数直写（可含空格，需引号）
#   echo "文本" | termlou-clipboard        # 管道（自由拼接变量/命令结果）
#   cat file.txt | termlou-clipboard       # 文件内容
#   termlou-clipboard --clear              # 清空剪贴板
#   termlou-clipboard --help | -h          # 帮助
#
# 说明:
#   - 文本来源优先级：命令行参数 > stdin 管道；无参数且无管道时显示帮助
#   - 支持任意文本（中文/emoji/多行），单次 ≤400KB，超限截断
#   - 写入在任意场景都允许（前台/后台/FGS），无需焦点
#
# 示例:
#   termlou-clipboard "hello world"
#   IP=$(hostname -I | awk '{print $1}'); echo "http://$IP:8000" | termlou-clipboard
#   MSG="build $(date) $(git rev-parse --short HEAD 2>/dev/null)"; echo "$MSG" | termlou-clipboard
#   cat README.md | termlou-clipboard
#   termlou-clipboard --help
#   termlou-clipboard --clear
set -u

TL_LANG="$(cat /termlou/lang 2>/dev/null || echo zh)"
_t() {
  if [ "$TL_LANG" = "en" ]; then printf '%s' "$2"; else printf '%s' "$1"; fi
}

DIR=/termlou/clipboard
REQ_DIR=$DIR/req
RES_DIR=$DIR/res
mkdir -p "$REQ_DIR" "$RES_DIR" 2>/dev/null

print_help() {
  cat <<'EOF'
Usage: termlou-clipboard [OPTIONS] [TEXT]
       echo TEXT | termlou-clipboard [OPTIONS]

Description:
  $(_t "将文本写入 Android 系统剪贴板（开放写入接口，sh 自由拼内容）" "Write text to the Android clipboard (open write API for shell scripts)")

Options:
  -h, --help     $(_t "显示此帮助" "Show this help")
  --clear        $(_t "清空剪贴板" "Clear the clipboard")

Examples:
  termlou-clipboard "hello world"
  echo "http://192.168.1.5:8000" | termlou-clipboard
  IP=$(hostname -I | awk '{print $1}'); echo "http://$IP:8000" | termlou-clipboard
  cat out.txt | termlou-clipboard
  termlou-clipboard --help
  termlou-clipboard --clear

Notes:
  - $(_t "文本来源：参数 > stdin；无参数且无管道时显示帮助" "Text source: args > stdin; show help when neither given")
  - $(_t "任意文本均可（中文/emoji/多行），单次建议 ≤400KB" "Any text (CJK/emoji/multiline), ≤400KB per call recommended")
  - $(_t "写入在前台/后台均可成功（Android 剪贴板写不受焦点限制）" "Writes succeed in foreground/background (no focus needed)")
EOF
}

OP="set"
SHOW_HELP=0

# ---------- 参数解析 ----------
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help)
      SHOW_HELP=1
      shift
      ;;
    --clear)
      OP="clear"
      shift
      ;;
    --)
      shift
      break
      ;;
    --*)
      echo "termlou-clipboard: $(_t "未知选项 $1" "unknown option $1")" >&2
      echo "Try 'termlou-clipboard --help' for more information." >&2
      exit 2
      ;;
    *)
      break
      ;;
  esac
done

if [ "$SHOW_HELP" -eq 1 ]; then
  print_help
  exit 0
fi

if [ "$OP" = "clear" ]; then
  TEXT=""
else
  # 文本来源：剩余参数 > stdin
  if [ $# -gt 0 ]; then
    # 将所有剩余参数用空格拼接，保留原始空格（需调用方加引号）
    TEXT="$*"
  else
    # 无参数：尝试读 stdin；若是 tty（无管道）则显示帮助
    if [ -t 0 ]; then
      print_help
      exit 0
    fi
    TEXT=$(cat)
  fi
fi

# 空文本（非 clear）视为误用，提示帮助
if [ "$OP" = "set" ] && [ -z "$TEXT" ]; then
  echo "termlou-clipboard: $(_t "无内容可写入" "nothing to write")" >&2
  print_help >&2
  exit 2
fi

# 400KB 截断（按字节）
TEXT_BYTES=$(printf '%s' "$TEXT" | wc -c)
if [ "$TEXT_BYTES" -gt 409600 ]; then
  echo "termlou-clipboard: $(_t "文本超限 ${TEXT_BYTES}B > 409600B，已截断" "text over limit ${TEXT_BYTES}B > 409600B, truncated")" >&2
  # 按字节截断，保留前 409600 字节
  TEXT=$(printf '%s' "$TEXT" | head -c 409600)
fi

# ---------- JSON 转义 ----------
json_str() {
  local LC_ALL=C s="$1" i ch code hex out=""
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\t'/\\t}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\r'/\\r}"
  if [[ "$s" == *[$'\001'-$'\037']* ]]; then
    for (( i=0; i<${#s}; i++ )); do
      ch="${s:$i:1}"
      case "$ch" in
        "'") out="${out}'" ;;
        *)
          code=$(printf '%d' "'$ch")
          if [ "$code" -lt 32 ]; then
            printf -v hex '\\u%04x' "$code"
            out="${out}$hex"
          else
            out="${out}$ch"
          fi
          ;;
      esac
    done
    printf '%s' "$out"
  else
    printf '%s' "$s"
  fi
}

id="$$_$(date +%s%N)"
reqfile="$REQ_DIR/$id.json"
resfile="$RES_DIR/$id.json"

if [ "$OP" = "clear" ]; then
  json="{\"id\":\"$id\",\"op\":\"clear\"}"
else
  json="{\"id\":\"$id\",\"op\":\"set\",\"text\":\"$(json_str "$TEXT")\"}"
fi

# 原子写 req
printf '%s\n' "$json" > "$reqfile.tmp" && mv "$reqfile.tmp" "$reqfile"

# ---------- 等待结果 ----------
TIMEOUT=3
itimeout=$TIMEOUT
if command -v inotifywait >/dev/null 2>&1; then
  inotifywait -q -e create -e moved_to --timeout "$itimeout" "$RES_DIR" >/dev/null 2>&1
  [ -f "$resfile" ] || sleep 0.3
else
  attempts=$(( TIMEOUT * 50 + 1 ))
  i=0
  while [ "$i" -lt "$attempts" ]; do
    [ -f "$resfile" ] && break
    sleep 0.02
    i=$(( i + 1 ))
  done
fi

if [ -f "$resfile" ]; then
  # 校验响应
  if grep -q '"ok"[[:space:]]*:[[:space:]]*true' "$resfile" 2>/dev/null; then
    rm -f "$resfile" "$reqfile"
    exit 0
  else
    # 打印错误原因
    cat "$resfile" >&2
    rm -f "$resfile" "$reqfile"
    exit 1
  fi
else
  echo "termlou-clipboard: $(_t "等待响应超时（${TIMEOUT}s），请确保 TermLou 在运行" "response timeout (${TIMEOUT}s), make sure TermLou is running")" >&2
  rm -f "$reqfile" "$resfile"
  exit 1
fi
