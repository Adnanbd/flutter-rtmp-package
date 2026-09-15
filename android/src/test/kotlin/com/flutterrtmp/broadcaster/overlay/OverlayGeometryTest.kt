package com.flutterrtmp.broadcaster.overlay

import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.FilterTransform
import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.FrameSize
import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.Length
import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.Placement
import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.PostRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class OverlayGeometryTest {

    private val portrait720 = FrameSize(720, 1280)
    private val landscape720 = FrameSize(1280, 720)
    private val frames = listOf(portrait720, landscape720, FrameSize(1080, 1920), FrameSize(1920, 1080))

    private fun assertClose(expected: Float, actual: Float, eps: Float = 0.01f, msg: String = "") =
        assertTrue(abs(expected - actual) <= eps, "$msg expected=$expected actual=$actual")

    private fun assertSame(expected: FilterTransform, actual: FilterTransform, msg: String) {
        // Exact equality: refactor must not change float results.
        assertEquals(expected, actual, msg)
    }

    // ---------------------------------------------------------------------------------------
    // Oracle: verbatim math from OverlayFilterManager before the P2 refactor (commit 6df943e).
    // ---------------------------------------------------------------------------------------
    private object Legacy {
        fun scoreband(
            widthParam: Float, xParam: Float, yParam: Float,
            bmpW: Int, bmpH: Int, streamWidth: Int, streamHeight: Int, isPortrait: Boolean
        ): FilterTransform {
            val widthPct = widthParam.coerceIn(1f, 100f)
            val xPct = xParam.coerceIn(0f, 100f)
            val yPct = yParam.coerceIn(0f, 100f)
            val bitmapAspect = bmpW.toFloat() / bmpH.toFloat()
            val postScaleX = widthPct
            val postScaleY = (widthPct / 100f) * (streamWidth.toFloat() / streamHeight.toFloat()) / bitmapAspect * 100f
            val postPosX = (xPct / 100f) * (100f - postScaleX)
            val postPosY = (yPct / 100f) * (100f - postScaleY)
            return if (isPortrait) {
                FilterTransform(postScaleY, postScaleX, 100f - postPosY - postScaleY, postPosX)
            } else {
                FilterTransform(postScaleX, postScaleY, postPosX, postPosY)
            }
        }

        fun sponsor(
            left: Int?, right: Int?, top: Int?, bottom: Int?, width: Int, height: Int,
            bmpW: Int, bmpH: Int, streamWidth: Int, streamHeight: Int, isPortrait: Boolean
        ): FilterTransform {
            val widthPct = width.coerceIn(1, 100).toFloat()
            val heightPct = height.coerceIn(1, 100).toFloat()
            val bitmapAspect = bmpW.toFloat() / bmpH.toFloat()
            val frameAspect = streamWidth.toFloat() / streamHeight.toFloat()
            val hForW = widthPct * frameAspect / bitmapAspect
            val finalW: Float
            val finalH: Float
            if (hForW <= heightPct) {
                finalW = widthPct
                finalH = hForW
            } else {
                finalH = heightPct
                finalW = heightPct * bitmapAspect / frameAspect
            }
            val maxPosX = (100f - finalW).coerceAtLeast(0f)
            val maxPosY = (100f - finalH).coerceAtLeast(0f)
            val postPosX = when {
                left != null && right == null -> left.toFloat().coerceIn(0f, maxPosX)
                right != null && left == null -> (100f - finalW - right.toFloat()).coerceIn(0f, maxPosX)
                else -> maxPosX / 2f
            }
            val postPosY = when {
                top != null && bottom == null -> top.toFloat().coerceIn(0f, maxPosY)
                bottom != null && top == null -> (100f - finalH - bottom.toFloat()).coerceIn(0f, maxPosY)
                else -> maxPosY / 2f
            }
            return if (isPortrait) {
                FilterTransform(finalH, finalW, 100f - postPosY - finalH, postPosX)
            } else {
                FilterTransform(finalW, finalH, postPosX, postPosY)
            }
        }
    }

    private val bitmaps = listOf(1408 to 186, 400 to 400, 200 to 800, 1920 to 1080, 64 to 32)

    @Test
    fun scoreband_matchesLegacyMath_acrossGrid() {
        val params = listOf(-5f, 0f, 1f, 33.3f, 50f, 90f, 100f, 140f)
        for (frame in frames) for (isPortrait in listOf(true, false)) for ((bw, bh) in bitmaps)
            for (w in params) for (x in params) for (y in params) {
                val expected = Legacy.scoreband(w, x, y, bw, bh, frame.width, frame.height, isPortrait)
                val rect = OverlayGeometry.scorebandRect(w, x, y, bw.toFloat() / bh, frame)
                val actual = OverlayGeometry.toFilter(rect, isPortrait)
                assertSame(expected, actual, "scoreband frame=$frame portrait=$isPortrait bmp=${bw}x$bh w=$w x=$x y=$y")
            }
    }

    @Test
    fun sponsor_matchesLegacyMath_acrossGrid() {
        val anchors = listOf(null, 0, 2, 50, 100, 120)
        val sizes = listOf(0, 1, 8, 22, 100, 150)
        for (frame in frames) for (isPortrait in listOf(true, false)) for ((bw, bh) in bitmaps)
            for (l in anchors) for (r in anchors) for (t in listOf(null, 2, 90)) for (b in listOf(null, 0, 10))
                for (w in sizes) for (h in listOf(8, 100)) {
                    val expected = Legacy.sponsor(l, r, t, b, w, h, bw, bh, frame.width, frame.height, isPortrait)
                    val rect = OverlayGeometry.sponsorRect(l, r, t, b, w, h, bw.toFloat() / bh, frame)
                    val actual = OverlayGeometry.toFilter(rect, isPortrait)
                    assertSame(expected, actual, "sponsor frame=$frame portrait=$isPortrait bmp=${bw}x$bh L=$l R=$r T=$t B=$b w=$w h=$h")
                }
    }

    @Test
    fun portraitTransform_workedExampleFromSpec() {
        // docs/specs/overlay-compositing.md: post scale (90, 6.69) pos (5, 89.31) → pre scale (6.69, 90) pos (4.0, 5)
        val t = OverlayGeometry.toFilter(PostRect(x = 5f, y = 89.31f, w = 90f, h = 6.69f), isPortrait = true)
        assertClose(6.69f, t.scaleX)
        assertClose(90f, t.scaleY)
        assertClose(4.0f, t.posX)
        assertClose(5f, t.posY)
    }

    @Test
    fun scoreband_defaultBottomCenter_1408x186_portrait720() {
        val r = OverlayGeometry.scorebandRect(90f, 50f, 100f, 1408f / 186f, portrait720)
        assertClose(90f, r.w)
        assertClose(6.69f, r.h)
        assertClose(5f, r.x)
        assertClose(100f - 6.69f, r.y)
    }

    @Test
    fun landscapeTransform_isIdentity() {
        val rect = PostRect(1f, 2f, 3f, 4f)
        assertEquals(FilterTransform(3f, 4f, 1f, 2f), OverlayGeometry.toFilter(rect, isPortrait = false))
    }

    @Test
    fun anchor_rules() {
        assertEquals(10f, OverlayGeometry.anchor(10f, null, 20f))
        assertEquals(70f, OverlayGeometry.anchor(null, 10f, 20f))
        assertEquals(40f, OverlayGeometry.anchor(10f, 10f, 20f))   // both → centered
        assertEquals(40f, OverlayGeometry.anchor(null, null, 20f)) // neither → centered
        assertEquals(80f, OverlayGeometry.anchor(95f, null, 20f))  // clamped to 100 − size
        assertEquals(0f, OverlayGeometry.anchor(null, null, 120f)) // oversize → 0
    }

    @Test
    fun length_pxConvertsAgainstAxis() {
        assertEquals(50f, Length.Px(640f).toPercent(1280))
        assertEquals(25f, Length.Percent(25f).toPercent(9999))
    }

    @Test
    fun placement_intrinsicPxSize_whenNoWidthOrHeight() {
        val res = OverlayGeometry.placementRect(
            Placement(left = Length.Px(0f), top = Length.Px(0f)), 360, 128, portrait720
        )
        assertFalse(res.downscaled)
        assertClose(50f, res.rect.w)   // 360 / 720
        assertClose(10f, res.rect.h)   // 128 / 1280
        assertEquals(0f, res.rect.x)
        assertEquals(0f, res.rect.y)
    }

    @Test
    fun placement_widthOnly_heightFromAspect() {
        // 400×100 content (aspect 4) at width 50% of 1280×720: w=640px → h=160px = 22.22%
        val res = OverlayGeometry.placementRect(Placement(width = Length.Percent(50f)), 400, 100, landscape720)
        assertClose(50f, res.rect.w)
        assertClose(22.22f, res.rect.h)
        assertClose(25f, res.rect.x)             // centered
        assertClose((100f - 22.22f) / 2f, res.rect.y)
    }

    @Test
    fun placement_heightOnly_widthFromAspect() {
        // 400×100 content, height 100px on 1280×720 → width 400px = 31.25%
        val res = OverlayGeometry.placementRect(Placement(height = Length.Px(100f)), 400, 100, landscape720)
        assertClose(31.25f, res.rect.w)
        assertClose(100f / 720f * 100f, res.rect.h)
    }

    @Test
    fun placement_widthAndHeight_contain() {
        // square content in 50%×50% box on 1280×720: height-limited → h=50% (360px), w=360px=28.125%
        val res = OverlayGeometry.placementRect(
            Placement(width = Length.Percent(50f), height = Length.Percent(50f)), 500, 500, landscape720
        )
        assertClose(28.125f, res.rect.w)
        assertClose(50f, res.rect.h)
    }

    @Test
    fun placement_rightBottomPxAnchors() {
        // 128×72 intrinsic on 1280×720 → 10%×10%; right 32px (2.5%), bottom 36px (5%)
        val res = OverlayGeometry.placementRect(
            Placement(right = Length.Px(32f), bottom = Length.Px(36f)), 128, 72, landscape720
        )
        assertClose(87.5f, res.rect.x)
        assertClose(85f, res.rect.y)
    }

    @Test
    fun placement_oversizeIntrinsic_isContainedAndFlagged() {
        val res = OverlayGeometry.placementRect(Placement(), 2560, 720, landscape720)
        assertTrue(res.downscaled)
        assertClose(100f, res.rect.w)
        assertClose(50f, res.rect.h)
        assertEquals(0f, res.rect.x)
        assertClose(25f, res.rect.y)
    }

    @Test
    fun placement_widthOnlyTooTall_isContainedAndFlagged() {
        // tall content 100×1000 at width 50% of landscape → h would be 889% → contained to 100% height
        val res = OverlayGeometry.placementRect(Placement(width = Length.Percent(50f)), 100, 1000, landscape720)
        assertTrue(res.downscaled)
        assertClose(100f, res.rect.h)
    }
}
