package com.flutterrtmp.broadcaster.camera

import com.flutterrtmp.broadcaster.overlay.OverlayScheduler
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class FakeTarget(override val source: ZoomSource = ZoomSource.CAMERA2) : ZoomTarget {
    var availability: ZoomTarget.Availability = ZoomTarget.Availability.Ready(1f, 8f)
    /** Applies are ignored (silent no-op) while false — like RootEncoder before the capture session exists. */
    var sessionOpen = true
    var zoom = 1f
    val applied = mutableListOf<Float>()

    override fun availability() = availability
    override fun apply(level: Float) {
        applied.add(level)
        if (sessionOpen) zoom = level
    }
    override fun current() = zoom
}

private class ManualScheduler : OverlayScheduler {
    var now = 0L
    private class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()
    val pending: Int get() = tasks.count { !it.cancelled }

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val t = Task(now + delayMs, task)
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

internal class ZoomControllerTest {
    private lateinit var target: FakeTarget
    private var hasTarget = true
    private lateinit var time: ManualScheduler
    private lateinit var events: MutableList<Map<String, Any?>>
    private lateinit var zoom: ZoomController

    private fun code(block: () -> Unit) = assertFailsWith<ZoomException> { block() }.code

    @BeforeTest
    fun setUp() {
        target = FakeTarget()
        hasTarget = true
        time = ManualScheduler()
        events = mutableListOf()
        zoom = ZoomController({ if (hasTarget) target else null }, { events.add(it) }, { time.now }, time)
    }

    @Suppress("UNCHECKED_CAST")
    private fun lastZoomEvent(): Pair<String, Map<String, Any?>> {
        val e = events.last { it["type"] == "zoomChanged" }
        return e["reason"] as String to e["zoom"] as Map<String, Any?>
    }

    @Test
    fun set_clampsAppliesAndReturnsState() {
        val info = zoom.set(20f)
        assertEquals(ZoomInfo(true, 1f, 8f, 8f, ZoomSource.CAMERA2), info)
        assertEquals(8f, zoom.requested)
        assertEquals(0.5f, ZoomController({ FakeTarget().apply { availability = ZoomTarget.Availability.Ready(0.5f, 10f) } },
            {}, { 0L }, time).set(0.2f).current)
        assertTrue(events.isEmpty())                            // setZoom never emits
    }

    @Test
    fun set_errors() {
        assertEquals("ZOOM_INVALID", code { zoom.set(0f) })
        assertEquals("ZOOM_INVALID", code { zoom.set(Float.NaN) })
        hasTarget = false
        assertEquals("ZOOM_NOT_READY", code { zoom.set(2f) })
        assertEquals("ZOOM_NOT_READY", code { zoom.info() })
        hasTarget = true
        target.availability = ZoomTarget.Availability.NotReady
        assertEquals("ZOOM_NOT_READY", code { zoom.set(2f) })
        target.availability = ZoomTarget.Availability.Unsupported
        assertEquals("ZOOM_UNSUPPORTED", code { zoom.set(2f) })
        assertEquals(ZoomInfo(false, 1f, 1f, 1f, ZoomSource.CAMERA2), zoom.set(1f))
        assertEquals(ZoomInfo(false, 1f, 1f, 1f, ZoomSource.CAMERA2), zoom.info())
    }

    @Test
    fun set_whileSessionOpening_keepsTryingInBackground() {
        target.sessionOpen = false
        assertEquals(1f, zoom.set(3f).current)
        time.advance(300)
        target.sessionOpen = true
        time.advance(100)
        assertEquals(3f, target.zoom)
        assertEquals("reapplied", lastZoomEvent().first)
        assertEquals(0, time.pending)
    }

    @Test
    fun reapply_waitsForReadySession_thenEmits() {
        zoom.set(4f)
        target.zoom = 1f                                        // camera re-opened: RootEncoder reset it
        target.availability = ZoomTarget.Availability.NotReady
        target.sessionOpen = false
        zoom.reapply("bindPreview")
        time.advance(250)
        assertEquals(1f, target.zoom)
        target.availability = ZoomTarget.Availability.Ready(1f, 8f)
        time.advance(100)                                       // range known, session still opening: apply is lost
        assertEquals(1f, target.zoom)
        target.sessionOpen = true
        time.advance(100)
        assertEquals(4f, target.zoom)
        val (reason, info) = lastZoomEvent()
        assertEquals("reapplied", reason)
        assertEquals(4.0, info["current"])
        assertEquals(0, time.pending)
    }

    @Test
    fun reapply_atDefaultZoom_doesNothing() {
        zoom.reapply("bindPreview")
        assertEquals(0, time.pending)
        assertTrue(target.applied.isEmpty())
    }

    @Test
    fun reapply_clampsToNewRange() {
        zoom.set(6f)
        target.availability = ZoomTarget.Availability.Ready(1f, 4f)
        zoom.reapply("bindPreview")
        assertEquals(4f, target.zoom)
        assertEquals(4f, zoom.requested)
        assertEquals("clamped", lastZoomEvent().first)
    }

    @Test
    fun reapply_givesUpWithWarningAfterTimeout() {
        zoom.set(2f)
        target.sessionOpen = false
        target.zoom = 1f
        zoom.reapply("bindPreview")
        time.advance(ZoomController.REAPPLY_TIMEOUT_MS + 200)
        val warn = events.single { it["type"] == "warning" }
        assertEquals("ZOOM_REAPPLY_FAILED", warn["code"])
        assertEquals(0, time.pending)
        assertEquals(2f, zoom.requested)                        // kept for the next re-open
    }

    @Test
    fun reapply_onUnsupportedSource_resetsAndEmits() {
        zoom.set(2f)
        target.availability = ZoomTarget.Availability.Unsupported
        zoom.reapply("bindPreview")
        assertEquals(1f, zoom.requested)
        assertEquals("reset", lastZoomEvent().first)
    }

    @Test
    fun cameraSwitch_resetsAndAnnouncesNewRange() {
        zoom.set(5f)
        target.availability = ZoomTarget.Availability.NotReady
        zoom.onCameraSwitched()
        assertEquals(1f, zoom.requested)
        assertTrue(events.none { it["type"] == "zoomChanged" })
        target.availability = ZoomTarget.Availability.Ready(1f, 2f)
        target.zoom = 1f
        time.advance(100)
        val (reason, info) = lastZoomEvent()
        assertEquals("cameraSwitched", reason)
        assertEquals(2.0, info["max"])
        assertEquals(1.0, info["current"])
    }

    @Test
    fun setZoom_supersedesPendingReapply_andReleaseCancels() {
        zoom.set(2f)
        target.sessionOpen = false
        target.zoom = 1f
        zoom.reapply("bindPreview")
        assertEquals(1, time.pending)
        target.sessionOpen = true
        zoom.set(3f)
        assertEquals(0, time.pending)
        target.sessionOpen = false
        target.zoom = 1f
        zoom.reapply("again")
        assertEquals(1, time.pending)
        zoom.release()
        assertEquals(0, time.pending)
    }

    @Test
    fun uvcMath_focalLengthRange() {
        assertEquals(1f to 4f, UvcZoomMath.ratioRange(100, 400))
        assertEquals(50, UvcZoomMath.percentOfRatio(2.5f, 100, 400))
        assertEquals(2.5f, UvcZoomMath.ratioOfPercent(50, 100, 400))
        assertEquals(400, UvcZoomMath.rawOfRatio(9f, 100, 400))
    }

    @Test
    fun uvcMath_zeroMinimumIsNominalOneToFour() {
        assertEquals(1f to 4f, UvcZoomMath.ratioRange(0, 10))
        assertEquals(5, UvcZoomMath.rawOfRatio(2.5f, 0, 10))
        assertEquals(50, UvcZoomMath.percentOfRatio(2.5f, 0, 10))
        assertEquals(1f, UvcZoomMath.ratioOfPercent(0, 0, 10))
    }
}
