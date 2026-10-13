package com.workspace.proot

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * 5.9.40 新增：MD3 UI 规范的执行层。
 *
 * ## 为什么要这一层
 *
 * 5.9.40 之前的实测结论：
 * - `setPadding(...` **236 处**，全是裸数字；
 * - **单位是混的** —— `setPadding((16 * d)`（px）、`setPaddingMd(UiTokens.SPACE_4, UiTokens.SPACE_2, UiTokens.SPACE_4, UiTokens.SPACE_2)`（px，忘换算，13 处）、
 *   `setPadding(dp(16)`（dp）三种写法同时存在。`16px` 在 3 倍屏上只有约 5dp，
 *   所以同一份代码里"16"有两三种含义，**这就是不协调的根源，不是巧合**；
 * - 圆角同时存在 2 / 8 / 12 / 20 四种；
 * - 字号同时存在 11/12/13/14/15/16/17/18/24/32 **十种**裸字面量，而 `UiTokens` 只定义了 4 档
 *   —— **定义了却没人守，等于没有规矩**；
 * - `setLineSpacing` 全项目出现 **0 次**：行高从来没有被管过。
 *
 * ## 本文件的单位约定（这一条是全部的前提）
 *
 * **本文件所有参数一律是 dp**，内部自己换算成 px。
 * 调用方不许再乘 `density` —— 那正是单位混乱的来源。
 * 所以迁移时看到 `(16 * d)` 就替换成 `16`（或 `UiTokens.SPACE_4`），**不要保留 `* d`**。
 *
 * ## 收敛规则（裸字面量 → 规范档位）
 *
 * - 间距：非 4 倍数**上调**到下一个 4 倍数（6→8、10→12、14→16、18→24）。MD3 取向上调而非就近。
 * - 圆角：`2→4`、其余就近吸附；`20→16`（用户已确认不保留大圆角）。
 * - 字号：`11→12`、`16/17→15`、`24/32→28`，其余本就合规。
 */

/** dp → px。 */
fun Context.dp(value: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
).toInt()

fun Context.dpf(value: Float): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
)

/** 间距取整到 MD3 的 4dp 网格。 */
fun snapSpace(dp: Int): Int = when {
    dp <= 0 -> UiTokens.SPACE_0
    dp <= UiTokens.SPACE_1 -> UiTokens.SPACE_1
    dp <= UiTokens.SPACE_2 -> UiTokens.SPACE_2
    dp <= UiTokens.SPACE_3 -> UiTokens.SPACE_3
    dp <= UiTokens.SPACE_4 -> UiTokens.SPACE_4
    dp <= UiTokens.SPACE_5 -> UiTokens.SPACE_5
    else -> UiTokens.SPACE_6
}

/**
 * 圆角吸附到 MD3 shape 档位。
 * 20dp 并入 lg（用户确认不保留大圆角），4dp 以下并入 xs。
 */
fun snapCorner(dp: Float): Float = when {
    dp <= UiTokens.CORNER_XS -> UiTokens.CORNER_XS
    dp <= UiTokens.CORNER_SM -> UiTokens.CORNER_SM
    dp <= UiTokens.CORNER_MD -> UiTokens.CORNER_MD
    else -> UiTokens.CORNER_LG
}

/**
 * 合规 setPadding —— **参数单位是 dp**（不要再乘 density）。
 *
 * 四个参数各自落到 4dp 网格。`0` 原样保留：`0` 是"这一边不留白"的语义，
 * 拿它去上调会变成 4，把所有贴边元素推开 —— 那是实打实的 bug。
 */
fun View.setPaddingMd(left: Int, top: Int, right: Int, bottom: Int) {
    setPadding(
        context.dp(snapSpace(left)),
        context.dp(snapSpace(top)),
        context.dp(snapSpace(right)),
        context.dp(snapSpace(bottom)),
    )
}

/** 左右统一（dp）。 */
fun View.setPaddingMdH(dp: Int) = setPaddingMd(dp, 0, dp, 0)

/** 上下统一（dp）。 */
fun View.setPaddingMdV(dp: Int) = setPaddingMd(0, dp, 0, dp)

/** 四边统一（dp）。 */
fun View.setPaddingMdAll(dp: Int) = setPaddingMd(dp, dp, dp, dp)

/** 只改左边（dp）。 */
fun View.padLeftMd(dp: Int) = setPaddingMd(dp, paddingTop, paddingRight, paddingBottom)

/** 只改上边（dp）。 */
fun View.padTopMd(dp: Int) = setPaddingMd(paddingLeft, dp, paddingRight, paddingBottom)

/** 只改右边（dp）。 */
fun View.padRightMd(dp: Int) = setPaddingMd(paddingLeft, paddingTop, dp, paddingBottom)

/** 只改下边（dp）。 */
fun View.padBottomMd(dp: Int) = setPaddingMd(paddingLeft, paddingTop, paddingRight, dp)

/**
 * 规范圆角背景 —— 取代手写 `GradientDrawable().apply { cornerRadius = Nf * density }`。
 * 半径自动吸附，写多少都漏不出第 5 种圆角。
 */
fun View.roundedBg(
    fill: Int,
    cornerDp: Float = UiTokens.CORNER_MD,
    strokeDp: Float = 0f,
    strokeColor: Int = 0,
) {
    val gd = GradientDrawable()
    gd.setColor(fill)
    gd.cornerRadius = context.dpf(snapCorner(cornerDp))
    if (strokeDp > 0f && strokeColor != 0) gd.setStroke(context.dp(strokeDp.toInt()), strokeColor)
    background = gd
}

/** 规范最小高度（MD3 触控目标 48dp）。 */
fun View.minTouchTarget() {
    minimumHeight = context.dp(UiTokens.TOUCH_TARGET)
}

/** 字号吸附到 MD3 的 5 档。 */
fun snapText(sp: Float): Float = when {
    sp <= UiTokens.TEXT_META -> UiTokens.TEXT_META          // 11 → 12
    sp <= UiTokens.TEXT_COMPACT -> UiTokens.TEXT_COMPACT    // 13
    sp <= UiTokens.TEXT_BODY + 1.5f -> UiTokens.TEXT_BODY   // 16 / 17 → 15
    sp <= UiTokens.TEXT_TITLE + 1.5f -> UiTokens.TEXT_TITLE // 18 附近保持
    else -> UiTokens.TEXT_DISPLAY                            // 24 / 32 → 28
}

private fun lineFor(sp: Float): Float = when {
    sp >= UiTokens.TEXT_TITLE -> UiTokens.LINE_TITLE
    sp >= UiTokens.TEXT_BODY -> UiTokens.LINE_BODY
    sp >= UiTokens.TEXT_COMPACT -> UiTokens.LINE_COMPACT
    else -> UiTokens.LINE_META
}

/**
 * 规范字号 + 行高（参数是 sp）。
 *
 * 5.9.40 补的第二件事：**顺带把行高设上**（此前全项目 0 次）。
 * 光统一字号不设行高，紧凑处照样挤 —— 割裂感不会真的消失。
 */
fun TextView.textMd(sp: Float, lineHeight: Float? = null, bold: Boolean = false) {
    val snapped = snapText(sp)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, snapped)
    setLineSpacing(0f, lineHeight ?: lineFor(snapped))
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

/** 已配好行高的常用组合 —— 页面里最常见，直接调用省事。 */
fun TextView.textTitle(bold: Boolean = true) = textMd(UiTokens.TEXT_TITLE, UiTokens.LINE_TITLE, bold)
fun TextView.textBody() = textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
fun TextView.textCompact() = textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
fun TextView.textMeta() = textMd(UiTokens.TEXT_META, UiTokens.LINE_META)
fun TextView.textDisplay(bold: Boolean = false) = textMd(UiTokens.TEXT_DISPLAY, UiTokens.LINE_TITLE, bold)