package com.flutterrtmp.broadcaster.overlay

import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.Length
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DynamicOverlayParserTest {

    private val bytes = byteArrayOf(1, 2, 3)

    private fun code(block: () -> Unit) = assertFailsWith<OverlayException> { block() }.code

    @Test
    fun parseAdd_fullPayload() {
        val cfg = DynamicOverlayParser.parseAdd(
            mapOf(
                "id" to "goal",
                "weight" to 70,
                "content" to mapOf("type" to "image", "bytes" to bytes),
                "placement" to mapOf(
                    "right" to mapOf("unit" to "px", "value" to 24.0),
                    "top" to mapOf("unit" to "percent", "value" to 5),
                    "width" to mapOf("unit" to "percent", "value" to 30.5)
                )
            )
        )
        assertEquals("goal", cfg.id)
        assertEquals(70, cfg.weight)
        assertTrue(cfg.content is OverlayContentConfig.Image)
        assertEquals(Length.Px(24f), cfg.placement.right)
        assertEquals(Length.Percent(5f), cfg.placement.top)
        assertEquals(Length.Percent(30.5f), cfg.placement.width)
        assertNull(cfg.placement.left)
        assertNull(cfg.placement.height)
    }

    @Test
    fun parseAdd_defaults() {
        val cfg = DynamicOverlayParser.parseAdd(
            mapOf("id" to "a", "content" to mapOf("type" to "image", "bytes" to bytes))
        )
        assertEquals(50, cfg.weight)
        assertEquals(OverlayGeometry.Placement(), cfg.placement)
    }

    @Test
    fun ids_reservedAndLength() {
        val content = mapOf("type" to "image", "bytes" to bytes)
        assertEquals("OVERLAY_ID_RESERVED", code { DynamicOverlayParser.parseAdd(mapOf("id" to "scoreband", "content" to content)) })
        assertEquals("OVERLAY_ID_RESERVED", code { DynamicOverlayParser.parseAdd(mapOf("id" to "sponsor_1", "content" to content)) })
        assertEquals("OVERLAY_ID_RESERVED", code { DynamicOverlayParser.parseAdd(mapOf("id" to "", "content" to content)) })
        assertEquals("OVERLAY_ID_RESERVED", code { DynamicOverlayParser.parseAdd(mapOf("id" to "x".repeat(65), "content" to content)) })
        assertEquals("OVERLAY_ID_RESERVED", code { DynamicOverlayParser.parseId(emptyMap<String, Any>()) })
        DynamicOverlayParser.parseAdd(mapOf("id" to "x".repeat(64), "content" to content))
        DynamicOverlayParser.parseAdd(mapOf("id" to "my_sponsor_1", "content" to content))
    }

    @Test
    fun content_validation() {
        assertEquals("OVERLAY_INVALID_CONTENT", code { DynamicOverlayParser.parseAdd(mapOf("id" to "a")) })
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            DynamicOverlayParser.parseAdd(mapOf("id" to "a", "content" to mapOf("type" to "image", "bytes" to ByteArray(0))))
        })
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            DynamicOverlayParser.parseAdd(mapOf("id" to "a", "content" to mapOf("type" to "video")))
        })
    }

    @Test
    fun placement_andWeight_validation() {
        fun add(placement: Map<String, Any?>, weight: Any? = null) = DynamicOverlayParser.parseAdd(
            mapOf("id" to "a", "content" to mapOf("type" to "image", "bytes" to bytes), "placement" to placement, "weight" to weight)
        )
        assertEquals("OVERLAY_INVALID_PLACEMENT", code { add(mapOf("left" to mapOf("unit" to "px", "value" to -1))) })
        assertEquals("OVERLAY_INVALID_PLACEMENT", code { add(mapOf("width" to mapOf("unit" to "percent", "value" to 101))) })
        assertEquals("OVERLAY_INVALID_PLACEMENT", code { add(mapOf("width" to mapOf("unit" to "em", "value" to 1))) })
        assertEquals("OVERLAY_INVALID_PLACEMENT", code { add(mapOf("width" to mapOf("unit" to "px"))) })
        assertEquals("OVERLAY_INVALID_PLACEMENT", code { add(emptyMap(), weight = 101) })
        assertEquals("OVERLAY_INVALID_PLACEMENT", code { add(emptyMap(), weight = "high") })
        add(mapOf("width" to mapOf("unit" to "px", "value" to 5000)), weight = 0)   // px has no upper bound
    }

    @Test
    fun parseUpdate_absentKeysAreNull() {
        val u = DynamicOverlayParser.parseUpdate(mapOf("id" to "a", "weight" to 5))
        assertEquals(5, u.weight)
        assertNull(u.content)
        assertNull(u.placement)
        assertEquals(DurationUpdate.Keep, u.duration)
        assertEquals(false, u.restartTimer)
    }

    @Test
    fun duration_add() {
        val content = mapOf("type" to "image", "bytes" to bytes)
        assertNull(DynamicOverlayParser.parseAdd(mapOf("id" to "a", "content" to content)).durationMs)
        assertNull(DynamicOverlayParser.parseAdd(mapOf("id" to "a", "content" to content, "durationMs" to null)).durationMs)
        assertEquals(5000L, DynamicOverlayParser.parseAdd(mapOf("id" to "a", "content" to content, "durationMs" to 5000)).durationMs)
        assertEquals(5000L, DynamicOverlayParser.parseAdd(mapOf("id" to "a", "content" to content, "durationMs" to 5000L)).durationMs)
        assertEquals("OVERLAY_INVALID_CONTENT", code { DynamicOverlayParser.parseAdd(mapOf("id" to "a", "content" to content, "durationMs" to 0)) })
        assertEquals("OVERLAY_INVALID_CONTENT", code { DynamicOverlayParser.parseAdd(mapOf("id" to "a", "content" to content, "durationMs" to -5)) })
        assertEquals("OVERLAY_INVALID_CONTENT", code { DynamicOverlayParser.parseAdd(mapOf("id" to "a", "content" to content, "durationMs" to "5s")) })
    }

    @Test
    fun duration_update() {
        val infinite = DynamicOverlayParser.parseUpdate(mapOf("id" to "a", "duration" to mapOf("ms" to null)))
        assertEquals(DurationUpdate.Set(null), infinite.duration)
        val set = DynamicOverlayParser.parseUpdate(mapOf("id" to "a", "duration" to mapOf("ms" to 3000), "restartTimer" to true))
        assertEquals(DurationUpdate.Set(3000L), set.duration)
        assertEquals(true, set.restartTimer)
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            DynamicOverlayParser.parseUpdate(mapOf("id" to "a", "duration" to mapOf("ms" to 0)))
        })
    }

    @Test
    fun textAndGif_content() {
        val text = DynamicOverlayParser.parseContent(
            mapOf(
                "type" to "text",
                "text" to "  GOAL!\n  Team   A ",
                "style" to mapOf(
                    "fontSizePx" to 48.0, "color" to 0xFFFF0000L, "background" to 0x80000000L,
                    "paddingPx" to 4, "fontTtf" to byteArrayOf(0, 1, 0, 0)
                )
            )
        ) as OverlayContentConfig.Text
        assertEquals("GOAL! Team A", text.text)
        assertEquals(48f, text.style.fontSizePx)
        assertEquals(0xFFFF0000L.toInt(), text.style.color)
        assertEquals(0x80000000L.toInt(), text.style.background)
        assertEquals(4f, text.style.paddingPx)
        assertEquals(4, text.style.fontTtf!!.size)

        val defaults = DynamicOverlayParser.parseContent(mapOf("type" to "text", "text" to "x")) as OverlayContentConfig.Text
        assertEquals(32f, defaults.style.fontSizePx)
        assertEquals(-1, defaults.style.color)
        assertNull(defaults.style.background)
        assertEquals(8f, defaults.style.paddingPx)

        assertTrue(DynamicOverlayParser.parseContent(mapOf("type" to "gif", "bytes" to bytes)) is OverlayContentConfig.Gif)
        assertEquals("OVERLAY_INVALID_CONTENT", code { DynamicOverlayParser.parseContent(mapOf("type" to "gif", "bytes" to ByteArray(0))) })
        assertEquals("OVERLAY_INVALID_CONTENT", code { DynamicOverlayParser.parseContent(mapOf("type" to "text", "text" to " \n ")) })
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            DynamicOverlayParser.parseContent(mapOf("type" to "text", "text" to "x", "style" to mapOf("fontSizePx" to 0)))
        })
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            DynamicOverlayParser.parseContent(mapOf("type" to "text", "text" to "x", "style" to mapOf("paddingPx" to -1)))
        })
        assertEquals("OVERLAY_FONT_INVALID", code {
            DynamicOverlayParser.parseContent(mapOf("type" to "text", "text" to "x", "style" to mapOf("fontTtf" to ByteArray(0))))
        })
    }

    @Test
    fun ticker_content() {
        val t = DynamicOverlayParser.parseContent(
            mapOf(
                "type" to "ticker", "text" to "line one\nline two", "cycleDurationMs" to 8000,
                "loop" to false, "loopGap" to mapOf("unit" to "px", "value" to 40), "direction" to "ltr"
            )
        ) as OverlayContentConfig.Ticker
        assertEquals("line one line two", t.text)
        assertEquals(8000L, t.cycleDurationMs)
        assertNull(t.speedPxPerSec)
        assertEquals(false, t.loop)
        assertEquals(Length.Px(40f), t.loopGap)
        assertEquals(TickerDirection.LTR, t.direction)

        val d = DynamicOverlayParser.parseContent(mapOf("type" to "ticker", "text" to "x")) as OverlayContentConfig.Ticker
        assertEquals(true, d.loop)
        assertEquals(TickerDirection.AUTO, d.direction)
        assertNull(d.loopGap)

        fun bad(extra: Map<String, Any?>) = code {
            DynamicOverlayParser.parseContent(mapOf("type" to "ticker", "text" to "x") + extra)
        }
        assertEquals("OVERLAY_INVALID_CONTENT", bad(mapOf("speedPxPerSec" to 10.0, "cycleDurationMs" to 1000)))
        assertEquals("OVERLAY_INVALID_CONTENT", bad(mapOf("speedPxPerSec" to 0.0)))
        assertEquals("OVERLAY_INVALID_CONTENT", bad(mapOf("cycleDurationMs" to 0)))
        assertEquals("OVERLAY_INVALID_CONTENT", bad(mapOf("direction" to "up")))
        assertEquals("OVERLAY_INVALID_PLACEMENT", bad(mapOf("loopGap" to mapOf("unit" to "percent", "value" to 150))))
    }

    @Test
    fun animations_andAnimateFlags() {
        val cfg = DynamicOverlayParser.parseAdd(
            mapOf(
                "id" to "a", "content" to mapOf("type" to "image", "bytes" to bytes),
                "enter" to mapOf("type" to "slide", "durationMs" to 250, "easing" to "easeInOut", "edge" to "left"),
                "exit" to mapOf("type" to "curtain", "durationMs" to 0, "easing" to "linear", "edge" to "bottom")
            )
        )
        assertEquals(AnimationSpec(AnimationType.SLIDE, 250, Easing.EASE_IN_OUT, Edge.LEFT), cfg.enter)
        assertEquals(AnimationType.CURTAIN, cfg.exit.type)
        assertEquals(false, cfg.exit.animates)                 // 0 ms = instant
        assertEquals(AnimationSpec.NONE, DynamicOverlayParser.parseAdd(mapOf("id" to "b", "content" to mapOf("type" to "image", "bytes" to bytes))).enter)

        fun bad(anim: Map<String, Any?>) = code { DynamicOverlayParser.parseAnimation(anim, "enter") }
        assertEquals("OVERLAY_INVALID_CONTENT", bad(mapOf("type" to "fade")))
        assertEquals("OVERLAY_INVALID_CONTENT", bad(mapOf("type" to "pop", "durationMs" to 5001)))
        assertEquals("OVERLAY_INVALID_CONTENT", bad(mapOf("type" to "pop", "durationMs" to -1)))
        assertEquals("OVERLAY_INVALID_CONTENT", bad(mapOf("type" to "pop", "easing" to "bounce")))
        assertEquals("OVERLAY_INVALID_CONTENT", bad(mapOf("type" to "slide", "edge" to "center")))

        assertEquals(true, DynamicOverlayParser.parseAnimate(emptyMap<String, Any>(), default = true))
        assertEquals(false, DynamicOverlayParser.parseAnimate(mapOf("animate" to false), default = true))
        assertEquals(false, DynamicOverlayParser.parseAnimate(emptyMap<String, Any>(), default = false))
    }

    @Test
    fun wrappedText_keepsBreaks_maxLinesAndAlignValidated() {
        val raw = "  Line  one\r\n\n  second\tline  \n"
        val single = DynamicOverlayParser.parseContent(mapOf("type" to "text", "text" to raw)) as OverlayContentConfig.Text
        assertEquals("Line one second line", single.text)
        assertEquals(1, single.style.maxLines)
        assertEquals(TextAlign.START, single.style.align)

        val wrapped = DynamicOverlayParser.parseContent(
            mapOf("type" to "text", "text" to raw, "style" to mapOf("maxLines" to 4, "align" to "center"))
        ) as OverlayContentConfig.Text
        assertEquals("Line one\n\nsecond line", wrapped.text)
        assertEquals(4, wrapped.style.maxLines)
        assertEquals(TextAlign.CENTER, wrapped.style.align)

        assertEquals("OVERLAY_INVALID_CONTENT", code {
            DynamicOverlayParser.parseContent(mapOf("type" to "text", "text" to "x", "style" to mapOf("maxLines" to 0)))
        })
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            DynamicOverlayParser.parseContent(mapOf("type" to "text", "text" to "x", "style" to mapOf("align" to "justify")))
        })
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            DynamicOverlayParser.parseContent(mapOf("type" to "text", "text" to " \n\n ", "style" to mapOf("maxLines" to 3)))
        })
    }

    private fun carouselItem(type: String = "image", intervalMs: Int? = null) = buildMap<String, Any> {
        put("content", mapOf("type" to type, "bytes" to bytes))
        if (intervalMs != null) put("intervalMs", intervalMs)
    }

    @Test
    fun parseContent_carousel_fullAndDefaults() {
        val full = DynamicOverlayParser.parseContent(
            mapOf(
                "type" to "carousel",
                "intervalMs" to 4000,
                "items" to listOf(carouselItem(), carouselItem("gif", 10_000)),
                "transition" to mapOf("type" to "push", "durationMs" to 600, "easing" to "linear", "edge" to "top")
            )
        ) as OverlayContentConfig.Carousel
        assertTrue(full.items[0].content is OverlayContentConfig.Image)
        assertTrue(full.items[1].content is OverlayContentConfig.Gif)
        assertEquals(listOf(4000L, 10_000L), full.effectiveIntervalsMs().toList())
        assertEquals(CarouselTransition(CarouselTransitionType.PUSH, 600, Easing.LINEAR, Edge.TOP), full.transition)

        val defaults = DynamicOverlayParser.parseContent(
            mapOf("type" to "carousel", "items" to listOf(carouselItem()))
        ) as OverlayContentConfig.Carousel
        assertEquals(5000L, defaults.intervalMs)
        assertEquals(CarouselTransition(), defaults.transition)
        assertEquals(CarouselTransitionType.CROSSFADE, defaults.transition.type)
    }

    @Test
    fun parseContent_carousel_validation() {
        fun carousel(vararg pairs: Pair<String, Any>) =
            DynamicOverlayParser.parseContent(mapOf("type" to "carousel", *pairs))

        assertEquals("OVERLAY_INVALID_CONTENT", code { carousel() })
        assertEquals("OVERLAY_INVALID_CONTENT", code { carousel("items" to emptyList<Any>()) })
        assertEquals("OVERLAY_INVALID_CONTENT", code { carousel("items" to List(21) { carouselItem() }) })
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            carousel("items" to listOf(mapOf("content" to mapOf("type" to "text", "text" to "x"))))
        })
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            carousel("items" to listOf(mapOf("content" to mapOf("type" to "image", "bytes" to ByteArray(0)))))
        })
        assertEquals("OVERLAY_INVALID_CONTENT", code { carousel("items" to listOf(carouselItem()), "intervalMs" to 499) })
        assertEquals("OVERLAY_INVALID_CONTENT", code { carousel("items" to listOf(carouselItem(intervalMs = 100))) })
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            carousel("items" to listOf(carouselItem()), "transition" to mapOf("type" to "fade"))
        })
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            carousel("items" to listOf(carouselItem()), "transition" to mapOf("durationMs" to 5001))
        })
        // Transition must be shorter than every effective interval…
        assertEquals("OVERLAY_INVALID_CONTENT", code {
            carousel(
                "items" to listOf(carouselItem(), carouselItem(intervalMs = 600)),
                "transition" to mapOf("durationMs" to 600)
            )
        })
        // …but a cut and a single item ignore the transition length.
        carousel("items" to listOf(carouselItem(), carouselItem(intervalMs = 600)), "transition" to mapOf("type" to "cut", "durationMs" to 600))
        carousel("items" to listOf(carouselItem(intervalMs = 600)), "transition" to mapOf("durationMs" to 600))
    }
}
