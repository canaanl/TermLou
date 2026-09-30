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
}
