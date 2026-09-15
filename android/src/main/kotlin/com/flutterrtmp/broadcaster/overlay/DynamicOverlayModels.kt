package com.flutterrtmp.broadcaster.overlay

import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.Length

/** Overlay API failure with a stable channel error code (docs/specs/dynamic-overlays.md §9). */
class OverlayException(val code: String, message: String) : RuntimeException(message)

enum class TextAlign { START, CENTER, END }

class TextStyleConfig(
    val fontSizePx: Float = 32f,
    /** ARGB32. */
    val color: Int = 0xFFFFFFFF.toInt(),
    /** ARGB32; null = no background. */
    val background: Int? = null,
    val paddingPx: Float = 8f,
    val fontTtf: ByteArray? = null,
    /** Text content only. 1 = single scaled line; > 1 = wrap to the placement width, ellipsize after this many lines. */
    val maxLines: Int = 1,
    val align: TextAlign = TextAlign.START
)

enum class TickerDirection { AUTO, RTL, LTR }

sealed class OverlayContentConfig {
    class Image(val bytes: ByteArray) : OverlayContentConfig()
    class Gif(val bytes: ByteArray) : OverlayContentConfig()

    /** [text] is flattened to one line, or keeps `\n` breaks when `style.maxLines > 1`. */
    class Text(val text: String, val style: TextStyleConfig) : OverlayContentConfig()

    /** [text] is already flattened to one line. At most one of [speedPxPerSec] / [cycleDurationMs]. */
    class Ticker(
        val text: String,
        val style: TextStyleConfig,
        val speedPxPerSec: Float?,
        val cycleDurationMs: Long?,
        val loop: Boolean,
        val loopGap: Length?,
        val direction: TickerDirection
    ) : OverlayContentConfig()

    /** Rotating image/GIF items in one slot (spec §12). Validated: 1–[DynamicOverlayParser.MAX_CAROUSEL_ITEMS] items. */
    class Carousel(
        val items: List<CarouselItemConfig>,
        val intervalMs: Long,
        val transition: CarouselTransition
    ) : OverlayContentConfig() {
        fun effectiveIntervalsMs(): LongArray = LongArray(items.size) { items[it].intervalMs ?: intervalMs }
    }
}

/** [content] is [OverlayContentConfig.Image] or [OverlayContentConfig.Gif]. */
class CarouselItemConfig(val content: OverlayContentConfig, val intervalMs: Long?)

enum class CarouselTransitionType { CUT, CROSSFADE, PUSH }

data class CarouselTransition(
    val type: CarouselTransitionType = CarouselTransitionType.CROSSFADE,
    val durationMs: Int = 500,
    val easing: Easing = Easing.EASE_IN_OUT,
    /** Push only: the side the incoming item enters from. */
    val edge: Edge = Edge.RIGHT
) {
    /** Transition length on the timeline; a cut takes none. */
    val effectiveMs: Long get() = if (type == CarouselTransitionType.CUT) 0L else durationMs.toLong()
}

enum class AnimationType { NONE, SLIDE, POP, CURTAIN }
enum class Easing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT }
enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

data class AnimationSpec(
    val type: AnimationType = AnimationType.NONE,
    val durationMs: Int = 400,
    val easing: Easing = Easing.EASE_OUT,
    val edge: Edge = Edge.BOTTOM
) {
    /** False when the transition is instant. */
    val animates: Boolean get() = type != AnimationType.NONE && durationMs > 0

    companion object {
        val NONE = AnimationSpec()
    }
}

data class DynamicOverlayConfig(
    val id: String,
    val content: OverlayContentConfig,
    val placement: OverlayGeometry.Placement,
    val weight: Int,
    /** Null = infinite. */
    val durationMs: Long? = null,
    val enter: AnimationSpec = AnimationSpec.NONE,
    val exit: AnimationSpec = AnimationSpec.NONE
)

/** `overlayUpdate.duration`: key omitted = [Keep]; `{ms: null}` = [Set] with null (infinite). */
sealed class DurationUpdate {
    object Keep : DurationUpdate()
    data class Set(val ms: Long?) : DurationUpdate()
}

data class DynamicOverlayUpdate(
    val id: String,
    val content: OverlayContentConfig?,
    val placement: OverlayGeometry.Placement?,
    val weight: Int?,
    val duration: DurationUpdate = DurationUpdate.Keep,
    val restartTimer: Boolean = false
)

/**
 * Parses and validates `overlay*` channel arguments. Pure Kotlin (no Android types) so it is unit-testable.
 * Wire format: docs/specs/dynamic-overlays.md §8.
 */
object DynamicOverlayParser {
    const val MAX_ID_LENGTH = 64
    const val DEFAULT_WEIGHT = 50
    const val MAX_ANIMATION_MS = 5000
    const val MAX_CAROUSEL_ITEMS = 20
    const val MIN_CAROUSEL_INTERVAL_MS = 500L
    const val DEFAULT_CAROUSEL_INTERVAL_MS = 5000L

    private val WHITESPACE = Regex("\\s+")
    private val INLINE_WHITESPACE = Regex("[^\\S\\n]+")

    fun parseAdd(args: Map<*, *>): DynamicOverlayConfig = DynamicOverlayConfig(
        id = parseId(args),
        content = parseContent(args["content"] as? Map<*, *>
            ?: throw OverlayException("OVERLAY_INVALID_CONTENT", "content is required")),
        placement = parsePlacement(args["placement"] as? Map<*, *>),
        weight = parseWeight(args["weight"]) ?: DEFAULT_WEIGHT,
        durationMs = parseDurationMs(args["durationMs"]),
        enter = parseAnimation(args["enter"], "enter"),
        exit = parseAnimation(args["exit"], "exit")
    )

    fun parseUpdate(args: Map<*, *>): DynamicOverlayUpdate = DynamicOverlayUpdate(
        id = parseId(args),
        content = (args["content"] as? Map<*, *>)?.let { parseContent(it) },
        placement = (args["placement"] as? Map<*, *>)?.let { parsePlacement(it) },
        weight = parseWeight(args["weight"]),
        duration = (args["duration"] as? Map<*, *>)?.let { DurationUpdate.Set(parseDurationMs(it["ms"])) }
            ?: DurationUpdate.Keep,
        restartTimer = args["restartTimer"] as? Boolean ?: false
    )

    /** `overlayRemove.animate` (default true) / `overlayClear.animate` (default false). */
    fun parseAnimate(args: Map<*, *>, default: Boolean): Boolean = args["animate"] as? Boolean ?: default

    fun parseId(args: Map<*, *>): String {
        val id = args["id"] as? String
            ?: throw OverlayException("OVERLAY_ID_RESERVED", "id is required")
        validateId(id)
        return id
    }

    fun validateId(id: String) {
        if (id.isEmpty() || id.length > MAX_ID_LENGTH) {
            throw OverlayException("OVERLAY_ID_RESERVED", "id must be 1–$MAX_ID_LENGTH characters")
        }
        if (id == "scoreband" || id.startsWith("sponsor_")) {
            throw OverlayException("OVERLAY_ID_RESERVED", "id '$id' is reserved")
        }
    }

    /** Line breaks and whitespace runs → single spaces (spec §6; also used for single-line text content). */
    fun flatten(text: String): String = text.replace(WHITESPACE, " ").trim()

    /** Wrapped text: keeps `\n` (CRLF/CR normalized), collapses other whitespace runs, trims each line and the ends. */
    fun normalizeKeepingBreaks(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .joinToString("\n") { it.replace(INLINE_WHITESPACE, " ").trim() }
            .trim('\n', ' ')

    fun parseContent(map: Map<*, *>): OverlayContentConfig = when (val type = map["type"]) {
        "image" -> OverlayContentConfig.Image(requireBytes(map, "image"))
        "gif" -> OverlayContentConfig.Gif(requireBytes(map, "gif"))
        "text" -> {
            val style = parseStyle(map["style"] as? Map<*, *>)
            OverlayContentConfig.Text(requireText(map, keepBreaks = style.maxLines > 1), style)
        }
        "ticker" -> parseTicker(map)
        "carousel" -> parseCarousel(map)
        else -> throw OverlayException("OVERLAY_INVALID_CONTENT", "unsupported content type '$type'")
    }

    private fun parseCarousel(map: Map<*, *>): OverlayContentConfig.Carousel {
        val rawItems = map["items"] as? List<*> ?: throw invalidContent("carousel requires items")
        if (rawItems.isEmpty() || rawItems.size > MAX_CAROUSEL_ITEMS) {
            throw invalidContent("carousel needs 1–$MAX_CAROUSEL_ITEMS items (got ${rawItems.size})")
        }
        val intervalMs = parseCarouselInterval(map["intervalMs"], "intervalMs") ?: DEFAULT_CAROUSEL_INTERVAL_MS
        val items = rawItems.mapIndexed { i, raw ->
            val item = raw as? Map<*, *> ?: throw invalidContent("carousel item $i must be a map")
            val content = item["content"] as? Map<*, *> ?: throw invalidContent("carousel item $i requires content")
            val parsed = when (val type = content["type"]) {
                "image" -> OverlayContentConfig.Image(requireBytes(content, "carousel item $i image"))
                "gif" -> OverlayContentConfig.Gif(requireBytes(content, "carousel item $i gif"))
                else -> throw invalidContent("carousel item $i must be image or gif (got '$type')")
            }
            CarouselItemConfig(parsed, parseCarouselInterval(item["intervalMs"], "items[$i].intervalMs"))
        }
        val transition = parseCarouselTransition(map["transition"])
        val carousel = OverlayContentConfig.Carousel(items, intervalMs, transition)
        if (items.size > 1 && transition.effectiveMs > 0L) {
            val shortest = carousel.effectiveIntervalsMs().minOrNull() ?: 0L
            if (transition.effectiveMs >= shortest) {
                throw invalidContent("transition.durationMs (${transition.durationMs}) must be shorter than every interval (shortest $shortest ms)")
            }
        }
        return carousel
    }

    private fun parseCarouselInterval(raw: Any?, field: String): Long? {
        if (raw == null) return null
        val ms = (raw as? Number)?.toLong() ?: throw invalidContent("$field must be an int (ms)")
        if (ms < MIN_CAROUSEL_INTERVAL_MS) throw invalidContent("$field must be ≥ $MIN_CAROUSEL_INTERVAL_MS ms (got $ms)")
        return ms
    }

    fun parseCarouselTransition(raw: Any?): CarouselTransition {
        val map = raw as? Map<*, *> ?: return CarouselTransition()
        val type = when (val t = map["type"] ?: "crossfade") {
            "cut" -> CarouselTransitionType.CUT
            "crossfade" -> CarouselTransitionType.CROSSFADE
            "push" -> CarouselTransitionType.PUSH
            else -> throw invalidContent("transition.type must be cut, crossfade or push (got '$t')")
        }
        val durationMs = (map["durationMs"] as? Number)?.toInt() ?: 500
        if (durationMs !in 0..MAX_ANIMATION_MS) {
            throw invalidContent("transition.durationMs must be 0–$MAX_ANIMATION_MS (got $durationMs)")
        }
        val easing = parseEasing(map["easing"] ?: "easeInOut", "transition")
        val edge = parseEdge(map["edge"] ?: "right", "transition")
        return CarouselTransition(type, durationMs, easing, edge)
    }

    private fun parseTicker(map: Map<*, *>): OverlayContentConfig.Ticker {
        val speed = (map["speedPxPerSec"] as? Number)?.toFloat()
        val cycleMs = (map["cycleDurationMs"] as? Number)?.toLong()
        if (speed != null && cycleMs != null) {
            throw invalidContent("ticker takes speedPxPerSec or cycleDuration, not both")
        }
        if (speed != null && (speed.isNaN() || speed <= 0f)) throw invalidContent("speedPxPerSec must be > 0 (got $speed)")
        if (cycleMs != null && cycleMs <= 0L) throw invalidContent("cycleDuration must be > 0 ms (got $cycleMs)")
        val direction = when (val d = map["direction"] ?: "auto") {
            "auto" -> TickerDirection.AUTO
            "rtl" -> TickerDirection.RTL
            "ltr" -> TickerDirection.LTR
            else -> throw invalidContent("ticker direction must be auto, rtl or ltr (got '$d')")
        }
        return OverlayContentConfig.Ticker(
            text = requireText(map),
            style = parseStyle(map["style"] as? Map<*, *>),
            speedPxPerSec = speed,
            cycleDurationMs = cycleMs,
            loop = map["loop"] as? Boolean ?: true,
            loopGap = parseLength(map, "loopGap"),
            direction = direction
        )
    }

    fun parseStyle(map: Map<*, *>?): TextStyleConfig {
        if (map == null) return TextStyleConfig()
        val fontSize = (map["fontSizePx"] as? Number)?.toFloat() ?: 32f
        if (fontSize.isNaN() || fontSize <= 0f) throw invalidContent("fontSizePx must be > 0 (got $fontSize)")
        val padding = (map["paddingPx"] as? Number)?.toFloat() ?: 8f
        if (padding.isNaN() || padding < 0f) throw invalidContent("paddingPx must be ≥ 0 (got $padding)")
        val ttf = map["fontTtf"] as? ByteArray
        if (ttf != null && ttf.isEmpty()) throw OverlayException("OVERLAY_FONT_INVALID", "fontTtf is empty")
        val maxLines = (map["maxLines"] as? Number)?.toInt() ?: 1
        if (maxLines < 1) throw invalidContent("maxLines must be ≥ 1 (got $maxLines)")
        val align = when (val a = map["align"] ?: "start") {
            "start" -> TextAlign.START
            "center" -> TextAlign.CENTER
            "end" -> TextAlign.END
            else -> throw invalidContent("align must be start, center or end (got '$a')")
        }
        return TextStyleConfig(
            fontSizePx = fontSize,
            color = argb(map["color"]) ?: 0xFFFFFFFF.toInt(),
            background = argb(map["background"]),
            paddingPx = padding,
            fontTtf = ttf,
            maxLines = maxLines,
            align = align
        )
    }

    fun parseAnimation(raw: Any?, field: String): AnimationSpec {
        val map = raw as? Map<*, *> ?: return AnimationSpec.NONE
        val type = when (val t = map["type"] ?: "none") {
            "none" -> AnimationType.NONE
            "slide" -> AnimationType.SLIDE
            "pop" -> AnimationType.POP
            "curtain" -> AnimationType.CURTAIN
            else -> throw invalidContent("$field.type must be none, slide, pop or curtain (got '$t')")
        }
        val durationMs = (map["durationMs"] as? Number)?.toInt() ?: 400
        if (durationMs !in 0..MAX_ANIMATION_MS) {
            throw invalidContent("$field.durationMs must be 0–$MAX_ANIMATION_MS (got $durationMs)")
        }
        val easing = parseEasing(map["easing"] ?: "easeOut", field)
        val edge = parseEdge(map["edge"] ?: "bottom", field)
        return AnimationSpec(type, durationMs, easing, edge)
    }

    private fun parseEasing(raw: Any, field: String): Easing = when (raw) {
        "linear" -> Easing.LINEAR
        "easeIn" -> Easing.EASE_IN
        "easeOut" -> Easing.EASE_OUT
        "easeInOut" -> Easing.EASE_IN_OUT
        else -> throw invalidContent("$field.easing must be linear, easeIn, easeOut or easeInOut (got '$raw')")
    }

    private fun parseEdge(raw: Any, field: String): Edge = when (raw) {
        "left" -> Edge.LEFT
        "right" -> Edge.RIGHT
        "top" -> Edge.TOP
        "bottom" -> Edge.BOTTOM
        else -> throw invalidContent("$field.edge must be left, right, top or bottom (got '$raw')")
    }

    fun parsePlacement(map: Map<*, *>?): OverlayGeometry.Placement {
        if (map == null) return OverlayGeometry.Placement()
        return OverlayGeometry.Placement(
            left = parseLength(map, "left"),
            right = parseLength(map, "right"),
            top = parseLength(map, "top"),
            bottom = parseLength(map, "bottom"),
            width = parseLength(map, "width"),
            height = parseLength(map, "height")
        )
    }

    private fun parseLength(parent: Map<*, *>, key: String): Length? {
        val m = parent[key] as? Map<*, *> ?: return null
        val value = (m["value"] as? Number)?.toFloat()
            ?: throw OverlayException("OVERLAY_INVALID_PLACEMENT", "$key.value is required")
        if (value.isNaN() || value < 0f) {
            throw OverlayException("OVERLAY_INVALID_PLACEMENT", "$key must be ≥ 0 (got $value)")
        }
        return when (val unit = m["unit"]) {
            "percent" -> {
                if (value > 100f) throw OverlayException("OVERLAY_INVALID_PLACEMENT", "$key percent must be ≤ 100 (got $value)")
                Length.Percent(value)
            }
            "px" -> Length.Px(value)
            else -> throw OverlayException("OVERLAY_INVALID_PLACEMENT", "$key.unit must be 'percent' or 'px' (got '$unit')")
        }
    }

    private fun parseWeight(raw: Any?): Int? {
        if (raw == null) return null
        val w = (raw as? Number)?.toInt()
            ?: throw OverlayException("OVERLAY_INVALID_PLACEMENT", "weight must be an int")
        if (w !in 0..100) throw OverlayException("OVERLAY_INVALID_PLACEMENT", "weight must be 0–100 (got $w)")
        return w
    }

    /** Null = infinite. Otherwise must be a positive int (ms). */
    private fun parseDurationMs(raw: Any?): Long? {
        if (raw == null) return null
        val ms = (raw as? Number)?.toLong()
            ?: throw OverlayException("OVERLAY_INVALID_CONTENT", "duration must be an int (ms)")
        if (ms <= 0L) throw OverlayException("OVERLAY_INVALID_CONTENT", "duration must be > 0 ms (got $ms)")
        return ms
    }

    private fun requireBytes(map: Map<*, *>, type: String): ByteArray {
        val bytes = map["bytes"] as? ByteArray
        if (bytes == null || bytes.isEmpty()) throw invalidContent("$type content requires non-empty bytes")
        return bytes
    }

    private fun requireText(map: Map<*, *>, keepBreaks: Boolean = false): String {
        val raw = map["text"] as? String ?: ""
        val text = if (keepBreaks) normalizeKeepingBreaks(raw) else flatten(raw)
        if (text.isEmpty()) throw invalidContent("text must not be empty")
        return text
    }

    /** Dart sends ARGB32 as int; values above Int.MAX_VALUE arrive as Long. */
    private fun argb(raw: Any?): Int? = (raw as? Number)?.toLong()?.toInt()

    private fun invalidContent(message: String) = OverlayException("OVERLAY_INVALID_CONTENT", message)
}
