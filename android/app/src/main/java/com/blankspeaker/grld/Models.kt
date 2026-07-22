package com.blankspeaker.grld

data class ProductUsage(
    val product: Int,
    val name: String,
    val usagePercent: Int
)

data class UsagePeriod(
    val type: String,
    val start: String?,
    val end: String?
)

data class UsageResponse(
    val ok: Boolean,
    val remainingPercent: Int?,
    val usedPercent: Int?,
    val weeklyUsageAvailable: Boolean,
    val tierName: String?,
    val currentPeriod: UsagePeriod?,
    val productUsage: List<ProductUsage>,
    val fetchedAt: String,
    val cached: Boolean
)

enum class ProductColors(val id: Int, val argb: Long) {
    THIRD_PARTY(0, 0xFF9E9E9EL),
    API(1, 0xFF42A5F5L),
    BUILD(2, 0xFF66BB6AL),
    PLUGINS(3, 0xFFAB47BCL),
    CHAT(4, 0xFF26C6DAL),
    IMAGINE(5, 0xFFFFCA28L),
    VOICE(6, 0xFFEF5350L),
    /** GrokAppBuilder (API product id 7). */
    APP_BUILDER(7, 0xFF8D8D93L);

    companion object {
        /** Max categories we can show in the notification body (2 rows × 4). */
        const val NOTIF_SLOT_COUNT = 8

        /**
         * In-app chart colors (unchanged brand palette).
         * Notification dots/bar use [NotifPalette] so they stay in lockstep.
         */
        fun forId(id: Int): Int {
            entries.firstOrNull { it.id == id }?.let { return it.argb.toInt() }
            val hue = ((id * 47) % 360).toFloat()
            return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.58f, 0.88f))
        }

        fun entryForId(id: Int): ProductColors? =
            entries.firstOrNull { it.id == id }

        /** Display name for any product id (including future/unknown). */
        fun displayName(id: Int): String = when (id) {
            0 -> "3rd Party"
            1 -> "API"
            2 -> "Grok Build"
            3 -> "Grok Plugins"
            4 -> "Chat"
            5 -> "Imagine"
            6 -> "Voice"
            7 -> "App Builder"
            else -> "Other ($id)"
        }
    }
}

/**
 * Notification dots + bar share this palette (no red).
 * Order: green → blue → purple → brown → orange → yellow → black
 *
 * Colors are assigned by **rank among active products** (sorted by product id),
 * not by raw product id. That way the first category is always green, second
 * blue, etc. — unused product ids do not skip slots in the palette.
 */
object NotifPalette {
    private data class Swatch(val emoji: String, val argb: Int)

    private val ORDER = listOf(
        Swatch("🟢", 0xFF66BB6A.toInt()), // green
        Swatch("🔵", 0xFF42A5F5.toInt()), // blue
        Swatch("🟣", 0xFFAB47BC.toInt()), // purple
        Swatch("🟤", 0xFF8D6E63.toInt()), // brown
        Swatch("🟠", 0xFFFFA726.toInt()), // orange
        Swatch("🟡", 0xFFFFCA28.toInt()), // yellow
        Swatch("⚫", 0xFF424242.toInt())  // black (dark gray so it reads on dark UI)
    )

    /** First swatch (green) — used when no product segments exist. */
    val defaultArgb: Int get() = ORDER[0].argb
    val defaultEmoji: String get() = ORDER[0].emoji

    /**
     * Map product id → palette index for the given set of products.
     * Sorted by product id so assignment is stable across UI surfaces.
     */
    fun indexMap(productIds: Collection<Int>): Map<Int, Int> {
        if (productIds.isEmpty()) return emptyMap()
        return productIds.distinct().sorted().mapIndexed { i, id ->
            id to (i % ORDER.size)
        }.toMap()
    }

    fun indexMapFromUsage(products: Collection<ProductUsage>): Map<Int, Int> =
        indexMap(products.map { it.product })

    /**
     * Merge live API products with ids known from daily history so palette ranks
     * (green → blue → …) stay stable after import when today only has one product.
     */
    fun indexMapFromUsageAndHistory(
        products: Collection<ProductUsage>,
        historyIds: Collection<Int>
    ): Map<Int, Int> =
        indexMap(products.map { it.product } + historyIds)

    private fun swatchAt(index: Int): Swatch = ORDER[Math.floorMod(index, ORDER.size)]

    fun emoji(productId: Int, map: Map<Int, Int>): String {
        val idx = map[productId] ?: return defaultEmoji
        return swatchAt(idx).emoji
    }

    fun argb(productId: Int, map: Map<Int, Int>): Int {
        val idx = map[productId] ?: return defaultArgb
        return swatchAt(idx).argb
    }

    /** Fallback when no map is available (treat id as dense rank — prefer [indexMap]). */
    fun argb(productId: Int): Int = swatchAt(productId).argb
    fun emoji(productId: Int): String = swatchAt(productId).emoji
}
