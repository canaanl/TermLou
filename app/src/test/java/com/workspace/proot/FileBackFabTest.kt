@file:Suppress("MagicNumber")

package com.workspace.proot

import android.app.Activity
import android.view.Gravity
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileBackFabTest {

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun theme(): ThemeColors = ThemeColors.default(true)

    @Test
    fun `at the root the arrow is computed neutral and click only reports`() {
        val activity = activity()
        val theme = theme()
        var navigateUp = 0
        var blocked = 0
        val fab = FileBackFab(activity, theme, { navigateUp++ }, { blocked++ })

        fab.canGoUp = false

        assertEquals(NeuFab.disabledIconColor(theme), fab.currentTextColor)
        assertTrue(ColorMath.contrast(fab.currentTextColor, theme.surface) >= 1.95)
        assertEquals(activity.getString(R.string.file_no_parent), fab.contentDescription)

        fab.performClick()
        assertEquals("must not navigate at the root", 0, navigateUp)
        assertEquals("must report the blocked tap", 1, blocked)
    }

    @Test
    fun `in a subdirectory the arrow is brand colored and navigates`() {
        val activity = activity()
        val theme = theme()
        var navigateUp = 0
        var blocked = 0
        val fab = FileBackFab(activity, theme, { navigateUp++ }, { blocked++ })

        fab.canGoUp = true

        assertEquals(theme.primaryVisible, fab.currentTextColor)
        assertEquals(activity.getString(R.string.file_back), fab.contentDescription)

        fab.performClick()
        assertEquals(1, navigateUp)
        assertEquals(0, blocked)
    }

    @Test
    fun `geometry matches the notes fab`() {
        val activity = activity()
        val fab = FileBackFab(activity, theme(), {}, {})
        val d = activity.resources.displayMetrics.density
        val lp = fab.layoutParams as FrameLayout.LayoutParams

        assertEquals((56 * d).toInt(), lp.width)
        assertEquals((56 * d).toInt(), lp.height)
        assertEquals(Gravity.BOTTOM or Gravity.END, lp.gravity)
        assertEquals((16 * d).toInt(), lp.marginEnd)
        assertEquals((16 * d).toInt(), lp.bottomMargin)
        assertEquals(6 * d, fab.elevation, 0.01f)
    }
}
