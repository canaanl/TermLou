package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CanvasSceneTest {

    private companion object {
        const val MID_PATH_MAX_X = 30f
        const val SECOND_CYCLE_MS = 1500L
        const val SCALED_NODE_SCALE_X = 2f
        const val SCALED_NODE_SCALE_Y = 0.5f
        const val LOCAL_FAR_X = 100f
        const val LOCAL_FAR_Y = 60f
        const val LOCAL_MID_X = 25f
        const val LOCAL_MID_Y = 40f
    }

    @Test
    fun `canvas row parses and round trips`() {
        val json = """
            {"id":"x","ui":{"rows":[
              {"kind":"canvas","height":260,"key":"main","scene":{
                "width":360,"height":240,"background":"#101010",
                "nodes":[
                  {"id":"title","type":"text","x":16,"y":12,"w":200,"h":24,"text":"Hello","textSize":18},
                  {"id":"bar","type":"progress","x":16,"y":60,"w":300,"h":12,"progress":0.25,"showLabel":true}
                ],
                "animations":[{"target":"bar","property":"progress","from":0,"to":1,"duration":800,"easing":"ease-out","loop":true}],
                "particles":{"count":12,"color":"#44FFFFFF","speed":8}
              }}
            ]}}
        """.trimIndent()
        val req = ScriptDialogSpec.parseRequest(json)
        val row = req.ui.rows[0] as ScriptDialogSpec.Row.Canvas
        assertEquals(260, row.row.heightDp)
        assertEquals("main", row.row.key)
        assertEquals(2, row.row.scene.nodes.size)
        assertEquals(1, row.row.scene.animations.size)
        assertEquals(-1, row.row.scene.animations[0].repeat)
        assertEquals(12, row.row.scene.particles.count)

        val round = ScriptDialogSpec.parseRequest(ScriptDialogSpec.requestToJson(req))
        val rr = round.ui.rows[0] as ScriptDialogSpec.Row.Canvas
        assertEquals(row.row.scene, rr.row.scene)
        assertEquals(row.row.heightDp, rr.row.heightDp)
        assertEquals(row.row.key, rr.row.key)
    }

    @Test
    fun `widget nodes parse round trip and keep old nodes byte stable`() {
        val old = """{"width":360,"height":240,"nodes":[{"id":"t","type":"text","x":1,"y":2,"w":100,"h":20,"text":"hi"}]}"""
        val oldScene = CanvasSceneCodec.parseScene(old)
        val oldJson = CanvasSceneCodec.sceneToJsonString(oldScene)
        assertFalse(oldJson.contains("\"key\""))
        assertFalse(oldJson.contains("\"wrap\""))
        assertFalse(oldJson.contains("\"clip\""))

        val json = """
            {"width":360,"height":400,"nodes":[
              {"id":"in","type":"input","x":10,"y":20,"w":200,"h":48,
               "key":"name","label":"Name","text":"Ada","password":true,"multiline":true},
              {"id":"sel","type":"select","x":10,"y":80,"w":200,"h":180,
               "key":"hosts","label":"Hosts","options":["a#up","b#down"],"multi":true},
              {"id":"tg","type":"toggle","x":10,"y":280,"w":200,"h":20,
               "key":"filter","label":"Only up","checked":true,"filter":"up"},
              {"id":"btn","type":"button","x":10,"y":320,"w":100,"h":38,
               "text":"Go","action":"submit:go","buttonKind":"primary"},
              {"id":"out","type":"text","x":10,"y":20,"w":200,"h":80,"text":"x","wrap":true,"ansi":true}
            ]}
        """.trimIndent()
        val scene = CanvasSceneCodec.parseScene(json)
        val input = scene.findNode("in")!!
        assertEquals("name", input.key)
        assertEquals("Ada", input.text)
        assertTrue(input.password)
        assertTrue(input.multiline)
        val select = scene.findNode("sel")!!
        assertEquals(listOf("a#up", "b#down"), select.options)
        assertTrue(select.multi)
        val toggle = scene.findNode("tg")!!
        assertTrue(toggle.checked)
        assertEquals("up", toggle.filter)
        val button = scene.findNode("btn")!!
        assertEquals("primary", button.buttonKind)
        assertTrue(scene.findNode("out")!!.wrap)
        assertTrue(scene.findNode("out")!!.ansi)

        val round = CanvasSceneCodec.parseScene(CanvasSceneCodec.sceneToJsonString(scene))
        assertEquals(scene, round)
    }

    @Test
    fun `widget fields are patchable and old fields still patch`() {
        val scene = CanvasSceneCodec.parseScene(
            """{"width":360,"height":240,"nodes":[
               {"id":"in","type":"input","x":1,"y":2,"w":100,"h":48,"key":"a","label":"A","text":"x"},
               {"id":"tg","type":"toggle","key":"t","checked":false}
            ]}"""
        )
        val patch = CanvasPatch.parse(
            """{"node":"in","label":"New","multiline":true,"text":"hello","animate":false}"""
        )
        val out = patch.applyTo(scene)
        assertTrue(out.nodeFound)
        val input = out.scene.findNode("in")!!
        assertEquals("New", input.label)
        assertEquals("a", input.key)
        assertEquals("hello", input.text)
        assertTrue(input.multiline)
        val toggle = CanvasPatch.parse("""{"node":"tg","checked":true}""").applyTo(out.scene)
        assertTrue(toggle.scene.findNode("tg")!!.checked)
    }

    @Test
    fun `canvas submit action parses id and keep flag`() {
        assertEquals(CanvasAction.Submit("start", false), CanvasAction.parseSubmit("submit:start"))
        assertEquals(CanvasAction.Submit("start", true), CanvasAction.parseSubmit("submit:start:keep"))
        assertEquals(CanvasAction.Submit("a:b", true), CanvasAction.parseSubmit("submit:a:b:keep"))
        assertNull(CanvasAction.parseSubmit("submit:"))
        assertNull(CanvasAction.parseSubmit("toggle:start"))
        assertNull(CanvasAction.parseSubmit(""))
    }

    @Test
    fun `scene clamps hostile input`() {
        val huge = buildString {
            append("""{"width":999999,"height":1,"nodes":[""")
            for (i in 0 until 500) {
                if (i > 0) append(',')
                append("""{"id":"n$i","type":"rect","x":1e30,"w":-5,"alpha":9,"text":""")
                append("\"")
                append("a".repeat(9000))
                append("\"}")
            }
            append("]}")
        }
        val scene = CanvasSceneCodec.parseScene(huge)
        assertTrue(scene.width <= 4000f)
        assertTrue(scene.height >= 32f)
        assertTrue(scene.nodes.size <= CanvasSpec.MAX_NODES)
        val n = scene.nodes[0]
        assertTrue(n.alpha <= 1f)
        assertTrue(n.w >= 0f)
        assertTrue(n.text.length <= CanvasSpec.MAX_TEXT)
        assertEquals(scene.width, scene.width)
    }

    @Test
    fun `path parser supports commands and curves`() {
        val p = CanvasPath.parse("M0 0 L10 0 H20 V10 C20 20 10 20 0 10 Q-5 5 0 0 Z")
        assertTrue(p.closed)
        assertTrue(p.points.size >= 10)
        assertTrue(p.points.any { it.x > 15f && it.y > 5f })
        assertEquals(0f, p.points.first().x, 0.001f)
    }

    @Test
    fun `path morph resamples compatible shapes`() {
        val a = listOf(CanvasPoint(0f, 0f), CanvasPoint(10f, 0f), CanvasPoint(10f, 10f))
        val b = listOf(CanvasPoint(0f, 0f), CanvasPoint(20f, 0f), CanvasPoint(20f, 20f), CanvasPoint(0f, 20f))
        val mid = CanvasPath.morph(a, b, 0.5f)
        assertTrue(mid.size >= 2)
        assertTrue(mid.any { it.x > 8f })
    }

    @Test
    fun `animator interpolates keyframes easing and loop`() {
        val scene = CanvasScene(
            nodes = listOf(CanvasNode("bar", CanvasSpec.TYPE_PROGRESS, x = 0f, y = 0f, w = 100f, h = 10f, progress = 0f)),
            animations = listOf(
                CanvasAnimation(
                    target = "bar",
                    property = "progress",
                    durationMs = 1000,
                    from = 0f,
                    to = 1f,
                    easing = "linear"
                )
            )
        )
        assertEquals(0.5f, CanvasAnimator.evaluate(scene, 500).nodes[0].progress, 0.01f)
        assertEquals(1f, CanvasAnimator.evaluate(scene, 5000).nodes[0].progress, 0.01f)

        val keyed = scene.copy(
            animations = listOf(
                CanvasAnimation(
                    target = "bar",
                    property = "x",
                    durationMs = 1000,
                    easing = "linear",
                    keyframes = listOf(
                        CanvasKeyframe(0f, 0f, "linear"),
                        CanvasKeyframe(0.5f, 50f, "linear"),
                        CanvasKeyframe(1f, 100f, "linear")
                    )
                )
            )
        )
        assertEquals(50f, CanvasAnimator.evaluate(keyed, 500).nodes[0].x, 0.5f)
        assertEquals(75f, CanvasAnimator.evaluate(keyed, 750).nodes[0].x, 1.0f)

        val infinite = scene.copy(animations = listOf(scene.animations[0].copy(repeat = -1, alternate = true)))
        assertEquals(1f, CanvasAnimator.evaluate(infinite, 1000).nodes[0].progress, 0.02f)
        assertEquals(0f, CanvasAnimator.evaluate(infinite, 2000).nodes[0].progress, 0.02f)
    }

    @Test
    fun `camera and stagger animate multiple targets`() {
        val scene = CanvasScene(
            nodes = listOf(
                CanvasNode("a", CanvasSpec.TYPE_RECT, x = 0f, y = 0f, w = 10f, h = 10f),
                CanvasNode("b", CanvasSpec.TYPE_RECT, x = 0f, y = 10f, w = 10f, h = 10f)
            ),
            animations = listOf(
                CanvasAnimation(
                    targets = listOf("a", "b"),
                    property = "alpha",
                    from = 0f,
                    to = 1f,
                    durationMs = 100,
                    staggerMs = 100,
                    easing = "linear"
                ),
                CanvasAnimation(target = CanvasSpec.CAMERA_TARGET, property = "cameraZoom", from = 1f, to = 2f, durationMs = 100, easing = "linear")
            )
        )
        val f = CanvasAnimator.evaluate(scene, 100)
        assertEquals(1f, f.nodes[0].alpha, 0.01f)
        assertEquals(0f, f.nodes[1].alpha, 0.02f)
        assertEquals(2f, f.cameraZoom, 0.01f)
    }

    @Test
    fun `easing specs are stable`() {
        for (spec in listOf("linear", "ease", "ease-in", "ease-out", "ease-in-out", "spring", "cubic-bezier(0.2,0.8,0.2,1)", "steps(4)", "back", "elastic")) {
            assertEquals("$spec start", 0f, CanvasEasing.eval(0f, spec), 0.001f)
            assertEquals("$spec end", 1f, CanvasEasing.eval(1f, spec), 0.001f)
            val mid = CanvasEasing.eval(0.5f, spec)
            assertTrue("$spec mid=$mid", mid.isFinite())
        }
    }

    @Test
    fun `patch updates only the target node`() {
        val scene = CanvasScene(
            nodes = listOf(
                CanvasNode("bar", CanvasSpec.TYPE_PROGRESS, progress = 0.1f),
                CanvasNode("other", CanvasSpec.TYPE_TEXT, text = "keep")
            )
        )
        val patch = CanvasPatch.parse("""{"op":"canvas","key":"main","patch":{"node":"bar","progress":0.7,"fill":"#FF0000"}}""")
        assertEquals("main", patch.key)
        val r = patch.applyTo(scene)
        assertTrue(r.nodeFound)
        assertEquals(0.7f, r.scene.findNode("bar")!!.progress, 0.001f)
        assertEquals("keep", r.scene.findNode("other")!!.text)
        assertTrue(r.changes.any { it.property == "progress" })
    }

    @Test
    fun `patch scene replacement parses`() {
        val p = CanvasPatch.parse("""{"op":"canvas","patch":{"key":"k","scene":{"width":200,"height":100,"nodes":[{"id":"a","type":"rect"}]}}}""")
        assertNotNull(p.scene)
        assertEquals(200f, p.scene!!.width, 0.1f)
        assertEquals("k", p.key)
    }

    @Test
    fun `non canvas op is rejected by parse`() {
        // CanvasPatch.parse 本身不校验 op，但调用方只在 op=canvas 时进入 patch 通道；
        // 这里锁住画布节点不会被普通请求误识别。
        val req = ScriptDialogSpec.parseRequest("""{"id":"x","ui":{"rows":[{"kind":"canvas","scene":{"nodes":[]}}]}}""")
        assertTrue(req.ui.rows[0] is ScriptDialogSpec.Row.Canvas)
        assertFalse(req.ui.rows[0] is ScriptDialogSpec.Row.Text)
    }

    @Test
    fun `color interpolation keeps alpha`() {
        val c = CanvasSpec.lerpColor(0x00FF0000, 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals(0x80, (c ushr 24) and 0xFF)
        assertEquals(0xFF, (c ushr 16) and 0xFF)
    }

    @Test
    fun `unknown node type falls back to rect`() {
        val scene = CanvasSceneCodec.parseScene("""{"nodes":[{"id":"x","type":"not-real"}]}""")
        assertEquals(CanvasSpec.TYPE_RECT, scene.nodes[0].type)
    }

    @Test
    fun `duplicate ids are made unique`() {
        val scene = CanvasSceneCodec.parseScene("""{"nodes":[{"id":"same"},{"id":"same"}]}""")
        assertEquals(2, scene.nodes.size)
        assertFalse(scene.nodes[0].id == scene.nodes[1].id)
    }

    @Test
    fun `animator interpolates color properties`() {
        val scene = CanvasScene(
            nodes = listOf(CanvasNode("box", CanvasSpec.TYPE_RECT, w = 100f, h = 100f, fill = "#FFFF0000")),
            animations = listOf(
                CanvasAnimation(
                    target = "box",
                    property = "fill",
                    fromColor = "#FFFF0000",
                    toColor = "#FF0000FF",
                    durationMs = 1000,
                    easing = "linear"
                )
            )
        )
        val mid = CanvasSpec.color(CanvasAnimator.evaluate(scene, 500).nodes[0].fill, 0)
        assertEquals(0xFF, (mid ushr 24) and 0xFF)
        assertEquals(0x80, (mid ushr 16) and 0xFF)
        assertEquals(0x80, mid and 0xFF)
    }

    @Test
    fun `animator morphs path points`() {
        val scene = CanvasScene(
            nodes = listOf(
                CanvasNode(
                    "p", CanvasSpec.TYPE_PATH, w = 40f, h = 40f,
                    points = listOf(CanvasPoint(0f, 0f), CanvasPoint(10f, 0f), CanvasPoint(10f, 10f))
                )
            ),
            animations = listOf(
                CanvasAnimation(
                    target = "p",
                    property = "path",
                    fromPath = "M0 0 L10 0 L10 10",
                    toPath = "M0 0 L40 0 L40 40",
                    durationMs = 1000,
                    easing = "linear"
                )
            )
        )
        val pts = CanvasAnimator.evaluate(scene, 500).nodes[0].points
        assertTrue(pts.isNotEmpty())
        assertTrue("mid path should expand past the original width", pts.any { it.x > 20f && it.x < MID_PATH_MAX_X })
    }

    @Test
    fun `animator preserves finite repeats and alternate`() {
        val scene = CanvasScene(
            nodes = listOf(CanvasNode("n", CanvasSpec.TYPE_RECT, x = 0f)),
            animations = listOf(
                CanvasAnimation(
                    target = "n",
                    property = "x",
                    from = 0f,
                    to = 10f,
                    durationMs = 1000,
                    repeat = 2,
                    alternate = true,
                    easing = "linear"
                )
            )
        )
        assertEquals(5f, CanvasAnimator.evaluate(scene, 500).nodes[0].x, 0.01f)
        assertEquals(5f, CanvasAnimator.evaluate(scene, SECOND_CYCLE_MS).nodes[0].x, 0.01f)
        // 两个循环且往复：第二圈倒放，结束时回到起点。
        assertEquals(0f, CanvasAnimator.evaluate(scene, 5000).nodes[0].x, 0.01f)
    }

    @Test
    fun `patch clamps progress and ignores unknown node`() {
        val scene = CanvasScene(nodes = listOf(CanvasNode("bar", CanvasSpec.TYPE_PROGRESS, progress = 0.1f)))
        val high = CanvasPatch.parse("""{"node":"bar","progress":4}""").applyTo(scene)
        assertEquals(1f, high.scene.findNode("bar")!!.progress, 0.001f)
        val missing = CanvasPatch.parse("""{"node":"nope","progress":0.5}""").applyTo(scene)
        assertFalse(missing.nodeFound)
        assertEquals(0.1f, missing.scene.findNode("bar")!!.progress, 0.001f)
    }

    @Test
    fun `null id is generated`() {
        val scene = CanvasSceneCodec.parseScene("""{"nodes":[{"type":"text","text":"a"}]}""")
        assertTrue(scene.nodes[0].id.isNotEmpty())
        assertNull(scene.nodes[0].baseNumber("nope"))
    }

    @Test
    fun `svg path is normalized to node local points`() {
        val scene = CanvasSceneCodec.parseScene(
            """{"nodes":[{"id":"p","type":"path","d":"M10 20 L110 20 L110 70 Z"}]}"""
        )
        val n = scene.nodes[0]
        assertEquals(10f, n.x, 0.01f)
        assertEquals(20f, n.y, 0.01f)
        assertEquals(100f, n.w, 0.01f)
        assertEquals(50f, n.h, 0.01f)
        assertEquals(0f, n.points.minOf { it.x }, 0.01f)
        assertEquals(0f, n.points.minOf { it.y }, 0.01f)
        assertTrue(n.points.any { abs(it.x - 100f) < 0.01f && abs(it.y - 50f) < 0.01f })
    }

    @Test
    fun `explicit position keeps points local`() {
        val scene = CanvasSceneCodec.parseScene(
            """{"nodes":[{"id":"l","type":"line","x":50,"y":60,"w":20,"h":10,
               "points":[{"x":0,"y":0},{"x":20,"y":10}]}]}"""
        )
        val n = scene.nodes[0]
        assertEquals(50f, n.x, 0.01f)
        assertEquals(60f, n.y, 0.01f)
        assertEquals(listOf(CanvasPoint(0f, 0f), CanvasPoint(20f, 10f)), n.points)
    }

    @Test
    fun `scaling selection scales local points only`() {
        val n = CanvasNode(
            "p", CanvasSpec.TYPE_PATH, x = 10f, y = 20f, w = 100f, h = 50f,
            points = listOf(CanvasPoint(0f, 0f), CanvasPoint(100f, 50f))
        )
        val scaled = n.scaledLocalPoints(200f, 100f)
        assertEquals(0f, scaled[0].x, 0.01f)
        assertEquals(0f, scaled[0].y, 0.01f)
        assertEquals(200f, scaled[1].x, 0.01f)
        assertEquals(100f, scaled[1].y, 0.01f)
        // 原节点不变；缩放只产生新的点集，x/y 仍是包围盒原点。
        assertEquals(100f, n.points[1].x, 0.01f)
        assertEquals(10f, n.x, 0.01f)
    }

    @Test
    fun `rotated node maps scene point back to local`() {
        val n = CanvasNode(
            "r", CanvasSpec.TYPE_RECT, x = 0f, y = 0f, w = 100f, h = 100f, rotation = 90f
        )
        val center = n.sceneToLocal(50f, 50f)
        assertEquals(50f, center.first, 0.01f)
        assertEquals(50f, center.second, 0.01f)
        val rightMid = n.sceneToLocal(100f, 50f)
        // 正向 Canvas 旋转 90° 是顺时针；反向映射时场景右侧中点回到节点局部上侧中点。
        assertEquals(50f, rightMid.first, 0.01f)
        assertEquals(0f, rightMid.second, 0.01f)
    }

    @Test
    fun `nested group transform round trips with offsets and rotation`() {
        val outer = CanvasNode(
            "outer", CanvasSpec.TYPE_GROUP, x = 100f, y = 50f, w = 200f, h = 120f,
            rotation = 30f, scaleX = 1.4f, scaleY = 0.8f
        )
        val inner = CanvasNode(
            "inner", CanvasSpec.TYPE_GROUP, x = 20f, y = 10f, w = 120f, h = 80f,
            rotation = -15f, scaleX = 1.1f, scaleY = 0.9f
        )
        val child = CanvasNode(
            "child", CanvasSpec.TYPE_RECT, x = 10f, y = 5f, w = 30f, h = 20f,
            rotation = 20f, scaleX = 1.2f, scaleY = 0.7f
        )
        val ancestors = listOf(outer, inner)
        val world = CanvasTransform.localToScene(child, 0f, 0f, ancestors)
        val back = CanvasTransform.sceneToLocal(child, world.first, world.second, ancestors)
        assertEquals(0f, back.first, 0.01f)
        assertEquals(0f, back.second, 0.01f)
    }

    @Suppress("MagicNumber")
    @Test
    fun `group offset is not applied twice to child hit tests`() {
        val group = CanvasNode("g", CanvasSpec.TYPE_GROUP, x = 100f, y = 50f, w = 100f, h = 80f)
        val child = CanvasNode("c", CanvasSpec.TYPE_RECT, x = 10f, y = 5f, w = 30f, h = 20f)
        val world = CanvasTransform.localToScene(child, 0f, 0f, listOf(group))
        assertEquals(110f, world.first, 0.01f)
        assertEquals(55f, world.second, 0.01f)
        val local = CanvasTransform.sceneToLocal(child, world.first, world.second, listOf(group))
        assertEquals(0f, local.first, 0.01f)
        assertEquals(0f, local.second, 0.01f)
    }

    @Suppress("MagicNumber")
    @Test
    fun `node limit is global across nested groups`() {
        val children = (0 until 150).joinToString(",") { """{"type":"rect"}""" }
        val group = """{"type":"group","children":[$children]}"""
        val scene = CanvasSceneCodec.parseScene("""{"nodes":[$group,$group]}""")
        assertEquals(CanvasSpec.MAX_NODES, scene.flatten().size)
    }

    @Suppress("MagicNumber")
    @Test
    fun `string points are clamped to finite scene bounds`() {
        val scene = CanvasSceneCodec.parseScene(
            """{"nodes":[{"type":"line","points":["NaN,Infinity","1e30,-1e30"]}]}"""
        )
        val points = scene.nodes[0].points
        assertTrue(points.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue(points.all { abs(it.x) <= 100000f && abs(it.y) <= 100000f })
    }

    @Test
    fun `removing a group also drops animations of its descendants`() {
        val child = CanvasNode("child", CanvasSpec.TYPE_RECT)
        val group = CanvasNode("group", CanvasSpec.TYPE_GROUP, children = listOf(child))
        val scene = CanvasScene(
            nodes = listOf(group),
            animations = listOf(
                CanvasAnimation(target = "child", property = "x", from = 0f, to = 10f),
                CanvasAnimation(target = CanvasSpec.CAMERA_TARGET, property = "cameraZoom", from = 1f, to = 2f)
            )
        )
        val out = scene.removeNode("group")
        assertTrue(out.nodes.isEmpty())
        assertEquals(1, out.animations.size)
        assertEquals(CanvasSpec.CAMERA_TARGET, out.animations[0].target)
    }

    @Test
    fun `scaled rotated node local round trips`() {
        val n = CanvasNode(
            "s", CanvasSpec.TYPE_RECT, x = 10f, y = 20f, w = 100f, h = 60f,
            rotation = 30f, scaleX = SCALED_NODE_SCALE_X, scaleY = SCALED_NODE_SCALE_Y
        )
        for ((lx, ly) in listOf(
            0f to 0f,
            LOCAL_FAR_X to LOCAL_FAR_Y,
            LOCAL_MID_X to LOCAL_MID_Y
        )) {
            val scene = n.localToScene(lx, ly)
            val back = n.sceneToLocal(scene.first, scene.second)
            assertEquals("x round trip", lx, back.first, 0.01f)
            assertEquals("y round trip", ly, back.second, 0.01f)
        }
    }
}
