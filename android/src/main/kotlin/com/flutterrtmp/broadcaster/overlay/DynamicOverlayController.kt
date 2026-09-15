package com.flutterrtmp.broadcaster.overlay

/**
 * Measured, validated content ready to be turned into GL filters by a [DynamicLayerHost].
 * [animated] = redraws over time on its own (GIF). [ticker] is set for ticker content.
 * [wrap] is set for wrapped text: its size depends on the wrap width in encoder px (see OverlayGeometry.wrappedPlacementRect).
 * [carousel] is set for carousel content: it redraws only during transitions (unless [animated]) and at cuts.
 * [fillBox] = a placement with both width and height fills the box instead of containing the content aspect.
 */
data class DecodedContent<C>(
    val handle: C,
    val width: Int,
    val height: Int,
    val animated: Boolean = false,
    val ticker: TickerMetrics? = null,
    val wrap: ((widthPx: Int) -> Pair<Int, Int>)? = null,
    val carousel: CarouselTimeline? = null,
    val fillBox: Boolean = false
)

/** One dynamic overlay as the GL host needs it. `seq` is stable for the overlay's lifetime. */
data class DynamicLayer<C>(
    val id: String,
    val content: DecodedContent<C>,
    val placement: OverlayGeometry.Placement,
    val weight: Int,
    val seq: Long
)

/** What a layer looks like at one instant: animation state plus content clocks. */
data class LayerFrame(
    val animation: AnimationType,
    val edge: Edge,
    /** Eased visible fraction, 0 = out, 1 = final. */
    val visible: Float,
    /** Ms since the overlay was added (GIF clock; runs regardless of live state). */
    val contentTimeMs: Long,
    val tickerDistancePx: Float,
    /** Newest ticker copy allowed to enter; null = keep looping. */
    val tickerLastCopy: Int?
) {
    companion object {
        val STATIC = LayerFrame(AnimationType.NONE, Edge.BOTTOM, 1f, 0L, 0f, null)
    }
}

/** A dynamic layer as needed to seed a new host. */
data class SeedLayer<C>(val layer: DynamicLayer<C>, val attached: Boolean, val frame: LayerFrame)

/** Implemented by the per-pipeline overlay manager (OverlayFilterManager). */
interface DynamicLayerHost<C> {
    /** Encoder output size (post-rotation). */
    val frameSize: OverlayGeometry.FrameSize

    /** Add a layer; attached = visible. Returns true when the placement had to be downscaled. */
    fun addDynamic(layer: DynamicLayer<C>, attached: Boolean, frame: LayerFrame): Boolean

    /** Apply new content/placement/weight. Returns true when the placement had to be downscaled. */
    fun updateDynamic(layer: DynamicLayer<C>, frame: LayerFrame): Boolean

    fun setDynamicAttached(id: String, attached: Boolean, frame: LayerFrame)

    fun removeDynamic(id: String)

    /**
     * Called once per display frame for attached layers that animate or just changed state.
     * Returns false when the frame could not be drawn yet (GL thread behind); it is asked again next frame.
     */
    fun renderDynamic(layer: DynamicLayer<C>, frame: LayerFrame): Boolean
}

/** Runs [task] once after [delayMs] on the controller's thread. Returns a cancel function. */
fun interface OverlayScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/** Starts/stops per-display-frame [DynamicOverlayController.onFrame] calls on the controller's thread. */
fun interface FrameRequester {
    fun setActive(active: Boolean)
}

/**
 * Owns dynamic overlay state for the lifetime of a CameraStreamManager. It survives pipeline
 * re-creation: call [snapAnimations], seed the new host from [layers], then [attachHost].
 *
 * Lifecycle, interruptions, timer and ticker rules: docs/specs/dynamic-overlays.md §4–§7.
 * Events + warnings go out through [emit] as EventChannel payload maps.
 * Main thread only; [clock] is monotonic ms; [scheduler] and [frames] must call back on the same thread.
 */
class DynamicOverlayController<C>(
    private val decode: (OverlayContentConfig) -> DecodedContent<C>?,
    private val emit: (Map<String, Any?>) -> Unit,
    private val clock: () -> Long,
    private val scheduler: OverlayScheduler,
    private val frames: FrameRequester
) {
    companion object {
        const val MAX_OVERLAYS = 16
    }

    enum class State { ENTERING, VISIBLE, EXITING, HIDDEN }

    private class Entry<C>(
        var layer: DynamicLayer<C>,
        var state: State,
        val timer: OverlayTimer,
        val enter: AnimationSpec,
        val exit: AnimationSpec,
        /** Content clock origin: add time; reset when carousel content is replaced (spec §12). */
        var contentEpochMs: Long
    ) {
        /** Carousel was mid-transition on the last frame: render once more so the final state lands. */
        var carouselWasActive = false
        /** Linear visible fraction at [animStartMs]; meaningful while ENTERING/EXITING. */
        var progress = 1f
        var animStartMs = 0L
        /** Non-null while EXITING toward removal (the event reason). Such an entry is gone for the API. */
        var removeReason: String? = null
        var scroll: ScrollProgress? = null
        /** Ticker: newest copy allowed to enter (non-loop = 0, finishing after expiry = copy at expiry). */
        var tickerLastCopy: Int? = null
        /** Ticker duration ran out; the current pass is finishing. */
        var expirePending = false
        /** Render once on the next frame even if nothing animates (state just changed). */
        var dirty = false
    }

    private val entries = LinkedHashMap<String, Entry<C>>()
    private var nextSeq = 0L
    private var host: DynamicLayerHost<C>? = null
    private var cancelExpiryCheck: (() -> Unit)? = null
    private var cancelCarouselWake: (() -> Unit)? = null
    private var framesActive = false

    /** RTMP connected. Timers and tickers only run while live (spec §5–§6). */
    var isLive: Boolean = false
        private set

    val size: Int get() = entries.size
    val visibleCount: Int get() = entries.values.count { it.state != State.HIDDEN }

    fun attachHost(h: DynamicLayerHost<C>?) {
        host = h
        if (h != null) refresh()
        else updateFrameRequest()
    }

    /** Current overlays in insertion order — used to seed a new host. */
    fun layers(): List<SeedLayer<C>> {
        val now = clock()
        return entries.values.map { SeedLayer(it.layer, it.state != State.HIDDEN, frameOf(it, now)) }
    }

    fun stateOf(id: String): State? = entries[id]?.state

    /** Live elapsed ms of an overlay, or null if unknown. For diagnostics and tests. */
    fun elapsedMsOf(id: String): Long? = entries[id]?.timer?.elapsedMs(clock())

    /** Ticker scroll distance in px, or null. For diagnostics and tests. */
    fun tickerDistanceOf(id: String): Float? = entries[id]?.scroll?.distance(clock())

    /** Called on RTMP connected (true) and disconnected / stopStream / release (false). */
    fun setLive(live: Boolean) {
        if (live == isLive) return
        isLive = live
        val now = clock()
        for (e in entries.values) updateRunning(e, now)
        refresh()
    }

    /** Pipeline transition: in-flight animations jump to their end state (spec §3). */
    fun snapAnimations() {
        val now = clock()
        for (e in entries.values.toList()) {
            when (e.state) {
                State.ENTERING -> finishEnter(e, now)
                State.EXITING -> finishExit(e, now)
                else -> {}
            }
        }
        refresh()
    }

    fun add(cfg: DynamicOverlayConfig) {
        val h = requireHost()
        entries[cfg.id]?.let { existing ->
            if (existing.removeReason == null) {
                throw OverlayException("OVERLAY_ID_EXISTS", "overlay '${cfg.id}' already exists; use updateOverlay")
            }
            finalizeRemove(existing, existing.removeReason!!)   // re-adding an id that is still animating out
        }
        if (entries.size >= MAX_OVERLAYS) {
            throw OverlayException("OVERLAY_LIMIT_REACHED", "max $MAX_OVERLAYS dynamic overlays")
        }
        val content = decodeOrThrow(cfg.content)
        val now = clock()
        val layer = DynamicLayer(cfg.id, content, cfg.placement, cfg.weight, nextSeq++)
        val e = Entry(
            layer,
            if (cfg.enter.animates) State.ENTERING else State.VISIBLE,
            OverlayTimer(cfg.durationMs),
            cfg.enter,
            cfg.exit,
            now
        )
        e.progress = 0f
        e.animStartMs = now
        content.ticker?.let { t ->
            e.scroll = ScrollProgress()
            e.tickerLastCopy = if (t.loop) null else 0
        }
        val downscaled = h.addDynamic(layer, attached = true, frame = frameOf(e, now))
        entries[cfg.id] = e
        e.dirty = true
        updateRunning(e, now)
        if (downscaled) warnDownscaled(cfg.id)
        if (e.state == State.VISIBLE) emit(mapOf("type" to "overlayShown", "id" to cfg.id))
        refresh()
    }

    fun update(u: DynamicOverlayUpdate) {
        val h = requireHost()
        val e = liveEntryOrThrow(u.id)
        val now = clock()
        if (u.content != null || u.placement != null || u.weight != null) {
            val content = u.content?.let { decodeOrThrow(it) }
            if (content != null) applyTickerChange(e, e.layer.content.ticker, content.ticker, h, now)
            if (content?.carousel != null) e.contentEpochMs = now   // new carousel restarts at item 0
            val layer = e.layer.copy(
                content = content ?: e.layer.content,
                placement = u.placement ?: e.layer.placement,
                weight = u.weight ?: e.layer.weight
            )
            e.layer = layer
            e.dirty = true
            val downscaled = h.updateDynamic(layer, frameOf(e, now))
            if (downscaled) warnDownscaled(u.id)
        }
        val duration = u.duration
        if (duration is DurationUpdate.Set) e.timer.durationMs = duration.ms
        if (u.restartTimer) e.timer.restart(now)
        if (e.expirePending) {
            val remaining = e.timer.remainingMs(now)
            if (remaining == null || remaining > 0L) {
                e.expirePending = false
                e.tickerLastCopy = if (e.layer.content.ticker?.loop == false) 0 else null
            }
        }
        updateRunning(e, now)
        if (e.expirePending && e.layer.content.ticker == null) beginExit(e, now, "expired")
        refresh()
    }

    fun hide(id: String) {
        requireHost()
        val e = liveEntryOrThrow(id)
        if (e.state == State.HIDDEN || e.state == State.EXITING) return
        beginExit(e, clock(), reason = null)
        refresh()
    }

    fun show(id: String) {
        val h = requireHost()
        val e = liveEntryOrThrow(id)
        val now = clock()
        when (e.state) {
            State.VISIBLE, State.ENTERING -> return
            State.HIDDEN -> {
                e.state = State.ENTERING
                e.progress = 0f
                e.animStartMs = now
                if (!e.enter.animates) e.state = State.VISIBLE
                h.setDynamicAttached(id, true, frameOf(e, now))
                e.dirty = true
                if (e.state == State.VISIBLE) emit(mapOf("type" to "overlayShown", "id" to id))
                updateRunning(e, now)
            }
            State.EXITING -> {
                if (e.enter.animates && e.enter.type == e.exit.type) {
                    startEnter(e, now, from = progressAt(e, now))
                } else {
                    finishExit(e, now)   // snap the hide to its end, then enter from scratch
                    show(id)
                    return
                }
            }
        }
        refresh()
    }

    fun remove(id: String, animate: Boolean = true) {
        requireHost()
        val e = liveEntryOrThrow(id)
        if (animate) beginExit(e, clock(), "removed") else finalizeRemove(e, "removed")
        refresh()
    }

    fun clear(animate: Boolean = false) {
        requireHost()
        val now = clock()
        for (e in entries.values.toList()) {
            val pending = e.removeReason
            when {
                pending != null -> if (!animate) finalizeRemove(e, pending)
                animate -> beginExit(e, now, "cleared")
                else -> finalizeRemove(e, "cleared")
            }
        }
        refresh()
    }

    /** One display frame: advance animations and tickers, render what changed. */
    fun onFrame() {
        val now = clock()
        for (e in entries.values.toList()) {
            if (entries[e.layer.id] !== e) continue
            when (e.state) {
                State.ENTERING -> if (progressAt(e, now) >= 1f) finishEnter(e, now)
                State.EXITING -> if (progressAt(e, now) <= 0f) finishExit(e, now)
                State.VISIBLE -> checkTickerFinished(e, now)
                State.HIDDEN -> {}
            }
        }
        host?.let { h ->
            for (e in entries.values) {
                if (e.state == State.HIDDEN) {
                    e.carouselWasActive = false
                    continue
                }
                val active = needsFrames(e, now)
                if (e.dirty || active || e.carouselWasActive) {
                    e.dirty = !h.renderDynamic(e.layer, frameOf(e, now))
                }
                e.carouselWasActive = active && e.layer.content.carousel != null
            }
        }
        refresh()
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    private fun progressAt(e: Entry<C>, now: Long): Float = when (e.state) {
        State.ENTERING -> (e.progress + (now - e.animStartMs) / e.enter.durationMs.toFloat()).coerceAtMost(1f)
        State.EXITING -> (e.progress - (now - e.animStartMs) / e.exit.durationMs.toFloat()).coerceAtLeast(0f)
        State.VISIBLE -> 1f
        State.HIDDEN -> 0f
    }

    private fun frameOf(e: Entry<C>, now: Long): LayerFrame {
        val (spec, exiting) = when (e.state) {
            State.ENTERING -> e.enter to false
            State.EXITING -> e.exit to true
            else -> AnimationSpec.NONE to false
        }
        val visible = if (spec.animates) OverlayAnimationMath.visible(spec, progressAt(e, now), exiting) else 1f
        return LayerFrame(
            animation = if (spec.animates) spec.type else AnimationType.NONE,
            edge = spec.edge,
            visible = visible,
            contentTimeMs = now - e.contentEpochMs,
            tickerDistancePx = e.scroll?.distance(now) ?: 0f,
            tickerLastCopy = e.tickerLastCopy
        )
    }

    private fun startEnter(e: Entry<C>, now: Long, from: Float) {
        e.removeReason = null
        e.progress = from
        e.animStartMs = now
        e.state = State.ENTERING
        e.dirty = true
        updateRunning(e, now)
    }

    /** Hide (reason null) or remove with [reason], applying the §4 interruption rules. */
    private fun beginExit(e: Entry<C>, now: Long, reason: String?) {
        when (e.state) {
            State.HIDDEN -> if (reason != null) finalizeRemove(e, reason)
            State.EXITING -> if (reason != null && e.removeReason == null) e.removeReason = reason
            State.VISIBLE -> startExit(e, now, reason, from = 1f)
            State.ENTERING -> {
                if (e.exit.animates && e.exit.type == e.enter.type) {
                    startExit(e, now, reason, from = progressAt(e, now))
                } else {
                    finishEnter(e, now)
                    startExit(e, now, reason, from = 1f)
                }
            }
        }
    }

    private fun startExit(e: Entry<C>, now: Long, reason: String?, from: Float) {
        e.removeReason = reason
        if (!e.exit.animates) {
            e.state = State.EXITING
            finishExit(e, now)
            return
        }
        e.progress = from
        e.animStartMs = now
        e.state = State.EXITING
        e.dirty = true
        updateRunning(e, now)
    }

    private fun finishEnter(e: Entry<C>, now: Long) {
        e.state = State.VISIBLE
        e.dirty = true
        updateRunning(e, now)
        emit(mapOf("type" to "overlayShown", "id" to e.layer.id))
    }

    private fun finishExit(e: Entry<C>, now: Long) {
        val reason = e.removeReason
        if (reason != null) {
            finalizeRemove(e, reason)
            return
        }
        e.state = State.HIDDEN
        updateRunning(e, now)
        host?.setDynamicAttached(e.layer.id, false, frameOf(e, now))
        emit(mapOf("type" to "overlayHidden", "id" to e.layer.id))
    }

    private fun finalizeRemove(e: Entry<C>, reason: String) {
        val id = e.layer.id
        host?.removeDynamic(id)
        entries.remove(id)
        emit(mapOf("type" to "overlayRemoved", "id" to id, "reason" to reason))
    }

    private fun updateRunning(e: Entry<C>, now: Long) {
        val run = isLive && e.state == State.VISIBLE
        e.timer.setRunning(run, now)
        val scroll = e.scroll ?: return
        val t = e.layer.content.ticker
        val h = host
        if (t != null && h != null) scroll.setSpeed(TickerMath.layout(t, e.layer.placement, h.frameSize).speedPxPerSec, now)
        scroll.setRunning(run, now)
    }

    // ---- ticker --------------------------------------------------------------------------------

    private fun applyTickerChange(e: Entry<C>, old: TickerMetrics?, new: TickerMetrics?, h: DynamicLayerHost<C>, now: Long) {
        if (new == null) {
            e.scroll = null
            e.tickerLastCopy = null
            return
        }
        val scroll = e.scroll
        if (old == null || scroll == null || old.text != new.text) {
            e.scroll = (scroll ?: ScrollProgress()).also { it.reset(now) }
            e.tickerLastCopy = if (new.loop && !e.expirePending) null else 0
            return
        }
        val oldLayout = TickerMath.layout(old, e.layer.placement, h.frameSize)
        val newLayout = TickerMath.layout(new, e.layer.placement, h.frameSize)
        scroll.rescale(newLayout.passLengthPx / oldLayout.passLengthPx, now)
        if (!new.loop && e.tickerLastCopy == null) {
            e.tickerLastCopy = TickerMath.newestCopy(scroll.distance(now), newLayout)
        } else if (new.loop && !e.expirePending) {
            e.tickerLastCopy = null
        }
    }

    private fun checkTickerFinished(e: Entry<C>, now: Long) {
        val t = e.layer.content.ticker ?: return
        val last = e.tickerLastCopy ?: return
        val h = host ?: return
        val scroll = e.scroll ?: return
        val l = TickerMath.layout(t, e.layer.placement, h.frameSize)
        scroll.setSpeed(l.speedPxPerSec, now)
        if (TickerMath.isFinished(scroll.distance(now), l, last)) {
            beginExit(e, now, if (e.expirePending) "expired" else "completed")
        }
    }

    // ---- scheduling ----------------------------------------------------------------------------

    private fun needsFrames(e: Entry<C>, now: Long): Boolean = when (e.state) {
        State.ENTERING, State.EXITING -> true
        State.HIDDEN -> false
        State.VISIBLE -> e.layer.content.animated || e.scroll?.isRunning == true ||
            e.layer.content.carousel?.inTransition(now - e.contentEpochMs) == true
    }

    private fun refresh() {
        rescheduleExpiry()
        rescheduleCarouselWake()
        updateFrameRequest()
    }

    private fun updateFrameRequest() {
        val now = clock()
        // Hidden layers are never rendered, so a leftover dirty flag must not keep frames running.
        val active = host != null && entries.values.any {
            it.state != State.HIDDEN && (it.dirty || it.carouselWasActive || needsFrames(it, now))
        }
        if (active != framesActive) {
            framesActive = active
            frames.setActive(active)
        }
    }

    /**
     * Expires every running overlay that is due, then arms one check for the soonest running expiry.
     * Paused timers (hidden / not live / animating) never expire, even past their duration: they expire as
     * soon as they run again. A ticker finishes its current pass first (spec §6).
     */
    private fun rescheduleExpiry() {
        cancelExpiryCheck?.invoke()
        cancelExpiryCheck = null

        val now = clock()
        val due = entries.values.filter { e ->
            !e.expirePending && e.timer.isRunning && e.timer.remainingMs(now)?.let { it <= 0L } == true
        }
        for (e in due) expire(e, now)

        val next = entries.values
            .filter { it.timer.isRunning && !it.expirePending }
            .mapNotNull { it.timer.remainingMs(now) }
            .minOrNull() ?: return
        cancelExpiryCheck = scheduler.schedule(next) {
            cancelExpiryCheck = null
            refresh()
        }
    }

    /**
     * Static carousels need no frames between transitions: arm one wake at the soonest transition start or cut
     * (spec §12). Carousels with GIF items are [DecodedContent.animated] and already frame-driven.
     */
    private fun rescheduleCarouselWake() {
        cancelCarouselWake?.invoke()
        cancelCarouselWake = null
        if (host == null) return
        val now = clock()
        val next = entries.values
            .filter { it.state == State.VISIBLE && !it.layer.content.animated && it.removeReason == null }
            .mapNotNull { e -> e.layer.content.carousel?.msUntilNextChange(now - e.contentEpochMs) }
            .filter { it > 0L }
            .minOrNull() ?: return
        cancelCarouselWake = scheduler.schedule(next) {
            cancelCarouselWake = null
            for (e in entries.values) if (e.layer.content.carousel != null && e.state != State.HIDDEN) e.dirty = true
            refresh()
        }
    }

    private fun expire(e: Entry<C>, now: Long) {
        val t = e.layer.content.ticker
        val h = host
        val scroll = e.scroll
        if (t == null || h == null || scroll == null) {
            beginExit(e, now, "expired")
            return
        }
        e.expirePending = true
        val newest = TickerMath.newestCopy(scroll.distance(now), TickerMath.layout(t, e.layer.placement, h.frameSize))
        e.tickerLastCopy = minOf(e.tickerLastCopy ?: newest, newest)
        checkTickerFinished(e, now)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun requireHost(): DynamicLayerHost<C> =
        host ?: throw OverlayException("OVERLAY_NOT_INITIALIZED", "call initPreview() or configure() before using overlays")

    /** Entries animating out toward removal are already gone as far as the API is concerned. */
    private fun liveEntryOrThrow(id: String): Entry<C> =
        entries[id]?.takeIf { it.removeReason == null }
            ?: throw OverlayException("OVERLAY_NOT_FOUND", "overlay '$id' not found")

    private fun decodeOrThrow(content: OverlayContentConfig): DecodedContent<C> =
        decode(content) ?: throw OverlayException("OVERLAY_DECODE_FAILED", "overlay content could not be decoded")

    private fun warnDownscaled(id: String) = emit(
        mapOf(
            "type" to "warning",
            "code" to "OVERLAY_DOWNSCALED",
            "message" to "overlay '$id' is larger than the frame and was scaled down to fit",
            "id" to id
        )
    )
}
