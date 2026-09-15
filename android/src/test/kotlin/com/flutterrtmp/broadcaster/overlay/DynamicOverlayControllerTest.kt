package com.flutterrtmp.broadcaster.overlay

import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.Length
import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.Placement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Host that mirrors what OverlayFilterManager does, over a real LayerStack + fake sink. */
private class FakeHost : DynamicLayerHost<String> {
    val sinkOrder = mutableListOf<String>()
    private val stack = LayerStack(object : FilterSink<String> {
        override fun add(index: Int, filter: String) = sinkOrder.add(index, filter)
        override fun remove(filter: String) { sinkOrder.remove(filter) }
        override fun clear() = sinkOrder.clear()
    })
    val contents = mutableMapOf<String, String>()
    var downscaleIds = setOf<String>()
    override var frameSize = OverlayGeometry.FrameSize(1000, 500)
    /** Last frame handed over per id (add/update/attach/render). */
    val frames = mutableMapOf<String, LayerFrame>()
    val renders = mutableListOf<String>()

    override fun addDynamic(layer: DynamicLayer<String>, attached: Boolean, frame: LayerFrame): Boolean {
        frames[layer.id] = frame
        contents[layer.id] = layer.content.handle
        stack.put(layer.id, layer.weight, LayerStack.LayerClass.DYNAMIC, layer.seq, attached) { layer.id }
        return layer.id in downscaleIds
    }

    override fun updateDynamic(layer: DynamicLayer<String>, frame: LayerFrame): Boolean {
        frames[layer.id] = frame
        contents[layer.id] = layer.content.handle
        stack.setWeight(layer.id, layer.weight)
        stack.replaceFilter(layer.id)
        return layer.id in downscaleIds
    }

    override fun setDynamicAttached(id: String, attached: Boolean, frame: LayerFrame) {
        frames[id] = frame
        if (attached) stack.attach(id) else stack.detach(id)
    }

    override fun removeDynamic(id: String) {
        stack.remove(id)
        contents.remove(id)
        frames.remove(id)
    }

    var renderSucceeds = true

    override fun renderDynamic(layer: DynamicLayer<String>, frame: LayerFrame): Boolean {
        frames[layer.id] = frame
        renders.add(layer.id)
        return renderSucceeds
    }
}

/** Manual clock + scheduler: [advance] fires due tasks in time order, like a Handler would. */
private class FakeScheduler : OverlayScheduler, FrameRequester {
    var now = 0L
    var framesActive = false
    override fun setActive(active: Boolean) { framesActive = active }
    private class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()

    val pending: Int get() = tasks.count { !it.cancelled }

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val t = Task(now + delayMs.coerceAtLeast(0L), task)
        tasks.add(t)
        return { t.cancelled = true }
    }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            tasks.remove(next)
            now = next.at
            next.run()
        }
        tasks.removeAll { it.cancelled }
        now = end
    }
}

internal class DynamicOverlayControllerTest {

    private lateinit var host: FakeHost
    private lateinit var events: MutableList<Map<String, Any?>>
    private lateinit var ctrl: DynamicOverlayController<String>
    private lateinit var time: FakeScheduler

    private fun img(tag: String) = OverlayContentConfig.Image(tag.toByteArray())

    private fun cfg(
        id: String,
        weight: Int = 50,
        tag: String = id,
        durationMs: Long? = null,
        enter: AnimationSpec = AnimationSpec.NONE,
        exit: AnimationSpec = AnimationSpec.NONE,
        content: OverlayContentConfig = img(tag)
    ) = DynamicOverlayConfig(id, content, Placement(width = Length.Percent(10f)), weight, durationMs, enter, exit)

    private fun anim(type: AnimationType, ms: Int = 400) = AnimationSpec(type, ms, Easing.LINEAR)

    private fun ticker(text: String, speed: Float? = 100f, loop: Boolean = true, gap: Length? = null, fontSize: Float = 10f) =
        OverlayContentConfig.Ticker(text, TextStyleConfig(fontSizePx = fontSize), speed, null, loop, gap, TickerDirection.AUTO)

    /** Advance time in ~16 ms display frames, calling onFrame while frames are requested. */
    private fun run(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            time.advance(step)
            left -= step
            if (time.framesActive) ctrl.onFrame()
        }
    }

    @BeforeTest
    fun setUp() {
        host = FakeHost()
        events = mutableListOf()
        time = FakeScheduler()
        ctrl = DynamicOverlayController(
            decode = { c ->
                when (c) {
                    is OverlayContentConfig.Image ->
                        if (String(c.bytes) == "corrupt") null else DecodedContent(String(c.bytes), 100, 50)
                    is OverlayContentConfig.Gif -> DecodedContent("gif", 100, 50, animated = true)
                    is OverlayContentConfig.Text -> DecodedContent("text:${c.text}", 100, 50)
                    // Fake measurement: every character is fontSizePx wide.
                    is OverlayContentConfig.Ticker -> DecodedContent(
                        "ticker:${c.text}", 100, 50,
                        ticker = TickerMetrics(
                            c.text, c.text.length * c.style.fontSizePx, 50, c.speedPxPerSec, c.cycleDurationMs,
                            c.loop, c.loopGap, movesLeft = true
                        )
                    )
                    is OverlayContentConfig.Carousel -> DecodedContent(
                        "carousel:${c.items.size}", 100, 50,
                        animated = c.items.any { it.content is OverlayContentConfig.Gif },
                        carousel = CarouselTimeline(c.effectiveIntervalsMs(), c.transition.effectiveMs),
                        fillBox = true
                    )
                }
            },
            emit = { events.add(it) },
            clock = { time.now },
            scheduler = time,
            frames = time
        )
        ctrl.attachHost(host)
    }

    private fun types() = events.map { it["type"] }

    @Test
    fun noHost_throwsNotInitialized() {
        ctrl.attachHost(null)
        val e = assertFailsWith<OverlayException> { ctrl.add(cfg("a")) }
        assertEquals("OVERLAY_NOT_INITIALIZED", e.code)
    }

    @Test
    fun add_attachesAndEmitsShown_orderedByWeightThenInsertion() {
        ctrl.add(cfg("a", 50))
        ctrl.add(cfg("b", 10))
        ctrl.add(cfg("c", 50))
        assertEquals(listOf("b", "a", "c"), host.sinkOrder)
        assertEquals(listOf("overlayShown", "overlayShown", "overlayShown"), types())
        assertEquals("a", events[0]["id"])
    }

    @Test
    fun add_duplicateId_throws() {
        ctrl.add(cfg("a"))
        assertEquals("OVERLAY_ID_EXISTS", assertFailsWith<OverlayException> { ctrl.add(cfg("a")) }.code)
    }

    @Test
    fun add_overLimit_throws() {
        repeat(DynamicOverlayController.MAX_OVERLAYS) { ctrl.add(cfg("o$it")) }
        assertEquals("OVERLAY_LIMIT_REACHED", assertFailsWith<OverlayException> { ctrl.add(cfg("extra")) }.code)
    }

    @Test
    fun add_hiddenOverlaysCountTowardLimit() {
        repeat(DynamicOverlayController.MAX_OVERLAYS) { ctrl.add(cfg("o$it")); ctrl.hide("o$it") }
        assertFailsWith<OverlayException> { ctrl.add(cfg("extra")) }
    }

    @Test
    fun add_decodeFailure_throwsAndLeavesNoState() {
        val e = assertFailsWith<OverlayException> { ctrl.add(cfg("a", tag = "corrupt")) }
        assertEquals("OVERLAY_DECODE_FAILED", e.code)
        assertEquals(0, ctrl.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun add_downscaled_emitsWarningBeforeShown() {
        host.downscaleIds = setOf("big")
        ctrl.add(cfg("big"))
        assertEquals(listOf("warning", "overlayShown"), types())
        assertEquals("OVERLAY_DOWNSCALED", events[0]["code"])
        assertEquals("big", events[0]["id"])
    }

    @Test
    fun hideShow_toggleAttachment_andAreIdempotent() {
        ctrl.add(cfg("a", 10))
        ctrl.add(cfg("b", 20))
        ctrl.hide("a")
        ctrl.hide("a")
        assertEquals(listOf("b"), host.sinkOrder)
        assertEquals(DynamicOverlayController.State.HIDDEN, ctrl.stateOf("a"))
        ctrl.show("a")
        ctrl.show("a")
        assertEquals(listOf("a", "b"), host.sinkOrder)
        assertEquals(listOf("overlayShown", "overlayShown", "overlayHidden", "overlayShown"), types())
    }

    @Test
    fun update_weightReorders_contentSwaps_keepsVisibility() {
        ctrl.add(cfg("a", 10))
        ctrl.add(cfg("b", 20))
        ctrl.update(DynamicOverlayUpdate("a", img("a2"), null, 30))
        assertEquals(listOf("b", "a"), host.sinkOrder)
        assertEquals("a2", host.contents["a"])
        ctrl.hide("b")
        ctrl.update(DynamicOverlayUpdate("b", null, null, 0))
        assertEquals(listOf("a"), host.sinkOrder)          // still hidden
        ctrl.show("b")
        assertEquals(listOf("b", "a"), host.sinkOrder)
    }

    @Test
    fun update_emptyUpdate_isNoOp_andUnknownIdThrows() {
        ctrl.add(cfg("a"))
        ctrl.update(DynamicOverlayUpdate("a", null, null, null))
        assertEquals("OVERLAY_NOT_FOUND", assertFailsWith<OverlayException> {
            ctrl.update(DynamicOverlayUpdate("zzz", null, null, 1))
        }.code)
    }

    @Test
    fun update_decodeFailure_keepsOldContent() {
        ctrl.add(cfg("a"))
        assertFailsWith<OverlayException> { ctrl.update(DynamicOverlayUpdate("a", img("corrupt"), null, null)) }
        assertEquals("a", host.contents["a"])
    }

    @Test
    fun remove_andClear_emitReasons() {
        ctrl.add(cfg("a"))
        ctrl.add(cfg("b"))
        ctrl.add(cfg("c"))
        ctrl.remove("a")
        ctrl.hide("b")
        ctrl.clear()
        assertEquals(0, ctrl.size)
        assertTrue(host.sinkOrder.isEmpty())
        val removed = events.filter { it["type"] == "overlayRemoved" }
        assertEquals(listOf("a" to "removed", "b" to "cleared", "c" to "cleared"), removed.map { it["id"] to it["reason"] })
        assertEquals("OVERLAY_NOT_FOUND", assertFailsWith<OverlayException> { ctrl.remove("a") }.code)
    }

    @Test
    fun layers_seedNewHostWithVisibilityAndStableSeq() {
        ctrl.add(cfg("a", 50))
        ctrl.add(cfg("b", 50))
        ctrl.hide("a")
        val newHost = FakeHost()
        for ((layer, visible, frame) in ctrl.layers()) newHost.addDynamic(layer, visible, frame)
        ctrl.attachHost(newHost)
        assertEquals(listOf("b"), newHost.sinkOrder)
        ctrl.show("a")
        assertEquals(listOf("a", "b"), newHost.sinkOrder)   // seq preserved: a before b
        assertNull(ctrl.stateOf("missing"))
    }

    // ---- P5: live-only duration timer (spec §5) ----

    private fun expired() = events.filter { it["type"] == "overlayRemoved" && it["reason"] == "expired" }.map { it["id"] }

    @Test
    fun timer_frozenBeforeGoLive() {
        ctrl.add(cfg("a", durationMs = 5_000))
        time.advance(60_000)
        assertEquals(0L, ctrl.elapsedMsOf("a"))
        assertEquals(0, time.pending)
        ctrl.setLive(true)
        time.advance(4_999)
        assertTrue(expired().isEmpty())
        time.advance(1)
        assertEquals(listOf<Any?>("a"), expired())
        assertNull(ctrl.stateOf("a"))
        assertTrue(host.sinkOrder.isEmpty())
        assertEquals(0, time.pending)
    }

    @Test
    fun timer_pausesDuringReconnect_andSurvivesStopStart() {
        ctrl.setLive(true)
        ctrl.add(cfg("a", durationMs = 10_000))
        time.advance(3_000)
        ctrl.setLive(false)                 // disconnected / reconnecting
        time.advance(20_000)
        ctrl.setLive(true)                  // reconnected
        time.advance(3_000)
        ctrl.setLive(false)                 // stopStream
        time.advance(100_000)
        assertEquals(6_000L, ctrl.elapsedMsOf("a"))
        ctrl.setLive(true)                  // startStream again
        time.advance(3_999)
        assertTrue(expired().isEmpty())
        time.advance(1)
        assertEquals(listOf<Any?>("a"), expired())
    }

    @Test
    fun timer_pausesWhileHidden() {
        ctrl.setLive(true)
        ctrl.add(cfg("a", durationMs = 5_000))
        time.advance(2_000)
        ctrl.hide("a")
        time.advance(60_000)
        assertEquals(2_000L, ctrl.elapsedMsOf("a"))
        assertEquals(0, time.pending)
        ctrl.show("a")
        time.advance(3_000)
        assertEquals(listOf<Any?>("a"), expired())
    }

    @Test
    fun timer_restartTimer_resetsElapsed() {
        ctrl.setLive(true)
        ctrl.add(cfg("a", durationMs = 5_000))
        time.advance(4_000)
        ctrl.update(DynamicOverlayUpdate("a", null, null, null, restartTimer = true))
        time.advance(4_999)
        assertTrue(expired().isEmpty())
        time.advance(1)
        assertEquals(listOf<Any?>("a"), expired())
    }

    @Test
    fun timer_durationUpdate_keepsElapsed_andMayExpireImmediately() {
        ctrl.setLive(true)
        ctrl.add(cfg("a", durationMs = 10_000))
        ctrl.add(cfg("b", durationMs = 10_000))
        time.advance(4_000)
        ctrl.update(DynamicOverlayUpdate("a", null, null, null, DurationUpdate.Set(6_000)))
        time.advance(1_999)
        assertTrue(expired().isEmpty())
        time.advance(1)
        assertEquals(listOf<Any?>("a"), expired())

        ctrl.update(DynamicOverlayUpdate("b", null, null, null, DurationUpdate.Set(3_000)))   // elapsed 6s > 3s
        assertEquals(listOf<Any?>("a", "b"), expired())
    }

    @Test
    fun timer_infiniteUpdate_disablesExpiry_andSetDurationOnInfinite() {
        ctrl.setLive(true)
        ctrl.add(cfg("a", durationMs = 5_000))
        ctrl.add(cfg("b"))
        time.advance(1_000)
        ctrl.update(DynamicOverlayUpdate("a", null, null, null, DurationUpdate.Set(null)))
        time.advance(60_000)
        assertTrue(expired().isEmpty())
        assertEquals(0, time.pending)
        ctrl.update(DynamicOverlayUpdate("b", null, null, null, DurationUpdate.Set(70_000)))   // b already shown 61s
        time.advance(9_000)
        assertEquals(listOf<Any?>("b"), expired())
        assertEquals(DynamicOverlayController.State.VISIBLE, ctrl.stateOf("a"))
    }

    @Test
    fun timer_pausedOverlayPastDuration_expiresWhenItRunsAgain() {
        ctrl.setLive(true)
        ctrl.add(cfg("a", durationMs = 10_000))
        time.advance(5_000)
        ctrl.hide("a")
        ctrl.update(DynamicOverlayUpdate("a", null, null, null, DurationUpdate.Set(1_000)))
        assertTrue(expired().isEmpty())
        assertEquals(DynamicOverlayController.State.HIDDEN, ctrl.stateOf("a"))
        ctrl.show("a")
        assertEquals(listOf<Any?>("a"), expired())
    }

    @Test
    fun timer_removeAndClear_cancelPendingExpiry() {
        ctrl.setLive(true)
        ctrl.add(cfg("a", durationMs = 1_000))
        ctrl.add(cfg("b", durationMs = 2_000))
        ctrl.remove("a")
        assertEquals(1, time.pending)
        ctrl.clear()
        assertEquals(0, time.pending)
        time.advance(10_000)
        assertTrue(expired().isEmpty())
    }

    @Test
    fun timer_multipleOverlays_expireInOrder_otherOverlaysUntouched() {
        ctrl.setLive(true)
        ctrl.add(cfg("slow", 10, durationMs = 3_000))
        ctrl.add(cfg("fast", 20, durationMs = 1_000))
        ctrl.add(cfg("forever", 30))
        time.advance(1_000)
        assertEquals(listOf<Any?>("fast"), expired())
        assertEquals(listOf("slow", "forever"), host.sinkOrder)
        time.advance(2_000)
        assertEquals(listOf<Any?>("fast", "slow"), expired())
        assertEquals(listOf("forever"), host.sinkOrder)
        assertEquals(0, time.pending)
    }

    @Test
    fun timer_survivesHostSwap() {
        ctrl.setLive(true)
        ctrl.add(cfg("a", durationMs = 4_000))
        time.advance(1_000)
        // Pipeline rebuild: detach, seed a new host, attach.
        ctrl.attachHost(null)
        val newHost = FakeHost()
        for ((layer, visible, frame) in ctrl.layers()) newHost.addDynamic(layer, visible, frame)
        ctrl.attachHost(newHost)
        time.advance(3_000)
        assertEquals(listOf<Any?>("a"), expired())
        assertTrue(newHost.sinkOrder.isEmpty())
    }

    // ---- P6–P8: lifecycle with animations (spec §4) ----

    private fun removedReasons() = events.filter { it["type"] == "overlayRemoved" }.map { it["id"] to it["reason"] }

    @Test
    fun enter_emitsShownWhenDone_timerStartsOnlyWhenVisible() {
        ctrl.setLive(true)
        ctrl.add(cfg("a", durationMs = 1_000, enter = anim(AnimationType.SLIDE)))
        assertTrue(events.isEmpty())
        assertEquals(DynamicOverlayController.State.ENTERING, ctrl.stateOf("a"))
        assertTrue(time.framesActive)
        assertEquals(AnimationType.SLIDE, host.frames["a"]!!.animation)
        assertEquals(0f, host.frames["a"]!!.visible)
        assertEquals(listOf("a"), host.sinkOrder)            // attached from the first frame, off-screen

        run(200)
        assertEquals(0.5f, host.frames["a"]!!.visible, 0.05f)
        assertEquals(0L, ctrl.elapsedMsOf("a"))
        run(200)
        assertEquals(DynamicOverlayController.State.VISIBLE, ctrl.stateOf("a"))
        assertEquals(listOf("overlayShown"), types())
        assertEquals(1f, host.frames["a"]!!.visible)          // final frame rendered
        assertEquals(AnimationType.NONE, host.frames["a"]!!.animation)
        assertFalse(time.framesActive)
        run(999)
        assertTrue(expired().isEmpty())
        run(1)
        assertEquals(listOf<Any?>("a"), expired())
    }

    @Test
    fun hide_duringEnter_sameType_reversesFromCurrentProgress() {
        ctrl.add(cfg("a", enter = anim(AnimationType.POP), exit = anim(AnimationType.POP)))
        run(100)                                               // 25 % in
        ctrl.hide("a")
        assertEquals(DynamicOverlayController.State.EXITING, ctrl.stateOf("a"))
        run(99)
        assertEquals(DynamicOverlayController.State.EXITING, ctrl.stateOf("a"))
        run(2)
        assertEquals(DynamicOverlayController.State.HIDDEN, ctrl.stateOf("a"))
        assertEquals(listOf("overlayHidden"), types())         // never fully shown
        assertTrue(host.sinkOrder.isEmpty())
    }

    @Test
    fun hide_duringEnter_otherType_snapsVisibleThenRunsFullExit() {
        ctrl.add(cfg("a", enter = anim(AnimationType.SLIDE), exit = anim(AnimationType.POP)))
        run(100)
        ctrl.hide("a")
        assertEquals(listOf("overlayShown"), types())
        run(398)
        assertEquals(DynamicOverlayController.State.EXITING, ctrl.stateOf("a"))
        run(3)
        assertEquals(listOf("overlayShown", "overlayHidden"), types())
    }

    @Test
    fun show_duringHideExit_sameType_reverses() {
        ctrl.add(cfg("a", enter = anim(AnimationType.CURTAIN), exit = anim(AnimationType.CURTAIN)))
        run(400)
        ctrl.hide("a")
        run(100)                                               // 75 % visible
        ctrl.show("a")
        assertEquals(DynamicOverlayController.State.ENTERING, ctrl.stateOf("a"))
        run(101)
        assertEquals(DynamicOverlayController.State.VISIBLE, ctrl.stateOf("a"))
        assertEquals(listOf("overlayShown", "overlayShown"), types())
    }

    @Test
    fun show_duringHideExit_otherType_snapsHiddenThenEnters() {
        ctrl.add(cfg("a", enter = anim(AnimationType.POP), exit = anim(AnimationType.SLIDE)))
        run(400)
        ctrl.hide("a")
        run(100)
        ctrl.show("a")
        assertEquals(listOf("overlayShown", "overlayHidden"), types())
        assertEquals(DynamicOverlayController.State.ENTERING, ctrl.stateOf("a"))
        assertEquals(listOf("a"), host.sinkOrder)
        assertEquals(0f, host.frames["a"]!!.visible)
        run(400)
        assertEquals(listOf("overlayShown", "overlayHidden", "overlayShown"), types())
    }

    @Test
    fun remove_duringHideExit_finishesExit_thenRemoved_andIdIsGoneForApi() {
        ctrl.add(cfg("a", exit = anim(AnimationType.SLIDE)))
        ctrl.hide("a")
        run(100)
        ctrl.remove("a")
        assertEquals(DynamicOverlayController.State.EXITING, ctrl.stateOf("a"))
        assertEquals("OVERLAY_NOT_FOUND", assertFailsWith<OverlayException> { ctrl.hide("a") }.code)
        assertEquals("OVERLAY_NOT_FOUND", assertFailsWith<OverlayException> { ctrl.update(DynamicOverlayUpdate("a", null, null, 1)) }.code)
        run(301)
        assertNull(ctrl.stateOf("a"))
        assertEquals(listOf("a" to "removed"), removedReasons())
        assertFalse(types().contains("overlayHidden"))
    }

    @Test
    fun add_sameIdWhileAnimatingOut_finalizesOldFirst() {
        ctrl.add(cfg("a", exit = anim(AnimationType.POP)))
        ctrl.remove("a")
        ctrl.add(cfg("a", tag = "a2"))
        assertEquals(listOf("overlayShown", "overlayRemoved", "overlayShown"), types())
        assertEquals("a2", host.contents["a"])
        assertEquals(DynamicOverlayController.State.VISIBLE, ctrl.stateOf("a"))
    }

    @Test
    fun remove_withoutAnimation_isImmediate_clearAnimatedThenImmediate() {
        ctrl.add(cfg("a", exit = anim(AnimationType.POP)))
        ctrl.remove("a", animate = false)
        assertEquals(listOf("a" to "removed"), removedReasons())

        ctrl.add(cfg("b", exit = anim(AnimationType.POP)))
        ctrl.add(cfg("c", exit = anim(AnimationType.SLIDE)))
        ctrl.hide("c")
        ctrl.clear(animate = true)
        assertEquals(2, ctrl.size)
        assertEquals(DynamicOverlayController.State.EXITING, ctrl.stateOf("b"))
        ctrl.clear(animate = false)
        assertEquals(0, ctrl.size)
        assertEquals(listOf("a" to "removed", "b" to "cleared", "c" to "cleared"), removedReasons())
    }

    @Test
    fun remove_hiddenOverlay_isImmediate_evenWithExitAnimation() {
        ctrl.add(cfg("a", exit = anim(AnimationType.POP)))
        ctrl.hide("a")
        run(400)
        ctrl.remove("a")
        assertNull(ctrl.stateOf("a"))
    }

    @Test
    fun snapAnimations_jumpsEveryAnimationToItsEndState() {
        ctrl.add(cfg("in", enter = anim(AnimationType.POP)))
        ctrl.add(cfg("hide", exit = anim(AnimationType.POP)))
        ctrl.add(cfg("rm", exit = anim(AnimationType.POP)))
        ctrl.hide("hide")
        ctrl.remove("rm")
        events.clear()
        ctrl.snapAnimations()
        assertEquals(DynamicOverlayController.State.VISIBLE, ctrl.stateOf("in"))
        assertEquals(DynamicOverlayController.State.HIDDEN, ctrl.stateOf("hide"))
        assertNull(ctrl.stateOf("rm"))
        assertEquals(listOf("overlayShown" to "in", "overlayHidden" to "hide", "overlayRemoved" to "rm"),
            events.map { it["type"] to it["id"] })
    }

    @Test
    fun renderFailure_keepsRequestingFramesUntilDrawn() {
        host.renderSucceeds = false
        ctrl.add(cfg("a"))
        assertTrue(time.framesActive)
        ctrl.onFrame()
        assertTrue(time.framesActive)
        host.renderSucceeds = true
        ctrl.onFrame()
        assertFalse(time.framesActive)
    }

    @Test
    fun gif_requestsFramesWhileShown_notWhileHidden() {
        ctrl.add(cfg("g", content = OverlayContentConfig.Gif(byteArrayOf(1))))
        run(100)
        assertTrue(time.framesActive)
        assertTrue(host.frames["g"]!!.contentTimeMs >= 96)
        ctrl.hide("g")
        run(32)
        assertFalse(time.framesActive)
    }

    // ---- ticker (spec §6) ----

    @Test
    fun ticker_frozenBeforeLive_scrollsWhileLiveAndVisible() {
        ctrl.add(cfg("t", content = ticker("hello")))
        run(1_000)
        assertEquals(0f, ctrl.tickerDistanceOf("t"))
        assertFalse(time.framesActive)
        ctrl.setLive(true)
        assertTrue(time.framesActive)
        run(1_000)
        assertEquals(100f, ctrl.tickerDistanceOf("t")!!, 0.5f)
        ctrl.hide("t")
        run(1_000)
        assertEquals(100f, ctrl.tickerDistanceOf("t")!!, 0.5f)
        ctrl.show("t")
        ctrl.setLive(false)
        run(500)
        assertEquals(100f, ctrl.tickerDistanceOf("t")!!, 0.5f)
        ctrl.setLive(true)
        run(500)
        assertEquals(150f, ctrl.tickerDistanceOf("t")!!, 0.5f)
        assertEquals(150f, host.frames["t"]!!.tickerDistancePx, 16f)
    }

    @Test
    fun ticker_playOnce_completesAfterOnePass() {
        ctrl.setLive(true)
        // "abc" at 10 px/char = 30 px; band 10 % of 1000 = 100 px → pass 130 px at 130 px/s = 1 s.
        ctrl.add(cfg("t", content = ticker("abc", speed = 130f, loop = false)))
        run(990)
        assertTrue(removedReasons().isEmpty())
        run(20)
        assertEquals(listOf("t" to "completed"), removedReasons())
    }

    @Test
    fun ticker_loopExpiry_finishesCurrentPassBeforeRemoval() {
        ctrl.setLive(true)
        // text 100 px, band 100 px, gap 100 px → period 200, pass 200. speed 100 px/s.
        ctrl.add(cfg("t", durationMs = 5_000, content = ticker("0123456789", gap = Length.Px(100f))))
        run(5_000)                                             // d = 500 → newest copy 2
        assertEquals(DynamicOverlayController.State.VISIBLE, ctrl.stateOf("t"))
        assertEquals(2, host.frames["t"]!!.tickerLastCopy)
        run(990)                                               // copy 2 leaves at d = 200 + 400 = 600 → t = 6 s
        assertTrue(removedReasons().isEmpty())
        run(20)
        assertEquals(listOf("t" to "expired"), removedReasons())
    }

    @Test
    fun ticker_expiryCancelledByLongerDuration() {
        ctrl.setLive(true)
        ctrl.add(cfg("t", durationMs = 1_000, content = ticker("0123456789", gap = Length.Px(100f))))
        run(1_000)
        assertEquals(0, host.frames["t"]!!.tickerLastCopy)
        ctrl.update(DynamicOverlayUpdate("t", null, null, null, DurationUpdate.Set(null)))
        run(2_000)
        assertNull(host.frames["t"]!!.tickerLastCopy)
        assertTrue(removedReasons().isEmpty())
    }

    @Test
    fun ticker_textChangeResets_styleChangeRescales() {
        ctrl.setLive(true)
        ctrl.add(cfg("t", content = ticker("aaaa")))           // 40 px text, 100 px band → pass 140
        run(1_000)
        assertEquals(100f, ctrl.tickerDistanceOf("t")!!, 0.5f)
        ctrl.update(DynamicOverlayUpdate("t", ticker("aaaa", fontSize = 20f), null, null))   // 80 px → pass 180
        assertEquals(100f * 180f / 140f, ctrl.tickerDistanceOf("t")!!, 0.5f)
        ctrl.update(DynamicOverlayUpdate("t", ticker("bbbb", fontSize = 20f), null, null))
        assertEquals(0f, ctrl.tickerDistanceOf("t")!!, 0.01f)
    }

    // ---- carousel (spec §12) -------------------------------------------------------------------

    private fun carousel(
        count: Int = 3,
        intervalMs: Long = 1_000,
        transition: CarouselTransition = CarouselTransition(CarouselTransitionType.CROSSFADE, 200, Easing.LINEAR),
        gifAt: Int? = null
    ) = OverlayContentConfig.Carousel(
        List(count) { i -> CarouselItemConfig(if (i == gifAt) OverlayContentConfig.Gif(byteArrayOf(1)) else img("s$i"), null) },
        intervalMs,
        transition
    )

    @Test
    fun carousel_static_framesOnlyDuringTransitions() {
        ctrl.add(cfg("c", content = carousel()))
        run(16)
        assertFalse(time.framesActive)
        assertEquals(1, time.pending)                          // one wake at the first transition (800 ms)
        host.renders.clear()
        run(700)
        assertFalse(time.framesActive)
        assertTrue(host.renders.isEmpty())
        run(150)                                               // 850 ms: inside the 800–1000 transition
        assertTrue(time.framesActive)
        run(250)                                               // 1100 ms: done, final frame rendered, frames off
        assertFalse(time.framesActive)
        val last = host.frames["c"]!!
        assertTrue(last.contentTimeMs >= 1_000)
        assertTrue(host.renders.size in 10..20)
    }

    @Test
    fun carousel_rotatesWhileOffAirAndHidden_noFramesWhileHidden() {
        ctrl.add(cfg("c", content = carousel()))
        assertFalse(ctrl.isLive)
        ctrl.hide("c")
        host.renders.clear()
        run(5_000)
        assertTrue(host.renders.isEmpty())
        assertFalse(time.framesActive)
        ctrl.show("c")
        run(16)
        assertEquals(5_016L, host.frames["c"]!!.contentTimeMs)  // wall clock kept running
    }

    @Test
    fun carousel_contentUpdateRestartsAtFirstItem_placementUpdateKeepsPosition() {
        ctrl.add(cfg("c", content = carousel()))
        run(2_500)
        ctrl.update(DynamicOverlayUpdate("c", null, Placement(width = Length.Percent(20f)), null))
        run(16)
        assertTrue(host.frames["c"]!!.contentTimeMs >= 2_500)
        ctrl.update(DynamicOverlayUpdate("c", carousel(count = 2), null, null))
        assertEquals(0L, host.frames["c"]!!.contentTimeMs)
        assertEquals("carousel:2", host.contents["c"])
    }

    @Test
    fun carousel_cut_rendersAtBoundary() {
        ctrl.add(cfg("c", content = carousel(transition = CarouselTransition(CarouselTransitionType.CUT))))
        run(16)
        host.renders.clear()
        run(970)                                               // 986 ms
        assertTrue(host.renders.isEmpty())
        run(30)                                                // cut at 1000 ms
        assertEquals(listOf("c"), host.renders)
        assertFalse(time.framesActive)
    }

    @Test
    fun carousel_withGifItem_isFrameDriven() {
        ctrl.add(cfg("c", content = carousel(gifAt = 1)))
        run(100)
        assertTrue(time.framesActive)
    }

    @Test
    fun carousel_remove_cancelsWake() {
        ctrl.add(cfg("c", content = carousel()))
        assertEquals(1, time.pending)
        ctrl.remove("c", animate = false)
        assertEquals(0, time.pending)
    }
}
