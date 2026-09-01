package com.workspace.proot

import android.content.Context
import androidx.recyclerview.widget.RecyclerView

class NonFlingRecyclerView(context: Context) : RecyclerView(context) {
    override fun fling(velocityX: Int, velocityY: Int): Boolean = false
}
