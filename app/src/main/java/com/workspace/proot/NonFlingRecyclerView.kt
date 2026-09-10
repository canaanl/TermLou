package com.workspace.proot

import android.content.Context
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

class NonFlingRecyclerView(context: Context) : RecyclerView(context) {
    override fun fling(velocityX: Int, velocityY: Int): Boolean = false

    /** 只读触摸观察：不拦截、不改分发，供灰色手势监测使用。 */
    var onDispatchTouch: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onDispatchTouch?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }
}

