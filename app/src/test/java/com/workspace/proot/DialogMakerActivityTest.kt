@file:Suppress("MagicNumber")

package com.workspace.proot

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 工坊外壳回归：必须是内容画布入口，不再是旧的线性控件列表。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DialogMakerActivityTest {

    @Test
    fun `workshop exposes content canvas editor`() {
        val activity = Robolectric.buildActivity(DialogMakerActivity::class.java).setup().get()
        val texts = mutableListOf<String>()
        collectTexts(activity.findViewById(android.R.id.content), texts)
        assertTrue(texts.contains(activity.getString(R.string.dm_section_canvas)))
        assertTrue(texts.contains(activity.getString(R.string.dm_edit_canvas)))
        assertTrue(texts.any { it.contains(activity.getString(R.string.dm_canvas_hint).take(4)) })
    }

    private fun collectTexts(view: View, out: MutableList<String>) {
        when (view) {
            is Button -> out.add(view.text.toString())
            is TextView -> out.add(view.text.toString())
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTexts(view.getChildAt(i), out)
        }
    }
}
