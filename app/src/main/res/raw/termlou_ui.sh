#!/bin/bash
# termlou-ui v4 - 单例进程式浮窗（有则改内容，无则新建；显式 --close 关闭）
#
# 用法:
#   termlou-ui [@模板名] [选项...]
#   termlou-ui --close                    # 显式关闭当前浮窗
#
# 选项:
#   --title T              标题
#   --message M            正文
#   --button 文本=id[=类型][=关闭]  按钮（可多个；类型: normal|primary|danger；关闭 1=点击后关窗(默认)，0=保持窗口等待下一弹窗）
#   --input 标签=key        文本输入行
#   --default 值           输入行/开关默认值（仅存在一种控件时可用）
#   --default key=值       按 key 精确指定输入行默认值或开关状态（1/on/true/yes 为开）
#   --select 标签=key       单选列表（后跟若干 --option）
#   --check 标签=key        多选列表（后跟若干 --option）
#   --option 值            列表选项（弹窗内有过滤开关时可用 "值#标签" 携带过滤标签）
#   --toggle 标签=key      开关行（默认关；结果值 "1"/"0"）
#   --toggle 标签=key=标签  过滤开关：开启时只显示带对应 #标签 的 select 选项，关闭显示全部
#   --output FILE          把文件内容作为文本输出行（支持 ANSI 色）
#   --timeout 秒           超时（默认 60）
#   --close                显式关闭当前浮窗（单例）
#   --chain CHAIN_ID       已废弃：单例自动原位更新，无需分组（保留兼容）
#   --theme dark|light|glass
#   --accent #RRGGBB       主色
#   --radius N             圆角 dp
#   --position center|bottom
#   --anim fade|scale|slide-up  # 仅首建生效，原位更新为 Diff + 高度平滑
#
# 示例:
#   termlou-ui --title "确认" --message "继续?" --button "继续=ok=primary" --button "退出=cancel"
#   ls -l > out.txt && termlou-ui --title "列表" --output out.txt --button 关闭
#   termlou-ui --title "服务" --check "主机=hosts" --option "a#up" --option "b#down" --toggle "只看运行中=filter=up"
#   termlou-ui --title "步骤1" --message "第一页" --button "下一步=next=primary"  # 有窗则原位更新
#   termlou-ui --title "菜单" --button "返回=back=normal=0" --button "退出=quit"  # 返回不关窗，接着发上层菜单即回退
#   termlou-ui --close  # 显式关闭
#   开关开启时列表只显示带 #up 标签的选项（#标签 不出现在显示与结果里）
set -u

DIR=/termlou
REQ_DIR=$DIR/req
RES_DIR=$DIR/res
mkdir -p "$REQ_DIR" "$RES_DIR" 2>/dev/null

id="$$_$(date +%s%N 2>/dev/null || date +%s)_$RANDOM"
reqfile="$REQ_DIR/$id.json"
resfile="$RES_DIR/$id.json"

TITLE=""
MESSAGE=""
OUTPUT_FILE=""
TIMEOUT=60
THEME="dark"
ACCENT=""
RADIUS=""
POSITION="center"
ANIM="scale"
CHAIN=""
CLOSE=0

BTN_SPEC=()
IN_LABEL=()
IN_KEY=()
IN_DEFAULT=()
SEL_LABEL=()
SEL_KEY=()
SEL_MULTI=()
SEL_OPT_START=()
SEL_OPT_END=()
OPT_POOL=()
TOG_LABEL=()
TOG_KEY=()
TOG_DEFAULT=()
TOG_FILTER=()
DEFAULTS=()
sel_index=-1

die() {
  echo "termlou-ui: $1" >&2
  exit 2
}

TL_LANG="$(cat /termlou/lang 2>/dev/null || echo zh)"
_t() {
  if [ "$TL_LANG" = "en" ]; then printf '%s' "$2"; else printf '%s' "$1"; fi
}

# ---------- 参数解析 ----------
while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)
      if [ "$TL_LANG" = "en" ]; then
        cat <<'EOF'
Usage: termlou-ui [@template] [options...]

Pop up an interactive overlay above any app; result prints to stdout as JSON.

Options:
  --title T              Title
  --message M            Body
  --button text=id[=kind][=close]  Buttons (repeatable; kind: normal|primary|danger; close 1=close after tap (default), 0=keep window for next dialog)
  --input label=key      Text input row
  --default V            Default for input/toggle (only when a single control exists)
  --default key=V        Default per key, or toggle state (1/on/true/yes = on)
  --select label=key     Single-choice list (followed by --option items)
  --check label=key      Multi-choice list (followed by --option items)
  --option V             List option (with filter toggle use "value#tag" to carry a filter tag)
  --toggle label=key     Toggle row (default off; result "1"/"0")
  --toggle label=key=tag Filter toggle: when on, only select options tagged #tag show
  --output FILE          Render file content as text output (ANSI colors supported)
  --timeout SEC          Timeout (default 60)
  --close                Close the current overlay explicitly
  --chain CHAIN_ID       Deprecated (singleton updates in place, kept for compat)
  --theme dark|light|glass
  --accent #RRGGBB       Accent color
  --radius N             Corner radius in dp
  --position center|bottom
  --anim fade|scale|slide-up
  -h, --help             Show this help

Examples:
  termlou-ui --title "Confirm" --message "Continue?" --button "Go=ok=primary" --button "Quit=cancel"
  ls -l > out.txt && termlou-ui --title "List" --output out.txt --button Close
  termlou-ui --close
EOF
      else
        cat <<'EOF'
用法: termlou-ui [@模板名] [选项...]

在任意 App 之上弹出可交互浮窗，结果以 JSON 打印到 stdout。

选项:
  --title T              标题
  --message M            正文
  --button 文本=id[=类型][=关闭]  按钮（可多个；类型: normal|primary|danger；关闭 1=点击后关窗(默认)，0=保持窗口等待下一弹窗）
  --input 标签=key        文本输入行
  --default 值           输入行/开关默认值（仅存在一种控件时可用）
  --default key=值       按 key 精确指定输入行默认值或开关状态（1/on/true/yes 为开）
  --select 标签=key       单选列表（后跟若干 --option）
  --check 标签=key        多选列表（后跟若干 --option）
  --option 值            列表选项（弹窗内有过滤开关时可用 "值#标签" 携带过滤标签）
  --toggle 标签=key      开关行（默认关；结果值 "1"/"0"）
  --toggle 标签=key=标签  过滤开关：开启时只显示带对应 #标签 的 select 选项，关闭显示全部
  --output FILE          把文件内容作为文本输出行（支持 ANSI 色）
  --timeout 秒           超时（默认 60）
  --close                显式关闭当前浮窗
  --chain CHAIN_ID       已废弃（单例自动原位更新，保留兼容）
  --theme dark|light|glass
  --accent #RRGGBB       主色
  --radius N             圆角 dp
  --position center|bottom
  --anim fade|scale|slide-up
  -h, --help             显示本帮助

示例:
  termlou-ui --title "确认" --message "继续?" --button "继续=ok=primary" --button "退出=cancel"
  ls -l > out.txt && termlou-ui --title "列表" --output out.txt --button 关闭
  termlou-ui --title "服务" --check "主机=hosts" --option "a#up" --option "b#down" --toggle "只看运行中=filter=up"
  termlou-ui --title "步骤1" --message "第一页" --button "下一步=next=primary"  # 有窗则原位更新
  termlou-ui --title "菜单" --button "返回=back=normal=0" --button "退出=quit"  # 返回不关窗，接着发上层菜单即回退
  termlou-ui --close
EOF
      fi
      exit 0
      ;;
    --title) shift; [ $# -eq 0 ] && die "$(_t "--title 缺少参数值" "--title needs a value")"; TITLE="$1" ;;
    --message) shift; [ $# -eq 0 ] && die "$(_t "--message 缺少参数值" "--message needs a value")"; MESSAGE="$1" ;;
    --button) shift; [ $# -eq 0 ] && die "$(_t "--button 缺少参数值" "--button needs a value")"; BTN_SPEC+=("$1") ;;
    --input) shift
      [ $# -eq 0 ] && die "$(_t "--input 缺少参数值" "--input needs a value")"
      label=${1%%=*}; key=${1#*=}
      [ "$key" = "$1" ] && key="$label"
      IN_LABEL+=("$label"); IN_KEY+=("$key"); IN_DEFAULT+=("")
      ;;
    --default) shift
      [ $# -eq 0 ] && die "$(_t "--default 缺少参数值" "--default needs a value")"
      DEFAULTS+=("$1")
      ;;
    --toggle) shift
      [ $# -eq 0 ] && die "$(_t "--toggle 缺少参数值" "--toggle needs a value")"
      label=${1%%=*}
      rest=${1#*=}
      key=${rest%%=*}
      filter=${rest#*=}
      [ "$key" = "$1" ] && key="$label"
      [ "$filter" = "$rest" ] && filter=""
      TOG_LABEL+=("$label"); TOG_KEY+=("$key"); TOG_FILTER+=("$filter"); TOG_DEFAULT+=("0")
      ;;
    --select)
      shift
      [ $# -eq 0 ] && die "$(_t "--select 缺少参数值" "--select needs a value")"
      sel_index=$(( ${#SEL_LABEL[@]} ))
      label=${1%%=*}; key=${1#*=}
      [ "$key" = "$1" ] && key="$label"
      SEL_LABEL[$sel_index]="$label"; SEL_KEY[$sel_index]="$key"
      SEL_MULTI[$sel_index]=""
      SEL_OPT_START[$sel_index]=${#OPT_POOL[@]}; SEL_OPT_END[$sel_index]=${#OPT_POOL[@]}
      ;;
    --check)
      shift
      [ $# -eq 0 ] && die "$(_t "--check 缺少参数值" "--check needs a value")"
      sel_index=$(( ${#SEL_LABEL[@]} ))
      label=${1%%=*}; key=${1#*=}
      [ "$key" = "$1" ] && key="$label"
      SEL_LABEL[$sel_index]="$label"; SEL_KEY[$sel_index]="$key"
      SEL_MULTI[$sel_index]="1"
      SEL_OPT_START[$sel_index]=${#OPT_POOL[@]}; SEL_OPT_END[$sel_index]=${#OPT_POOL[@]}
      ;;
    --option) shift
      [ $# -eq 0 ] && die "$(_t "--option 缺少参数值" "--option needs a value")"
      if [ "$sel_index" -ge 0 ]; then
        OPT_POOL+=("$1")
        SEL_OPT_END[$sel_index]=${#OPT_POOL[@]}
      else
        die "$(_t "--option 必须跟在 --select/--check 之后" "--option must follow --select/--check")"
      fi
      ;;
    --output) shift; [ $# -eq 0 ] && die "$(_t "--output 缺少参数值" "--output needs a value")"; OUTPUT_FILE="$1" ;;
    --timeout) shift; [ $# -eq 0 ] && die "$(_t "--timeout 缺少参数值" "--timeout needs a value")"; TIMEOUT="$1" ;;
    --close) CLOSE=1 ;;
    --chain) shift; [ $# -eq 0 ] && die "$(_t "--chain 缺少参数值" "--chain needs a value")"; CHAIN="$1"; [[ "$CHAIN" =~ ^[A-Za-z0-9._-]+$ ]] || die "--chain $(_t "非法" "invalid"): $CHAIN" ;;
    --theme) shift; [ $# -eq 0 ] && die "$(_t "--theme 缺少参数值" "--theme needs a value")"; THEME="$1" ;;
    --accent) shift; [ $# -eq 0 ] && die "$(_t "--accent 缺少参数值" "--accent needs a value")"; ACCENT="$1" ;;
    --radius) shift; [ $# -eq 0 ] && die "$(_t "--radius 缺少参数值" "--radius needs a value")"; RADIUS="$1" ;;
    --position) shift; [ $# -eq 0 ] && die "$(_t "--position 缺少参数值" "--position needs a value")"; POSITION="$1" ;;
    --anim) shift; [ $# -eq 0 ] && die "$(_t "--anim 缺少参数值" "--anim needs a value")"; ANIM="$1" ;;
    *) die "$(_t "未知参数 $1" "unknown option $1")" ;;
  esac
  shift
done

# ---------- 解析 --default（发射期统一解析，消除"先出现后引用"次序问题） ----------
resolve_default() {
  local d="$1" k v i found=""
  k=${d%%=*}
  v=${d#*=}
  # 新语法：--default key=值（k 精确匹配某个输入/开关的 key）
  if [ "$k" != "$d" ]; then
    for i in "${!IN_KEY[@]}"; do
      if [ "${IN_KEY[$i]}" = "$k" ]; then
        IN_DEFAULT[$i]="$v"; found=1; break
      fi
    done
    if [ -z "$found" ]; then
      for i in "${!TOG_KEY[@]}"; do
        if [ "${TOG_KEY[$i]}" = "$k" ]; then
          case "$v" in
            1|on|true|yes) TOG_DEFAULT[$i]="1" ;;
            *) TOG_DEFAULT[$i]="0" ;;
          esac
          found=1; break
        fi
      done
    fi
    [ -n "$found" ] && return
  fi
  # 旧语法：整串当值（仅存在一种控件时可用）
  if [ ${#IN_KEY[@]} -gt 0 ] && [ ${#TOG_KEY[@]} -gt 0 ]; then
    die "--default $d: $(_t "同时存在输入行与开关，请用 --default key=值 精确指定" "both input and toggle exist, use --default key=value")"
  elif [ ${#IN_KEY[@]} -gt 0 ]; then
    IN_DEFAULT[$(( ${#IN_DEFAULT[@]} - 1 ))]="$d"
  elif [ ${#TOG_KEY[@]} -gt 0 ]; then
    case "$d" in
      1|on|true|yes) TOG_DEFAULT[$(( ${#TOG_DEFAULT[@]} - 1 ))]="1" ;;
      *) TOG_DEFAULT[$(( ${#TOG_DEFAULT[@]} - 1 ))]="0" ;;
    esac
  else
    die "--default $d: $(_t "没有输入行或开关可设置" "no input or toggle to set")"
  fi
}
for d in "${DEFAULTS[@]}"; do
  resolve_default "$d"
done

# ---------- 参数校验 ----------
case "$THEME" in dark|light|glass) ;; *) die "--theme $(_t "无效值" "invalid value"): $THEME" ;; esac
case "$POSITION" in center|bottom) ;; *) die "--position $(_t "无效值" "invalid value"): $POSITION" ;; esac
case "$ANIM" in fade|scale|slide-up) ;; *) die "--anim $(_t "无效值" "invalid value"): $ANIM" ;; esac
[[ "$TIMEOUT" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "--timeout $(_t "必须是数字" "must be a number"): $TIMEOUT"
if [ -n "$RADIUS" ]; then
  [[ "$RADIUS" =~ ^[0-9]+$ ]] || die "--radius $(_t "必须是整数" "must be an integer"): $RADIUS"
fi
for i in "${!IN_LABEL[@]}"; do
  [ -n "${IN_LABEL[$i]}" ] || die "$(_t "--input 标签不能为空" "--input label must not be empty")"
  [ -n "${IN_KEY[$i]}" ] || die "$(_t "--input key 不能为空" "--input key must not be empty")"
done
for i in "${!SEL_LABEL[@]}"; do
  [ -n "${SEL_LABEL[$i]}" ] || die "$(_t "--select/--check 标签不能为空" "--select/--check label must not be empty")"
  [ -n "${SEL_KEY[$i]}" ] || die "$(_t "--select/--check key 不能为空" "--select/--check key must not be empty")"
done
for i in "${!TOG_LABEL[@]}"; do
  [ -n "${TOG_LABEL[$i]}" ] || die "$(_t "--toggle 标签不能为空" "--toggle label must not be empty")"
  [ -n "${TOG_KEY[$i]}" ] || die "$(_t "--toggle key 不能为空" "--toggle key must not be empty")"
done

# ---------- 文本转义 ----------
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
        "'") out="${out}'" ;;  # 单引号会让 printf "'$ch" 变成空串，单独放行
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
file_to_json() {
  awk '{
    gsub(/\\/, "\\\\");
    gsub(/"/, "\\\"");
    gsub(/\t/, "\\t");
    gsub(/\r/, "\\r");
    if (NR > 1) printf "\\n";
    printf "%s", $0
  }' "$1"
}

# ---------- 组装 rows ----------
rows=""
sep=""

# 文本输出行
if [ -n "$OUTPUT_FILE" ]; then
  if [ -f "$OUTPUT_FILE" ]; then
    content=$(file_to_json "$OUTPUT_FILE")
  else
    content="(文件不存在: $(json_str "$OUTPUT_FILE"))"
  fi
  rows="${rows}${sep}{\"kind\":\"text\",\"text\":\"$content\"}"
  sep=","
fi

# 输入行
for i in "${!IN_LABEL[@]}"; do
  rows="${rows}${sep}{\"kind\":\"input\",\"key\":\"$(json_str "${IN_KEY[$i]}")\",\"label\":\"$(json_str "${IN_LABEL[$i]}")\",\"default\":\"$(json_str "${IN_DEFAULT[$i]}")\"}"
  sep=","
done

# 选择/多选（选项从 OPT_POOL 按 [start,end) 区间取，免去逗号拆分之虞）
for i in "${!SEL_LABEL[@]}"; do
  multi="${SEL_MULTI[$i]:-}"
  start="${SEL_OPT_START[$i]:-0}"
  end="${SEL_OPT_END[$i]:-0}"
  inner=""
  for (( oi=start; oi<end; oi++ )); do
    o="${OPT_POOL[$oi]:-}"
    [ -z "$o" ] && continue
    inner="${inner}${inner:+,}\"$(json_str "$o")\""
  done
  rows="${rows}${sep}{\"kind\":\"select\",\"key\":\"$(json_str "${SEL_KEY[$i]}")\",\"label\":\"$(json_str "${SEL_LABEL[$i]}")\",\"multi\":${multi:-0},\"options\":[$inner]}"
  sep=","
done

# 开关行（--toggle 标签=key[=过滤标签]：过滤标签非空时，开启后只显示带该 #标签 的 select 选项）
for i in "${!TOG_LABEL[@]}"; do
  f="${TOG_FILTER[$i]:-}"
  if [ -n "$f" ]; then
    rows="${rows}${sep}{\"kind\":\"toggle\",\"key\":\"$(json_str "${TOG_KEY[$i]}")\",\"label\":\"$(json_str "${TOG_LABEL[$i]}")\",\"default\":${TOG_DEFAULT[$i]:-0},\"filter\":\"$(json_str "$f")\"}"
  else
    rows="${rows}${sep}{\"kind\":\"toggle\",\"key\":\"$(json_str "${TOG_KEY[$i]}")\",\"label\":\"$(json_str "${TOG_LABEL[$i]}")\",\"default\":${TOG_DEFAULT[$i]:-0}}"
  fi
  sep=","
done

# 按钮（默认关闭）
if [ ${#BTN_SPEC[@]} -eq 0 ]; then
  BTN_SPEC=("关闭:close:normal")
fi
bjson=""
bsep=""
for b in "${BTN_SPEC[@]}"; do
  # 文本=id[=类型][=关闭]：关闭 1=点击后关窗（默认），0=保持窗口等待下一弹窗
  t="${b%%=*}"
  rest="${b#*=}"
  if [ "$rest" = "$b" ]; then
    bid="$t"; kind="normal"; close="1"
  else
    bid="${rest%%=*}"
    rest2="${rest#*=}"
    if [ "$rest2" = "$rest" ]; then
      kind="normal"; close="1"
    else
      kind="${rest2%%=*}"
      rest3="${rest2#*=}"
      if [ "$rest3" = "$rest2" ]; then
        close="1"
      else
        close="${rest3%%=*}"
      fi
    fi
  fi
  [ -z "$bid" ] && bid="$t"
  [ "$kind" = "$bid" ] && kind="normal"
  [ "$close" != "0" ] && close="1"
  bjson="${bjson}${bsep}{\"text\":\"$(json_str "$t")\",\"id\":\"$(json_str "$bid")\",\"kind\":\"$(json_str "$kind")\""
  if [ "$close" = "0" ]; then bjson="${bjson},\"close\":false"; fi
  bjson="${bjson}}"
  bsep=","
done
rows="${rows}${sep}{\"kind\":\"buttons\",\"buttons\":[$bjson]}"

# ---------- 组装完整 JSON ----------
style="{\"theme\":\"$(json_str "$THEME")\",\"position\":\"$(json_str "$POSITION")\",\"anim\":\"$(json_str "$ANIM")\""
if [ -n "$ACCENT" ]; then style="$style,\"accent\":\"$(json_str "$ACCENT")\""; fi
if [ -n "$RADIUS" ]; then style="$style,\"radius\":$RADIUS"; fi
style="$style}"

chain_json=""
if [ -n "$CHAIN" ]; then chain_json=",\"chain\":\"$(json_str "$CHAIN")\""; fi
op_json=""
if [ "$CLOSE" = 1 ]; then op_json=",\"op\":\"close\""; fi

json="{\"id\":\"$id\",\"timeout\":$TIMEOUT$chain_json$op_json,\"ui\":{\"title\":\"$(json_str "$TITLE")\",\"message\":\"$(json_str "$MESSAGE")\",\"rows\":[$rows]},\"style\":$style}"
# 原子写：先写临时文件再 rename，保证文件到达时内容已完整（Android 端监听 MOVED_TO 立即触发）
printf '%s\n' "$json" > "$reqfile.tmp" && mv "$reqfile.tmp" "$reqfile"

# ---------- 等待结果（优先 inotifywait，回退 0.02s 轮询） ----------
itimeout=$(awk -v t="$TIMEOUT" 'BEGIN { printf "%d", int(t) + (t > int(t)) }')
if command -v inotifywait >/dev/null 2>&1; then
  # deadline 循环：避免被他人 id 误唤醒导致超时误判，精确比对文件名
  deadline=$(( $(date +%s) + itimeout + 1 ))
  while [ ! -f "$resfile" ] && [ "$(date +%s)" -lt "$deadline" ]; do
    remain=$(( deadline - $(date +%s) ))
    [ "$remain" -le 0 ] && break
    [ "$remain" -gt 1 ] && remain=1
    fname=$(inotifywait -q -e create -e moved_to --timeout "$remain" --format '%f' "$RES_DIR" 2>/dev/null)
    # 仅当唤醒文件名精确等于 $id.json 时才跳出；否则继续等
    if [ "$fname" = "$id.json" ]; then
      break
    fi
    # 误唤醒：检查是否已到达
    [ -f "$resfile" ] && break
  done
  # 触达可能早于文件写完，短暂兜底
  if [ -f "$resfile" ]; then
    sleep 0.05
  fi
else
  attempts=$(awk -v t="$TIMEOUT" 'BEGIN { printf "%d", int(t * 50) + 1 }')
  i=0
  while [ "$i" -lt "$attempts" ]; do
    [ -f "$resfile" ] && break
    sleep 0.02
    i=$(( i + 1 ))
  done
fi

if [ -f "$resfile" ]; then
  # 校验：首非空白字符为 { 且含 "id" 字段（id 为按钮 id/timeout，非请求 id；串扰已由文件名 deadline 循环保证）
  first=""
  while IFS= read -r -n1 ch; do
    case "$ch" in
      ' '|$'\t'|$'\n'|$'\r') continue ;;
      *) first="$ch"; break ;;
    esac
  done < "$resfile"
  if [ "$first" = "{" ] && grep -q '"id"' "$resfile"; then
    cat "$resfile"
  else
    echo '{"id":"error","error":"invalid_response"}'
  fi
  rm -f "$resfile" "$reqfile"
else
  echo '{"id":"timeout","values":{}}'
  rm -f "$reqfile" "$resfile"
fi
exit 0
