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
#   --timeout 秒           超时（默认 60；0 = 永不自动超时）
#   --no-timeout           等价于 --timeout 0
#   --close                显式关闭当前浮窗（单例）
#   --chain CHAIN_ID       已废弃：单例自动原位更新，无需分组（保留兼容）
#   --theme dark|light|glass
#   --accent #RRGGBB       主色
#   --radius N             圆角 dp
#   --position center|bottom
#   --anim fade|scale|slide-up  # 仅首建生效，原位更新为 Diff + 高度平滑
#
# Canvas（原生 Android Canvas，不接 WebView）:
#   --canvas JSON|@文件     初始 Canvas 场景（JSON 对象）
#   --canvas-file FILE      从文件读取初始场景 JSON
#   --canvas-height N       Canvas 行高度 dp（默认 220）
#   --canvas-key KEY        Canvas 稳定标识，patch 精确匹配
#   --canvas-update JSON|@文件
#                           只 patch 活动 Canvas，不新建行、不写结果
#   --canvas-progress 节点=0..1  更新进度节点（可多个）
#   --canvas-scene @文件    整体替换活动场景
#   --canvas-play|--canvas-pause|--canvas-seek MS  控制动画时钟
#   patch 通道：无匹配活动 Canvas 时丢弃；无 --canvas-key 时作用于全部活动 Canvas
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
CANVAS_JSON=""
CANVAS_FILE=""
CANVAS_HEIGHT=220
CANVAS_KEY=""
CANVAS_UPDATE=""
CANVAS_PROGRESS=()
CANVAS_SCENE_FILE=""
CANVAS_PLAY=0
CANVAS_PAUSE=0
CANVAS_SEEK=""
PATCH_ONLY=0
NO_TIMEOUT=0

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
       termlou-ui --canvas-update JSON|@FILE [--canvas-key KEY] [...]

Pop up an interactive overlay above any app; the result prints to stdout as JSON.
An existing live overlay is updated in place instead of creating a new one.

Basic options:
  --title T              Title
  --message M            Body
  --button text=id[=kind][=close]
                         Buttons (repeatable; kind: normal|primary|danger;
                         close 1=close after tap (default), 0=keep window)
  --input label=key      Text input row
  --default V            Default for input/toggle (single control only)
  --default key=V        Default per key, or toggle state (1/on/true/yes = on)
  --select label=key     Single-choice list (followed by --option items)
  --check label=key      Multi-choice list (followed by --option items)
  --option V             List option ("value#tag" carries a filter tag)
  --toggle label=key     Toggle row (default off; result "1"/"0")
  --toggle label=key=tag Filter toggle: when on, only #tag options show
  --output FILE          Render file content as text output (ANSI colors OK)
  --timeout SEC          Timeout in seconds (default 60; 0 means never)
  --no-timeout           Same as --timeout 0; wait until a button/close request
  --close                Close the current overlay explicitly
  --chain CHAIN_ID       Deprecated (singleton updates in place, kept for compat)
  --theme dark|light|glass
  --accent #RRGGBB       Accent color
  --radius N             Corner radius in dp
  --position center|bottom
  --anim fade|scale|slide-up
  -h, --help             Show this help

Canvas options (native Android Canvas, no WebView):
  --canvas JSON|@FILE     Add an initial Canvas row; JSON is a scene object
  --canvas-file FILE      Read the initial scene JSON from FILE
  --canvas-height N       Canvas row height in dp (default 220)
  --canvas-key KEY        Stable canvas id used by patches; letters/digits/._-
  --canvas-update JSON|@FILE
                          Patch the active canvas only: no new row, no result file
  --canvas-progress NODE=0..1
                          Patch one progress node (repeatable; animated 300 ms)
  --canvas-scene @FILE    Replace the whole active scene (patch channel)
  --canvas-play           Resume the active canvas animation clock
  --canvas-pause          Pause the active canvas animation clock
  --canvas-seek MS        Seek the active canvas animation clock (integer ms)
  --no-timeout            Keep the dialog open for long-running canvas updates
  Patch options produce no new Entry and never write a result. They are dropped
  when no matching live canvas exists. Without --canvas-key, all active canvases
  receive the patch. Use a background termlou-ui with --no-timeout, then update it.

Canvas scene JSON:
  width,height            Design size in dp (default 360x220)
  background              #AARRGGBB scene background
  cameraX,cameraY,cameraZoom
  nodes[]                 Draw order: first is bottom, last is top
  animations[]            Optional animation list (see below)
  particles{count,color,size,speed,drift,seed}
  Node types: rect|roundRect|circle|ellipse|line|path|text|image|progress|group
  Common node fields: id,type,x,y,w,h,rotation,scaleX,scaleY,alpha,visible,
    action,hitTest,locked,name,shadow,shadowRadius
  Shape fields: fill,stroke,strokeWidth,radius,dash,dashGap,dashOffset
  Text fields: text,textSize,textColor,textAlign(left|center|right),bold,italic,
    monospace
  Image fields: src(file:// or content://),fit(contain|cover|stretch),tint
  Path/line fields: points([{x,y}] or [[x,y]]),d(SVG path subset),closed,trim
    d supports M/L/H/V/C/Q/S/T/A/Z; curves are expanded to editable points.
    points are node-local; the node x/y is the bounding-box origin.
  Progress fields: progress(0..1),trackColor,barColor,gradientFrom,gradientTo,
    radius,showLabel,labelFormat("{percent}%" or "{value}"),labelColor,labelSize
    Progress is linear and determinate only (no ring/indeterminate/multi-segment).
  Group fields: children[] (same node schema; nested up to 4 levels)
  Limits: 200 nodes, 2000 points, 200 animations, 120 keyframes each.

Animations:
  target or targets[]     Node id(s), or "@camera"
  property                x,y,w,h,rotation,scaleX,scaleY,alpha,progress,textSize,
                          strokeWidth,radius,fill,stroke,textColor,trackColor,
                          barColor,cameraX,cameraY,cameraZoom,path
  from,to                 Numeric range (optional; base value is the fallback)
  fromColor,toColor       Color range for color properties
  fromPath,toPath         SVG paths for path morph (sampled isomorphically)
  duration,delay          Milliseconds
  stagger                 Extra delay per target for parallel sequences
  repeat                  Count; <=0 means loop forever
  alternate               true = ping-pong each cycle
  easing                  linear|ease|ease-in|ease-out|ease-in-out|back|elastic|
                          cubic-bezier(x1,y1,x2,y2)|spring|steps(N)
  keyframes[{at,value,easing}]  at is 0..1 within one cycle
  Multiple animations on one node run in parallel; use delay/stagger to build
  sequences. Camera animations use target "@camera".

Runtime actions (node "action"):
  toggle:NODE_ID | show:NODE_ID | hide:NODE_ID | select:NODE_ID
  progress:NODE_ID:0..1 | seek:MS | play | pause
  submit:ID | submit:ID:keep
  Actions update the scene in place; they do not write req/res or rebuild the card.

Patch examples:
  termlou-ui --title "Build" --canvas @scene.json --no-timeout --button "Close=close"
  termlou-ui --canvas-update '{"node":"bar","progress":0.42,"animate":true,"duration":300}'
  termlou-ui --canvas-update '{"node":"title","text":"Done","fill":"#4DD0E1"}'
  termlou-ui --canvas-progress bar=0.8 --canvas-progress bar2=1
  termlou-ui --canvas-update '{"play":true}'
  termlou-ui --canvas-update '{"seek":1200}'
  termlou-ui --canvas-scene @new-scene.json --canvas-key main
  Patch JSON may also wrap fields in "patch": {"patch":{"node":"bar","progress":0.5}}.
  --canvas-progress accepts NODE=0..1. --canvas-seek takes integer milliseconds.

Canvas click -> shell command:
  submit:ID         Tap writes {"id":"ID","values":{...}} and closes the window.
  submit:ID:keep    Same result, but keeps the window for the next request.
  The app never runs commands: termlou-ui prints JSON, then your shell runs it.
  One request accepts one submit. For continuous use, loop (still sequential):
    while :; do
      r=$(termlou-ui --canvas @panel.json --no-timeout --button "Close=close")
      case "$(printf '%s' "$r" | jq -r .id)" in
        close|dismiss|timeout) break ;;
        start) ./start.sh ;;
      esac
    done

Examples:
  termlou-ui --title "Confirm" --message "Continue?" --button "Go=ok=primary" --button "Quit=cancel"
  ls -l > out.txt && termlou-ui --title "List" --output out.txt --button Close
  termlou-ui --title "Services" --check "Host=hosts" --option "a#up" --option "b#down" --toggle "Running only=filter=up"
  termlou-ui --title "Step 1" --message "First page" --button "Next=next=primary"  # updates the live window in place
  termlou-ui --title "Menu" --button "Back=back=normal=0" --button "Exit=quit"  # back keeps it open; send the parent menu next
  termlou-ui --close
  The filter toggle limits the list to #up options; tags never appear in labels or results.
EOF
      else
        cat <<'EOF'
用法: termlou-ui [@模板名] [选项...]
      termlou-ui --canvas-update JSON|@文件 [--canvas-key KEY] [...]

在任意 App 之上弹出可交互浮窗，结果以 JSON 打印到 stdout。
已有活动浮窗时就地更新内容，不重复新建。

基础选项:
  --title T              标题
  --message M            正文
  --button 文本=id[=类型][=关闭]
                         按钮（可多个；类型: normal|primary|danger；
                         关闭 1=点击后关窗(默认)，0=保持窗口等待下一弹窗）
  --input 标签=key        文本输入行
  --default 值           输入行/开关默认值（仅存在一种控件时可用）
  --default key=值       按 key 精确指定输入行默认值或开关状态（1/on/true/yes 为开）
  --select 标签=key       单选列表（后跟若干 --option）
  --check 标签=key        多选列表（后跟若干 --option）
  --option 值            列表选项（可用 "值#标签" 携带过滤标签）
  --toggle 标签=key      开关行（默认关；结果值 "1"/"0"）
  --toggle 标签=key=标签  过滤开关：开启时只显示带对应 #标签 的 select 选项
  --output FILE          把文件内容作为文本输出行（支持 ANSI 色）
  --timeout 秒           超时（默认 60；0 表示永不自动超时）
  --no-timeout           等价于 --timeout 0；一直等到按钮/关闭请求
  --close                显式关闭当前浮窗（单例）
  --chain CHAIN_ID       已废弃（单例自动原位更新，保留兼容）
  --theme dark|light|glass
  --accent #RRGGBB       主色
  --radius N             圆角 dp
  --position center|bottom
  --anim fade|scale|slide-up
  -h, --help             显示本帮助

Canvas 选项（Android 原生 Canvas，不接 WebView）:
  --canvas JSON|@文件     添加一行初始 Canvas；JSON 是场景对象
  --canvas-file FILE      从文件读取初始场景 JSON
  --canvas-height N       Canvas 行高度 dp（默认 220）
  --canvas-key KEY        Canvas 稳定标识，供 patch 精确匹配；字母/数字/._-
  --canvas-update JSON|@文件
                          只 patch 活动 Canvas：不新建行、不写结果文件
  --canvas-progress 节点=0..1
                          更新一个进度节点（可多个；带 300ms 动画）
  --canvas-scene @文件    整体替换活动场景（走 patch 通道）
  --canvas-play           继续活动 Canvas 动画时钟
  --canvas-pause          暂停活动 Canvas 动画时钟
  --canvas-seek MS        跳转活动 Canvas 动画时间（整数毫秒）
  --no-timeout            长任务配合画布更新时保持浮窗不自动关闭
  Patch 不创建新 Entry、不写结果；没有匹配的活动 Canvas 时直接丢弃。
  不传 --canvas-key 时 patch 会应用到所有活动 Canvas。建议后台运行一个
  --no-timeout 的 termlou-ui，再用 --canvas-update 持续更新。

Canvas 场景 JSON:
  width,height            设计尺寸 dp（默认 360x220）
  background              #AARRGGBB 场景背景
  cameraX,cameraY,cameraZoom
  nodes[]                 绘制顺序：先画的在下，后画的在上
  animations[]            可选动画列表（见下）
  particles{count,color,size,speed,drift,seed}
  节点类型: rect|roundRect|circle|ellipse|line|path|text|image|progress|group
  通用字段: id,type,x,y,w,h,rotation,scaleX,scaleY,alpha,visible,
    action,hitTest,locked,name,shadow,shadowRadius
  图形字段: fill,stroke,strokeWidth,radius,dash,dashGap,dashOffset
  文本字段: text,textSize,textColor,textAlign(left|center|right),bold,italic,
    monospace
  图片字段: src(file:// 或 content://),fit(contain|cover|stretch),tint
  路径/直线: points([{x,y}] 或 [[x,y]]),d(SVG path 子集),closed,trim
    d 支持 M/L/H/V/C/Q/S/T/A/Z；曲线会展开成可编辑点。
    points 是节点局部坐标；节点 x/y 是包围盒原点。
  进度条: progress(0..1),trackColor,barColor,gradientFrom,gradientTo,
    radius,showLabel,labelFormat("{percent}%" 或 "{value}"),labelColor,labelSize
    进度条只支持线性、确定值；不做环形、多段、不确定进度。
  分组: children[]（同样的节点结构；最多嵌套 4 层）
  上限: 200 节点、2000 点、200 条动画、每条动画 120 个关键帧。

动画:
  target 或 targets[]     节点 id（可多个）或 "@camera"
  property                x,y,w,h,rotation,scaleX,scaleY,alpha,progress,textSize,
                          strokeWidth,radius,fill,stroke,textColor,trackColor,
                          barColor,cameraX,cameraY,cameraZoom,path
  from,to                 数值范围（可空；省略时以节点原值为基准）
  fromColor,toColor       颜色属性的起止颜色
  fromPath,toPath         路径 morph 的 SVG 起止路径（同构采样）
  duration,delay          毫秒
  stagger                 多目标并行时的逐个错峰延迟
  repeat                  次数；<=0 表示无限循环
  alternate               true = 每个循环往复
  easing                  linear|ease|ease-in|ease-out|ease-in-out|back|elastic|
                          cubic-bezier(x1,y1,x2,y2)|spring|steps(N)
  keyframes[{at,value,easing}]  at 是单次循环内 0..1 的关键帧位置
  同一节点的多条动画并行；用 delay/stagger 组合出顺序动画。
  相机动画 target 写 "@camera"。

运行时交互动作（节点 "action"）:
  toggle:节点ID | show:节点ID | hide:节点ID | select:节点ID
  progress:节点ID:0..1 | seek:毫秒 | play | pause
  submit:ID | submit:ID:keep
  动作只原位更新场景，不写 req/res，不重建卡片。

Patch 示例:
  termlou-ui --title "构建" --canvas @scene.json --no-timeout --button "关闭=close"
  termlou-ui --canvas-update '{"node":"bar","progress":0.42,"animate":true,"duration":300}'
  termlou-ui --canvas-update '{"node":"title","text":"完成","fill":"#4DD0E1"}'
  termlou-ui --canvas-progress bar=0.8 --canvas-progress bar2=1
  termlou-ui --canvas-update '{"play":true}'
  termlou-ui --canvas-update '{"seek":1200}'
  termlou-ui --canvas-scene @new-scene.json --canvas-key main
  Patch 也可把字段包在 "patch" 内: {"patch":{"node":"bar","progress":0.5}}。
  --canvas-progress 接受 节点=0..1；--canvas-seek 接受整数毫秒。

Canvas 点击 → 执行命令:
  submit:ID         点击写出 {"id":"ID","values":{...}} 并关窗。
  submit:ID:keep    写同样的结果，但保持窗口等待下一次请求。
  App 不执行命令：termlou-ui 只输出 JSON，由调用方 shell 执行。
  一次请求只接受一次提交；连续操作在 shell 里循环（仍然串行）：
    while :; do
      r=$(termlou-ui --canvas @panel.json --no-timeout --button "Close=close")
      case "$(printf '%s' "$r" | jq -r .id)" in
        close|dismiss|timeout) break ;;
        start) ./start.sh ;;
      esac
    done

示例:
  termlou-ui --title "确认" --message "继续?" --button "继续=ok=primary" --button "退出=cancel"
  ls -l > out.txt && termlou-ui --title "列表" --output out.txt --button 关闭
  termlou-ui --title "服务" --check "主机=hosts" --option "a#up" --option "b#down" --toggle "只看运行中=filter=up"
  termlou-ui --title "步骤1" --message "第一页" --button "下一步=next=primary"  # 有窗则原位更新
  termlou-ui --title "菜单" --button "返回=back=normal=0" --button "退出=quit"  # 返回不关窗，接着发上层菜单即回退
  termlou-ui --close
  开启过滤开关后列表只显示带 #标签 的选项；标签不出现在显示与结果里。
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
    --canvas) shift; [ $# -eq 0 ] && die "$(_t "--canvas 缺少参数值" "--canvas needs a value")"; CANVAS_JSON="$1" ;;
    --canvas-file) shift; [ $# -eq 0 ] && die "$(_t "--canvas-file 缺少参数值" "--canvas-file needs a value")"; CANVAS_FILE="$1" ;;
    --canvas-height) shift; [ $# -eq 0 ] && die "$(_t "--canvas-height 缺少参数值" "--canvas-height needs a value")"; CANVAS_HEIGHT="$1" ;;
    --canvas-key) shift; [ $# -eq 0 ] && die "$(_t "--canvas-key 缺少参数值" "--canvas-key needs a value")"; CANVAS_KEY="$1" ;;
    --canvas-update) shift; [ $# -eq 0 ] && die "$(_t "--canvas-update 缺少参数值" "--canvas-update needs a value")"; CANVAS_UPDATE="$1"; PATCH_ONLY=1 ;;
    --canvas-progress) shift; [ $# -eq 0 ] && die "$(_t "--canvas-progress 缺少参数值" "--canvas-progress needs a value")"; CANVAS_PROGRESS+=("$1"); PATCH_ONLY=1 ;;
    --canvas-scene) shift; [ $# -eq 0 ] && die "$(_t "--canvas-scene 缺少参数值" "--canvas-scene needs a value")"; CANVAS_SCENE_FILE="$1"; PATCH_ONLY=1 ;;
    --canvas-play) CANVAS_PLAY=1; PATCH_ONLY=1 ;;
    --canvas-pause) CANVAS_PAUSE=1; PATCH_ONLY=1 ;;
    --canvas-seek) shift; [ $# -eq 0 ] && die "$(_t "--canvas-seek 缺少参数值" "--canvas-seek needs a value")"; CANVAS_SEEK="$1"; PATCH_ONLY=1 ;;
    --no-timeout) NO_TIMEOUT=1 ;;
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
if [ "$NO_TIMEOUT" = 1 ]; then TIMEOUT=0; fi
if [ -n "$RADIUS" ]; then
  [[ "$RADIUS" =~ ^[0-9]+$ ]] || die "--radius $(_t "必须是整数" "must be an integer"): $RADIUS"
fi
[[ "$CANVAS_HEIGHT" =~ ^[0-9]+$ ]] || die "--canvas-height $(_t "必须是整数" "must be an integer"): $CANVAS_HEIGHT"
if [ -n "$CANVAS_KEY" ] && ! [[ "$CANVAS_KEY" =~ ^[A-Za-z0-9._-]+$ ]]; then
  die "--canvas-key $(_t "非法" "invalid"): $CANVAS_KEY"
fi
if [ -n "$CANVAS_FILE" ]; then
  [ -f "$CANVAS_FILE" ] || die "--canvas-file $(_t "文件不存在" "file not found"): $CANVAS_FILE"
  CANVAS_JSON="$(cat "$CANVAS_FILE")"
fi
case "$CANVAS_JSON" in
  @*) f="${CANVAS_JSON#@}"; [ -f "$f" ] || die "--canvas $(_t "文件不存在" "file not found"): $f"; CANVAS_JSON="$(cat "$f")" ;;
esac
if [ -n "$CANVAS_JSON" ]; then
  case "$CANVAS_JSON" in
    \{*) ;;
    *) die "--canvas $(_t "必须是 JSON 对象" "must be a JSON object")" ;;
  esac
fi
if [ -n "$CANVAS_SCENE_FILE" ]; then
  case "$CANVAS_SCENE_FILE" in
    @*) CANVAS_SCENE_FILE="${CANVAS_SCENE_FILE#@}" ;;
  esac
  [ -f "$CANVAS_SCENE_FILE" ] || die "--canvas-scene $(_t "文件不存在" "file not found"): $CANVAS_SCENE_FILE"
fi
if [ -n "$CANVAS_SEEK" ] && ! [[ "$CANVAS_SEEK" =~ ^[0-9]+$ ]]; then
  die "--canvas-seek $(_t "必须是整数毫秒" "must be integer milliseconds"): $CANVAS_SEEK"
fi
if [ "$CANVAS_PLAY" = 1 ] && [ "$CANVAS_PAUSE" = 1 ]; then
  die "$(_t "--canvas-play 与 --canvas-pause 不能同时使用" "--canvas-play and --canvas-pause cannot be used together")"
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

# ---------- Canvas patch：只更新活动浮窗里的 Canvas，不新建 Entry、不等待结果 ----------
if [ "$PATCH_ONLY" = 1 ]; then
  patch_no=0
  write_patch() {
    local body="$1"
    patch_no=$((patch_no + 1))
    local pid="${id}_p${patch_no}"
    if [ -n "$CANVAS_KEY" ]; then
      body="{$(printf '"key":"%s",' "$(json_str "$CANVAS_KEY")")${body#\{}"
    fi
    local pf="$REQ_DIR/$pid.json"
    printf '%s\n' "{\"op\":\"canvas\",\"id\":\"$pid\",\"patch\":$body}" > "$pf.tmp" && mv "$pf.tmp" "$pf"
  }
  wrote=0
  if [ -n "$CANVAS_SCENE_FILE" ]; then
    scene_body="$(cat "$CANVAS_SCENE_FILE")"
    case "$scene_body" in
      \{*) write_patch "{\"scene\":$scene_body}"; wrote=1 ;;
      *) die "--canvas-scene $(_t "必须是 JSON 对象" "must be a JSON object")" ;;
    esac
  fi
  if [ -n "$CANVAS_UPDATE" ]; then
    case "$CANVAS_UPDATE" in
      @*) uf="${CANVAS_UPDATE#@}"; [ -f "$uf" ] || die "--canvas-update $(_t "文件不存在" "file not found"): $uf"; ub="$(cat "$uf")" ;;
      *) ub="$CANVAS_UPDATE" ;;
    esac
    case "$ub" in
      \{*) write_patch "$ub"; wrote=1 ;;
      *) die "--canvas-update $(_t "必须是 JSON 对象" "must be a JSON object")" ;;
    esac
  fi
  for spec in "${CANVAS_PROGRESS[@]:-}"; do
    [ -z "$spec" ] && continue
    nid="${spec%%=*}"; val="${spec#*=}"
    [ "$nid" = "$spec" ] && die "--canvas-progress $(_t "格式应为 节点=0..1" "expected node=0..1"): $spec"
    [[ "$val" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "--canvas-progress $(_t "进度必须是数字" "progress must be a number"): $spec"
    write_patch "{\"node\":\"$(json_str "$nid")\",\"progress\":$val,\"animate\":true,\"duration\":300}"
    wrote=1
  done
  if [ "$CANVAS_PLAY" = 1 ] || [ "$CANVAS_PAUSE" = 1 ] || [ -n "$CANVAS_SEEK" ]; then
    ctl=""
    [ "$CANVAS_PLAY" = 1 ] && ctl="\"play\":true"
    [ "$CANVAS_PAUSE" = 1 ] && ctl="${ctl:+$ctl,}\"play\":false"
    [ -n "$CANVAS_SEEK" ] && ctl="${ctl:+$ctl,}\"seek\":$CANVAS_SEEK"
    write_patch "{$ctl}"
    wrote=1
  fi
  [ "$wrote" = 1 ] || die "$(_t "--canvas-update/--canvas-progress/--canvas-scene/--canvas-play/--canvas-pause/--canvas-seek 至少需要一个" "one of --canvas-update/--canvas-progress/--canvas-scene/--canvas-play/--canvas-pause/--canvas-seek is required")"
  exit 0
fi

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

# Canvas 场景行（原生 Canvas；场景 JSON 原样嵌入）
if [ -n "$CANVAS_JSON" ]; then
  key_json=""
  if [ -n "$CANVAS_KEY" ]; then key_json=",\"key\":\"$(json_str "$CANVAS_KEY")\""; fi
  rows="${rows}${sep}{\"kind\":\"canvas\",\"height\":$CANVAS_HEIGHT$key_json,\"scene\":$CANVAS_JSON}"
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
if [ "$TIMEOUT" = "0" ]; then
  # --no-timeout：浮窗不自动关闭，脚本持续等待结果（适合后台任务 + --canvas-update）
  if command -v inotifywait >/dev/null 2>&1; then
    while [ ! -f "$resfile" ]; do
      fname=$(inotifywait -q -e create -e moved_to --timeout 1 --format '%f' "$RES_DIR" 2>/dev/null)
      if [ "$fname" = "$id.json" ]; then break; fi
      [ -f "$resfile" ] && break
    done
    [ -f "$resfile" ] && sleep 0.05
  else
    while [ ! -f "$resfile" ]; do sleep 0.02; done
  fi
elif command -v inotifywait >/dev/null 2>&1; then
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
