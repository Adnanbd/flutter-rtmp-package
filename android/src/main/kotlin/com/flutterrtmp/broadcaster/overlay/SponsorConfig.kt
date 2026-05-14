package com.flutterrtmp.broadcaster.overlay

data class SponsorConfig(
    val bytes: ByteArray,
    val left: Int?,
    val right: Int?,
    val top: Int?,
    val bottom: Int?,
    val width: Int,
    val height: Int
) {
    companion object {
        fun fromMap(map: Map<String, Any>): SponsorConfig = SponsorConfig(
            bytes = map["bytes"] as ByteArray,
            left = map["left"] as? Int,
            right = map["right"] as? Int,
            top = map["top"] as? Int,
            bottom = map["bottom"] as? Int,
            width = map["width"] as Int,
            height = map["height"] as Int
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SponsorConfig) return false
        return left == other.left && right == other.right &&
            top == other.top && bottom == other.bottom &&
            width == other.width && height == other.height &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (left ?: 0)
        result = 31 * result + (right ?: 0)
        result = 31 * result + (top ?: 0)
        result = 31 * result + (bottom ?: 0)
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
