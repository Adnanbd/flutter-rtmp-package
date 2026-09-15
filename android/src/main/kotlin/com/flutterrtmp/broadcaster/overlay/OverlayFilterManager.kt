package com.flutterrtmp.broadcaster.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.library.generic.GenericStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Per-pipeline overlay GL layers: sponsors, scoreband, dynamic overlays — all ordered by one [LayerStack]
 * (ADR 0014). A new instance is built whenever the encoder is re-prepared (dims/orientation change).
 *
 * Every filter is built by a factory that **decodes a fresh bitmap from cached bytes**: RootEncoder's
 * `TextureLoader.load` recycles the bitmap after uploading it, so a bitmap can never be handed to
 * `setImage` twice. Factory order is always setImage → setScale → setPosition, before `addFilter` (ADR 0010).
 *
 * Dynamic overlays use [DynamicLayerFilter] + [LayerRenderer] instead (ADR 0015): content is drawn per frame into
 * reusable bitmaps, and animations move the filter.
 */
class OverlayFilterManager(
    // Encoder (post-rotation) dimensions. Portrait: 720x1280. Landscape: 1280x720.
    private val streamWidth: Int,
    private val streamHeight: Int,
    // True when stream rotation is applied (setStreamRotation(270)).
    // Filters render in PRE-rotation (camera-native landscape) coordinate space,
    // so portrait needs counter-rotated bitmap and swapped scale/position.
    private val isPortrait: Boolean
) : DynamicLayerHost<OverlayVisual> {

    companion object {
        private const val TAG = "OverlayFilterManager"
        private const val SCOREBAND_ID = "scoreband"
        private const val SPONSOR_PREFIX = "sponsor_"
        const val DEFAULT_SPONSOR_WEIGHT = 10
        const val DEFAULT_SCOREBAND_WEIGHT = 50
        /** Avoids a degenerate sprite at pop progress 0. */
        private const val MIN_SCALE = 0.001f

        /** Decode bounds only — validates content and measures it without allocating pixels. */
        fun measure(bytes: ByteArray): DecodedContent<ByteArray>? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            return DecodedContent(bytes, opts.outWidth, opts.outHeight)
        }
    }

    private val frame = OverlayGeometry.FrameSize(streamWidth, streamHeight)
    override val frameSize: OverlayGeometry.FrameSize get() = frame

    private class DynState(var layer: DynamicLayer<OverlayVisual>, var frame: LayerFrame, val renderer: LayerRenderer)
    private val dynamic = HashMap<String, DynState>()
    private var stack: LayerStack<BaseFilterRender>? = null
    private var sponsorCount = 0
    private var scorebandWeight = DEFAULT_SCOREBAND_WEIGHT

    data class OverlayOpResult(val input: Int, val added: Int, val decodeFails: Int)

    /**
     * Build the stack for this pipeline: clears any filters left on the GL interface, then adds sponsors
     * and the given dynamic overlays (visible ones attached). The scoreband is added lazily on first update.
     * @return sponsor result plus ids of dynamic layers that were downscaled.
     */
    fun initLayers(
        stream: GenericStream,
        sponsorList: List<SponsorConfig>,
        dynamicLayers: List<SeedLayer<OverlayVisual>> = emptyList()
    ): OverlayOpResult {
        val s = LayerStack(GlFilterSink(stream))
        s.clear()
        stack = s
        sponsorCount = 0
        dynamic.clear()

        val result = addSponsors(s, sponsorList, "initLayers")
        for (seed in dynamicLayers) addDynamicTo(s, seed.layer, seed.attached, seed.frame)

        Log.d(TAG, "initLayers: input=${sponsorList.size}, added=${result.added}, decodeFails=${result.decodeFails}, " +
            "dynamic=${dynamicLayers.size}, scoreband=lazy, encDims=${streamWidth}x${streamHeight}, isPortrait=$isPortrait, " +
            "order=${s.attachedIds()}")
        return result
    }

    fun updateScoreband(pngBytes: ByteArray, widthParam: Float, xParam: Float, yParam: Float, weight: Int = DEFAULT_SCOREBAND_WEIGHT) {
        val s = stack
        if (s == null) {
            Log.e(TAG, "updateScoreband: stack NULL — initLayers not called")
            throw IllegalStateException("OVERLAY_NOT_INITIALIZED: updateScoreband called before initLayers — call configure() first")
        }
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode scoreband PNG — update skipped (bytes=${pngBytes.size})")
            throw IllegalArgumentException("OVERLAY_DECODE_FAILED: scoreband PNG decode returned null (bytes=${pngBytes.size})")
        }

        // Placement math lives in OverlayGeometry (post-rotation rect → pre-rotation filter transform).
        val rect = OverlayGeometry.scorebandRect(
            widthParam, xParam, yParam,
            bitmap.width.toFloat() / bitmap.height.toFloat(),
            frame
        )
        val t = OverlayGeometry.toFilter(rect, isPortrait)
        val factory = imageFactory(pngBytes, t)

        if (!s.contains(SCOREBAND_ID)) {
            scorebandWeight = weight
            s.put(SCOREBAND_ID, weight, LayerStack.LayerClass.SCOREBAND, 0, attached = true, factory)
            Log.d(TAG, "updateScoreband[create]: bmp=${bitmap.width}x${bitmap.height}, scale=(${t.scaleX},${t.scaleY})%, " +
                "pos=(${t.posX},${t.posY})%, dartW=${rect.w} dartX=$xParam dartY=$yParam weight=$weight, isPortrait=$isPortrait, order=${s.attachedIds()}")
        } else {
            s.setFactory(SCOREBAND_ID, factory)
            if (weight != scorebandWeight) {
                scorebandWeight = weight
                s.setWeight(SCOREBAND_ID, weight) // re-adds with the new factory at the new index
            } else {
                // In-place update, as before the layer stack: swap image + transform on the live filter.
                val filter = s.filterOf(SCOREBAND_ID) as? ImageObjectFilterRender
                if (filter != null) {
                    filter.setImage(orientBitmap(bitmap))
                    filter.setScale(t.scaleX, t.scaleY)
                    filter.setPosition(t.posX, t.posY)
                }
            }
            Log.d(TAG, "updateScoreband[update]: bmp=${bitmap.width}x${bitmap.height}, scale=(${t.scaleX},${t.scaleY})%, " +
                "pos=(${t.posX},${t.posY})%, dartW=${rect.w} dartX=$xParam dartY=$yParam weight=$weight")
        }
    }

    /** Replace all sponsor layers. Sponsors keep their weights relative to scoreband/dynamic layers. */
    fun updateSponsors(stream: GenericStream, sponsorList: List<SponsorConfig>): OverlayOpResult {
        val s = stack ?: LayerStack(GlFilterSink(stream)).also { stack = it }
        s.orderedIds().filter { it.startsWith(SPONSOR_PREFIX) }.forEach { s.remove(it) }
        sponsorCount = 0
        val result = addSponsors(s, sponsorList, "updateSponsors")
        Log.d(TAG, "updateSponsors: input=${sponsorList.size}, added=${result.added}, decodeFails=${result.decodeFails}, order=${s.attachedIds()}")
        return result
    }

    /** Re-add every visible layer in order with fresh filters (after the GL pipeline dropped them). */
    fun rebuild(where: String) {
        val s = stack ?: return
        s.rebuild()
        Log.d(TAG, "rebuild[$where]: order=${s.attachedIds()}")
    }

    val hasLayers: Boolean get() = (stack?.attachedCount ?: 0) > 0

    fun release(stream: GenericStream) {
        stack?.clear() ?: stream.getGlInterface().clearFilters()
        stack = null
        sponsorCount = 0
        dynamic.clear()
    }

    // ---- DynamicLayerHost ----------------------------------------------------------------------

    override fun addDynamic(layer: DynamicLayer<OverlayVisual>, attached: Boolean, frame: LayerFrame): Boolean =
        addDynamicTo(requireStack(), layer, attached, frame)

    override fun updateDynamic(layer: DynamicLayer<OverlayVisual>, frame: LayerFrame): Boolean {
        val s = requireStack()
        val st = dynState(layer.id)
        st.layer = layer
        st.frame = frame
        st.renderer.invalidate()
        val before = s.filterOf(layer.id)
        s.setWeight(layer.id, layer.weight)   // re-adds through the factory (reads st) if the weight changed
        val current = s.filterOf(layer.id)
        if (current != null && current === before) applyDynamic(current as DynamicLayerFilter, st)
        val downscaled = dynamicPlacement(layer).downscaled
        Log.d(TAG, "updateDynamic[${layer.id}]: content=${layer.content.width}x${layer.content.height} weight=${layer.weight} " +
            "downscaled=$downscaled order=${s.attachedIds()}")
        return downscaled
    }

    override fun setDynamicAttached(id: String, attached: Boolean, frame: LayerFrame) {
        val s = requireStack()
        val st = dynState(id)
        st.frame = frame
        if (attached) {
            s.attach(id)
        } else {
            s.detach(id)
            st.renderer.dropPool()
        }
        Log.d(TAG, "setDynamicAttached[$id]=$attached order=${s.attachedIds()}")
    }

    override fun removeDynamic(id: String) {
        val s = requireStack()
        s.remove(id)
        dynamic.remove(id)?.renderer?.dropPool()
        Log.d(TAG, "removeDynamic[$id] order=${s.attachedIds()}")
    }

    override fun renderDynamic(layer: DynamicLayer<OverlayVisual>, frame: LayerFrame): Boolean {
        val st = dynamic[layer.id] ?: return true
        st.layer = layer
        st.frame = frame
        val filter = stack?.filterOf(layer.id) as? DynamicLayerFilter ?: return true
        return applyDynamic(filter, st)
    }

    // ---- internals -----------------------------------------------------------------------------

    private fun requireStack(): LayerStack<BaseFilterRender> =
        stack ?: throw OverlayException("OVERLAY_NOT_INITIALIZED", "overlay layers not initialized")

    private fun dynState(id: String): DynState =
        dynamic[id] ?: throw OverlayException("OVERLAY_NOT_FOUND", "overlay '$id' has no layer in this pipeline")

    private fun addDynamicTo(s: LayerStack<BaseFilterRender>, layer: DynamicLayer<OverlayVisual>, attached: Boolean, frame: LayerFrame): Boolean {
        val st = DynState(layer, frame, LayerRenderer(isPortrait))
        dynamic[layer.id] = st
        s.put(layer.id, layer.weight, LayerStack.LayerClass.DYNAMIC, layer.seq, attached) { buildDynamicFilter(st) }
        val downscaled = dynamicPlacement(layer).downscaled
        Log.d(TAG, "addDynamic[${layer.id}]: content=${layer.content.width}x${layer.content.height} weight=${layer.weight} " +
            "attached=$attached downscaled=$downscaled order=${s.attachedIds()}")
        return downscaled
    }

    /** Factory for dynamic layers: always builds from the layer's latest state. */
    private fun buildDynamicFilter(st: DynState): BaseFilterRender =
        DynamicLayerFilter().also {
            st.renderer.invalidate()
            applyDynamic(it, st)
        }

    /**
     * Placement in the post-rotation frame. A ticker's size is its band: placement width × line height.
     * Wrapped text is measured at the placement width.
     */
    private fun dynamicPlacement(layer: DynamicLayer<OverlayVisual>): OverlayGeometry.PlacementResult {
        layer.content.wrap?.let { return OverlayGeometry.wrappedPlacementRect(layer.placement, frame, it) }
        val ticker = layer.content.ticker
            ?: return OverlayGeometry.placementRect(
                layer.placement, layer.content.width, layer.content.height, frame, layer.content.fillBox
            )
        val bandPx = TickerMath.bandWidthPx(TickerMath.bandOf(layer.placement), frame)
        return OverlayGeometry.placementRect(
            layer.placement.copy(width = OverlayGeometry.Length.Px(bandPx), height = null),
            max(1, bandPx.roundToInt()),
            ticker.bandHeightPx,
            frame
        )
    }

    /** Move/scale the filter for the current animation state and draw the content if it changed. */
    private fun applyDynamic(filter: DynamicLayerFilter, st: DynState): Boolean {
        val res = dynamicPlacement(st.layer)
        val anim = OverlayAnimationMath.frame(res.rect, st.frame.animation, st.frame.edge, st.frame.visible)
        val t = OverlayGeometry.toFilter(anim.rect, isPortrait)
        filter.setScale(max(t.scaleX, MIN_SCALE), max(t.scaleY, MIN_SCALE))
        filter.setPosition(t.posX, t.posY)
        // Render at the final (un-animated) display size so pop/slide never reallocate bitmaps.
        val wPx = max(1, (res.rect.w / 100f * frame.width).roundToInt())
        val hPx = max(1, (res.rect.h / 100f * frame.height).roundToInt())
        val layout = st.layer.content.ticker?.let { TickerMath.layout(it, st.layer.placement, frame) }
        return st.renderer.render(filter, st.layer.content.handle, st.frame, wPx, hPx, anim.reveal, layout)
    }

    private fun addSponsors(s: LayerStack<BaseFilterRender>, sponsorList: List<SponsorConfig>, where: String): OverlayOpResult {
        var decodeFails = 0
        var added = 0
        for ((idx, sponsor) in sponsorList.withIndex()) {
            val size = measure(sponsor.bytes)
            if (size == null) {
                Log.e(TAG, "$where: decode FAIL idx=$idx bytes=${sponsor.bytes.size}")
                decodeFails++
                continue
            }
            val rect = OverlayGeometry.sponsorRect(
                sponsor.left, sponsor.right, sponsor.top, sponsor.bottom, sponsor.width, sponsor.height,
                size.width.toFloat() / size.height.toFloat(),
                frame
            )
            val t = OverlayGeometry.toFilter(rect, isPortrait)
            val id = "$SPONSOR_PREFIX$idx"
            s.put(id, sponsor.weight, LayerStack.LayerClass.SPONSOR, idx.toLong(), attached = true, imageFactory(sponsor.bytes, t))
            added++
            sponsorCount++
            Log.d(TAG, "$where[add idx=$idx]: bmp=${size.width}x${size.height}, " +
                "L=${sponsor.left} R=${sponsor.right} T=${sponsor.top} B=${sponsor.bottom} w=${sponsor.width} h=${sponsor.height} " +
                "weight=${sponsor.weight}")
        }
        return OverlayOpResult(sponsorList.size, added, decodeFails)
    }

    /** Filter factory: decode fresh bitmap → orient → setImage → setScale → setPosition. */
    private fun imageFactory(bytes: ByteArray, t: OverlayGeometry.FilterTransform): () -> BaseFilterRender = {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("OVERLAY_DECODE_FAILED: overlay decode returned null (bytes=${bytes.size})")
        ImageObjectFilterRender().apply {
            setImage(orientBitmap(bitmap))
            setScale(t.scaleX, t.scaleY)
            setPosition(t.posX, t.posY)
        }
    }

    // Counter-rotates bitmap 90° CW so it appears upright after the frame's 90° CCW rotation.
    private fun orientBitmap(src: Bitmap): Bitmap {
        if (!isPortrait) return src
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
