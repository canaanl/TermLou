package com.workspace.probe

/**
 * 测试页面：本地 HTML，不走网络。
 *
 * ## 为什么带 `<meta name="viewport">`
 * 没有它时 Chromium 用 **980 CSS px 默认视口**，和"412dp × 密度"的像素数容易撞上，
 * 导致"视口到底由谁决定"分不清（v1 就栽在这儿）。有了它 1 CSS px = 1 dp，
 * `innerWidth` 就该等于我给的 dp 宽度，是可判据。
 *
 * ## 各项在测什么
 *  - `#fifty`（`width:50%`）：CSS 排版有没有真发生。防"innerText 有内容是巧合"。
 *  - `#hidden`（`display:none`）：`innerText` 该看不见它 —— 这项挂掉，
 *    `text` op 与 `text=` 选择器都会失真。
 *  - `#tick`：400ms 后被脚本改字，测 JS。
 *  - `#img`：内联 data URI PNG，测图片加载。
 *  - `#cv` + 画红 60×60：测 canvas（要 GPU 路径，最容易挂）。
 *  - `#btn` / `#inp`：测点与填。
 *  - **v3 新增** `#lnk`：点同文档锚点，测**同文档导航与历史栈** ——
 *    TermLou 有 `back`/`reload` 指令，导航与历史不通的话那是真回归。
 *  - **v3 新增** `#sel`：测 `<select>` 选值。
 */
object ProbePage {

    private const val RED_PNG =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAHUlEQVQI12P4//8" +
            "/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg=="

    val html: String = """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>probe-ready</title>
<style>
  body { margin: 0; font: 16px sans-serif; }
  #box { background: #eeeeee; padding: 8px; }
  #hidden { display: none; }
</style>
</head>
<body>
<div id="box">
  <h1 id="h1">探针页面</h1>
  <p id="text">可见正文 ABC-123</p>
  <p id="hidden">隐藏文字 SECRET</p>
  <div id="fifty" style="width:50%; background:#cde; height:20px"></div>
  <p id="tick">等待中</p>
  <canvas id="cv" width="60" height="60"></canvas>
  <img id="img" src="$RED_PNG" width="16" height="16">
  <button id="btn" onclick="document.getElementById('text').textContent='已点击'">按我</button>
  <input id="inp" type="text">
  <select id="sel">
    <option value="a">甲</option>
    <option value="b">乙</option>
    <option value="c">丙</option>
  </select>
  <p><a id="lnk" href="#done">跳到锚点</a></p>
</div>
<script>
  setTimeout(function () {
    document.getElementById('tick').textContent = 'JS 已执行';
  }, 400);
  var c = document.getElementById('cv').getContext('2d');
  if (c) { c.fillStyle = '#ff0000'; c.fillRect(0, 0, 60, 60); }
</script>
</body></html>
""".trimIndent()

    /** 一次把各项原始观测值全取回来。只报观测，不下判断（判断在 [ProbeVerdict]）。 */
    val PROBE_JS: String = """
(function(){
  function t(id){var e=document.getElementById(id);return e?e:null;}
  var fifty=t('fifty');
  var out={};
  out.title=document.title;
  out.readyState=document.readyState;
  out.visState=document.visibilityState;
  out.innerW=window.innerWidth;
  out.innerH=window.innerHeight;
  out.docW=document.documentElement?document.documentElement.getBoundingClientRect().width:0;
  out.fiftyW=fifty?Math.round(fifty.getBoundingClientRect().width):-1;
  out.innerText=document.body?document.body.innerText:'';
  out.innerTextLen=out.innerText.length;
  out.innerTextHasSecret=out.innerText.indexOf('SECRET')>=0;
  out.tick=t('tick')?t('tick').textContent:'';
  out.h1=t('h1')?t('h1').textContent:'';
  var img=t('img');
  out.imgComplete=img?img.complete:false;
  out.imgW=img?(img.naturalWidth||0):-1;
  var cv=t('cv');
  if(cv){
    try{
      var d=cv.getContext('2d').getImageData(0,0,1,1).data;
      out.canvasPx=d[0]+','+d[1]+','+d[2];
    }catch(e){ out.canvasPx='ERR:'+e.name; }
  } else { out.canvasPx='no-canvas'; }
  try{ t('btn').click(); out.afterClick=t('text').textContent; }catch(e){ out.afterClick='ERR:'+e.name; }
  try{
    var i=t('inp'); i.value='probe-typed'; i.dispatchEvent(new Event('input',{bubbles:true}));
    out.afterType=i.value;
  }catch(e){ out.afterType='ERR:'+e.name; }
  try{
    var s=t('sel'); s.value='b';
    s.dispatchEvent(new Event('change',{bubbles:true}));
    out.afterSelect=s.value;
  }catch(e){ out.afterSelect='ERR:'+e.name; }
  // 同文档导航：点锚点，历史栈应当 +1。不走网络，所以不需要服务器。
  out.histBefore=history.length;
  try{ t('lnk').click(); }catch(e){}
  out.histAfter=history.length;
  out.hash=location.hash;
  return JSON.stringify(out);
})()
""".trimIndent()

    /** 真实站点用：只取最稳的几个指标。 */
    val REAL_JS: String = """
(function(){
  return JSON.stringify({
    title: document.title,
    innerW: window.innerWidth,
    innerH: window.innerHeight,
    len: document.body?document.body.innerText.length:0,
    head: document.body?document.body.innerText.slice(0,50).replace(/\\s+/g,' '):'',
    links: document.querySelectorAll('a').length,
    imgs: document.images.length,
    hist: history.length,
    visState: document.visibilityState,
    h1: (document.querySelector('h1')||{}).textContent||''
  });
})()
""".trimIndent()

    /** 挑不出真实内容时（WebView 自己的错误页）如实说出来，别当成测到了。 */
    fun looksLikeErrorPage(o: org.json.JSONObject?): Boolean {
        if (o == null) return true
        val t = o.optString("title", "")
        val l = o.optInt("links", -1)
        return l == 0 || t.contains("无法打开") || t.contains("not available") ||
            t.contains("Webpage not available") || t.contains("ERR_")
    }

    // ---------- v7：滚动测试 ----------

    /** 底部那条纯红色。`Ink.countRed` 按这个色相认。 */
    const val RED = "#ff0000"

    /** 顶部那条纯绿色。`Ink.countGreen` 按这个色相认。 */
    const val GREEN = "#00c000"

    /**
     * 长页面，用来测**滚动之后还能不能截图**。
     *
     * ## 为什么顶部绿、底部红
     *
     * v5/v6 只在底部铺红，于是**「滚之前那张图长什么样」从来没被量过**。
     * 而那一页的 body 底色是 `#e9e9e9`（233 < 245），整屏都算非白 ——
     * v5 实测滚前非白 = 3,304,928 / 3,307,536 = **99.92%**，
     * 有没有内容画出来这个数字都是满的，**压根不能当判据**。
     *
     * 所以必须有两个互不干扰的信号：
     *  - **顶部纯绿**（250px × 整宽）：滚到 0 时它就在视口最上面。画出来 = 长页面能画。
     *  - **底部纯红**（250px × 整宽）：滚到底时它填视口下沿。画出来 = 滚过去也画得出。
     *
     * 于是能分清三种情况，v5/v6 全都分不清：
     *  1. 绿也没有 → 长页面**从来没画出来过**，要换测试页
     *  2. 绿有、红没有 → 确实是滚动后才坏（本轮要修的）
     *  3. 绿有、红也有 → 根本没问题（或者被某个变体修好了）
     *
     * 色块都取纯色、与页面其余内容无关，判据不靠"猜画面长什么样"。
     */
    val longHtml: String = """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>probe-long</title>
<style>
  body { margin: 0; background: #e9e9e9; }
  .band { height: 620px; }
  #b1 { background: #334455; }
  #b2 { background: #99aacc; }
  #b3 { background: #cc8555; }
  #mark { height: 60px; background: #111111; color: #ffffff; }
  #head { height: 250px; background: $GREEN; }
  #tail { height: 250px; background: $RED; }
</style>
</head>
<body>
  <div id="head"></div>
  <div class="band" id="b1"></div>
  <div class="band" id="b2"></div>
  <div class="band" id="b3"></div>
  <div id="mark">BOTTOM-MARKER</div>
  <div id="tail"></div>
</body>
</html>
""".trimIndent()

    /**
     * 同一页长页面，**首帧就把整页内容贴到底**。
     *
     * ## 为什么要一个「贴底版」
     *
     * 绿顶红底那一版是给「滚到底再画」用的。v5~v7 量了 22 次，滚出去的那块就是画不出来。
     * 于是 v8 改成**让底部落在首屏里**。
     *
     * **关键是必须在首帧之前挪好。** 用 JS 挪的话首帧早就画完了，
     * 还是要等新帧 —— 那和 `REPAINT` 变体没区别，白测。所以偏移**烤进 HTML**。
     *
     * ## 为什么用纯 CSS 而不是算一个像素值
     *
     * `body { position: absolute; bottom: 0 }` 让 body 的底边贴住初始包含块的底边
     * （也就是视口底边），于是 2420px 高的 body 顶上来了 −1528px ——
     * **这个 −1528 是浏览器自己算的**，不依赖我这里页面的具体高度，页面改了就自动跟着对。
     * 写死 `top: -1528px` 的话页面一改就悄悄测错东西。
     *
     * 视口（`innerHeight`）没变、版面也没变 —— 这是它比「视图撑到整页」好的地方。
     * 代价：`position: fixed` 的元素会留在原处，贴底的 body 盖不住它。
     */
    val longHtmlShifted: String = """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>probe-long-shifted</title>
<style>
  html { overflow: hidden; }
  body { margin: 0; position: absolute; left: 0; right: 0; bottom: 0; background: #e9e9e9; }
  .band { height: 620px; }
  #b1 { background: #334455; }
  #b2 { background: #99aacc; }
  #b3 { background: #cc8555; }
  #mark { height: 60px; background: #111111; color: #ffffff; }
  #head { height: 250px; background: $GREEN; }
  #tail { height: 250px; background: $RED; }
</style>
</head>
<body>
  <div id="head"></div>
  <div class="band" id="b1"></div>
  <div class="band" id="b2"></div>
  <div class="band" id="b3"></div>
  <div id="mark">BOTTOM-MARKER</div>
  <div id="tail"></div>
</body>
</html>
""".trimIndent()

    /** 把页面滚到底。返回当前 scrollY。 */
    val SCROLL_JS: String = """
(function(){
  window.scrollTo(0, document.documentElement.scrollHeight);
  return String(Math.round(window.scrollY));
})()
""".trimIndent()

    /**
     * 滚到 99% —— 留 1px 的滚动余量，不进那个"完全静止"的位置。
     *
     * v9 怀疑 `SCROLL_JS` 滚到的最底部是个**整像素对齐的静止点**：
     * `scrollY = scrollHeight - innerHeight`，页面再也没有"没露出来的内容"，
     * Chromium 可能因此认为"不需要新帧" —— 不是产不出，是觉得不用产。
     * 之前 11 种全是"逼它出帧"，没有一种是"让它觉得有帧可出"。
     * 留 1px 的话合成器还有理由出一帧。
     */
    val SCROLL_99_JS: String = """
(function(){
  var de=document.documentElement, ih=window.innerHeight;
  window.scrollTo(0, Math.max(0, de.scrollHeight-ih-1));
  return String(Math.round(window.scrollY));
})()
""".trimIndent()

    /**
     * 起一个 ±1px 的抖动循环（20 次 × 120ms），**画的时候它还在抖**。
     *
     * 和 `NUDGE_JS` 的区别：那个是抖完、停稳、回到静止点**之后**才画；
     * 这个是队列在 1200ms 后直接量，而循环要 2400ms 才结束 ——
     * 量的时候滚动事件还在进，页面没有机会进入静止点。
     */
    val JITTER_JS: String = """
(function(){
  var de=document.documentElement, ih=window.innerHeight;
  var bottom=Math.max(0, de.scrollHeight-ih);
  var n=0;
  function step(){
    window.scrollTo(0, bottom+(n%2===0?-1:1));
    n++;
    if(n<20) setTimeout(step, 120); else window.scrollTo(0, bottom);
  }
  setTimeout(step, 0);
  return 'jittering';
})()
""".trimIndent()

    /**
     * 滚到底，然后改底部标记的字色和字样，逼出一个 DOM 脏区。
     *
     * 改的是 `#tail` 前面那块 deep-gray 的标记（`querySelectorAll('div')`
     * 倒数第二个），不动 `#tail` 本身 —— 红色块的颜色不能动，否则红判据就废了。
     * 改完读一次 `offsetHeight` 强制同步布局，免得浏览器把改动合并掉。
     * 如果这张红了 → 帧生产本身没坏，坏的是"滚动不标记脏区"。
     */
    val DIRTY_JS: String = """
(function(){
  var de=document.documentElement;
  window.scrollTo(0, de.scrollHeight);
  var ms=document.querySelectorAll('div');
  var m=ms[ms.length-2];
  m.style.color='#00ff00'; m.textContent='DIRTY-'+Date.now(); void m.offsetHeight;
  return 'dirty';
})()
""".trimIndent()

    /**
     * 滚到底之后抖一下 `opacity`，逼 Chromium 重画整页。
     *
     * 每改一次就读一次 `offsetHeight` 强制同步布局 —— 这一步是关键：
     * 不读的话浏览器可能把三次改动合并成一次，中间那些帧压根不存在。
     */
    val REPAINT_JS: String = """
(function(){
  var h=document.documentElement, b=document.body;
  h.style.opacity='0.999'; void b.offsetHeight;
  h.style.opacity='0.998'; void b.offsetHeight;
  h.style.opacity='';        void b.offsetHeight;
  return 'repainted';
})()
""".trimIndent()

    /**
     * v12：滚到底，然后往 `body` 末尾**插一个 100px 的元素**，再滚一次到底。
     *
     * 和 `DIRTY_JS` 的区别：那个只改字色字样，是装饰性 dirty，测过，不行。
     * 这个改变布局 —— `scrollHeight` 变大、整页重排。从没试过"布局变了"
     * 能不能逼出新 tile。如果这张红了，根因收敛到
     * "scroll 不触发 raster 调度，但 layout 可以"。
     *
     * 红色块本身不动（新元素插在它后面），红判据继续有效。
     * 插完再滚一次到底：`tailVisible` 保持 true，位置判据不作废。
     */
    val LAYOUT_DIRTY_JS: String = """
(function(){
  var de=document.documentElement;
  window.scrollTo(0, de.scrollHeight);
  var d=document.createElement('div');
  d.id='probe-dirty';
  d.style.cssText='height:100px;background:#0000ff;color:#ffffff';
  d.textContent='DIRTY-'+Date.now();
  document.body.appendChild(d);
  void d.offsetHeight;
  window.scrollTo(0, document.documentElement.scrollHeight);
  return 'layout-dirty';
})()
""".trimIndent()

    /**
     * 滚到底之后上下抖 6 下 —— 每次都是一次真实的滚动事件，
     * 逼 Chromium 重算可见区域、更新瓦片。
     *
     * **用 `setTimeout` 而不是 `requestAnimationFrame`**：不挂窗口的 WebView
     * 压根不产帧，`rAF` 回调可能永远不来（那正是这个 bug 的样子），
     * 定时器才保证这一步一定跑完。
     */
    val NUDGE_JS: String = """
(function(){
  var sh=document.documentElement.scrollHeight, ih=window.innerHeight;
  var bottom=Math.max(0, sh-ih);
  var n=0;
  function step(){
    window.scrollTo(0, bottom + (n % 2 === 0 ? -2 : 0));
    n++;
    if(n<6) setTimeout(step, 60); else window.scrollTo(0, bottom);
  }
  setTimeout(step, 0);
  return 'nudging';
})()
""".trimIndent()

    /**
     * 滚动后的状态。**绿块和红块都报** —— v6 只报红块，所以「顶部有没有画出来」
     * 这件最关键的事没人量过。
     */
    val SCROLL_METRICS_JS: String = """
(function(){
  function rect(id){
    var e=document.getElementById(id);
    if(!e) return null;
    var r=e.getBoundingClientRect();
    return {top:Math.round(r.top), bottom:Math.round(r.bottom)};
  }
  function vis(r){
    return !!r && r.top<window.innerHeight && r.bottom>0;
  }
  var h=rect('head'), t=rect('tail');
  // v9：视口底部中心那个点，DOM 认为是什么元素。
  // 如果它是 `#tail`，说明 DOM 层正常、掉的是后面的管线 —— 不是"滚错了地方"。
  var r_el='?';
  try{
    var _el=document.elementFromPoint(window.innerWidth>>1, window.innerHeight-10);
    r_el=_el?(_el.id||_el.tagName):'none';
  }catch(e){}
  return JSON.stringify({
    sy:Math.round(window.scrollY),
    sh:document.documentElement?document.documentElement.scrollHeight:0,
    ih:window.innerHeight,
    headTop:h?h.top:-1,
    headVisible:vis(h),
    tailTop:t?t.top:-1,
    tailVisible:vis(t),
    el:r_el,
    len:document.body?document.body.innerText.length:0,
    visState:document.visibilityState
  });
})()
""".trimIndent()
}
