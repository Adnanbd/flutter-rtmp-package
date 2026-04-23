package com.flutterrtmp.broadcaster.overlay

data class SponsorConfig(
    val bytes: ByteArray,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    companion object {
        fun fromMap(map: Map<String, Any>): SponsorConfig = SponsorConfig(
            bytes = map["bytes"] as ByteArray,
            x = (map["x"] as Double).toFloat(),
            y = (map["y"] as Double).toFloat(),
            width = (map["width"] as Double).toFloat(),
            height = (map["height"] as Double).toFloat()
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SponsorConfig) return false
        return x == other.x && y == other.y &&
            width == other.width && height == other.height &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + width.hashCode()
        result = 31 * result + height.hashCode()
        return result
    }
}
