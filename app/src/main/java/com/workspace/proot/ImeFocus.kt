package com.workspace.proot

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager

/**
 * 输入焦点与输入法的统一收口。
 *
 * ViewPager2 的五个页面全程常驻，旧页里的 EditText 不会因为页面滑走自动失焦；
 * 只调 hideSoftInput 只能暂时收键盘，焦点仍在隐藏页上，键盘可能再次跟出来。
 * 切页真正落定时必须先 clearFocus，再收输入法。
 */
internal object ImeFocus {

    /** 清掉当前输入焦点并收起输入法；没有焦点时也安全。 */
    fun clearAndHide(activity: Activity) {
        val focused = focusedView(activity)
        val token = focused?.windowToken ?: activity.window?.decorView?.windowToken
        focused?.clearFocus()
        hideToken(activity, token)
    }

    /**
     * `Activity.currentFocus` 在窗口还没拿到焦点时可能为 null（Robolectric 下必现），
     * 但视图树里的焦点所有者仍然存在；先走 decorView.findFocus()，回退 currentFocus。
     */
    private fun focusedView(activity: Activity): android.view.View? =
        activity.window?.decorView?.findFocus() ?: activity.currentFocus

    private fun hideToken(activity: Activity, token: android.os.IBinder?) {
        if (token == null) return
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }
}
