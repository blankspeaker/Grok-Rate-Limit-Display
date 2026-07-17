package com.blankspeaker.grld

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class DaySlice(
    var dayKey: String,
    var periodStart: String,
    var lastUsedPercent: Int,
    var firstUsedPercent: Int,
    var dayDelta: Int,
    var isPreResetFragment: Boolean,
    var frozen: Boolean,
    var productLast: MutableMap<String, Int> = mutableMapOf(),
    var productDelta: MutableMap<String, Int> = mutableMapOf()
)

data class ProductSegment(val product: Int, val delta: Int)

data class ChartBar(
    val dayKey: String,
    val weekdayLabel: String,
    val dayDelta: Int,
    val isPreResetFragment: Boolean,
    val periodStart: String,
    val segments: List<ProductSegment>
)

/**
 * Port of macOS DailyHistoryStore — day-over-day weekly quota usage.
 * **Singleton** so MainActivity, Settings, and UsageMonitorService share one in-memory
 * store (import must not be wiped by a stale service instance calling save()).
 */
class DailyHistoryStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "daily_history.json")
    var slices: MutableList<DaySlice> = mutableListOf()
        private set
    private var lastSeenPeriodStart: String? = null
    /** Pending mid-period hard reset; undo if usage recovers within grace window. */
    private var pendingHardReset: PendingHardReset? = null

    private data class PendingHardReset(
        val dayKey: String,
        val previousUsed: Int,
        val previousProductLast: Map<String, Int>,
        val prePeriodStart: String,
        val postPeriodStart: String,
        val atEpochMs: Long
    )

    init { load() }

    /** Re-read from disk (e.g. after external file replace). */
    @Synchronized
    fun reload() { load() }

    @Synchronized
    fun record(usedPercent: Int, periodStart: String?, productUsage: List<ProductUsage>, at: Date = Date()) {
        if (periodStart.isNullOrBlank()) return
        val used = usedPercent.coerceIn(0, 100)
        val day = dayKey(at)
        val productLast = productMap(productUsage)
        val nowMs = at.time

        // Resolve pending hard reset (false-positive window).
        pendingHardReset?.let { pending ->
            if (pending.dayKey != day) {
                pendingHardReset = null
            } else {
                val ageMs = nowMs - pending.atEpochMs
                val recovered = used >= max(pending.previousUsed - 3, HARD_RESET_MIN_PREV_USED)
                if (recovered && ageMs < HARD_RESET_GRACE_MS) {
                    undoHardReset(pending, used, productLast)
                    lastSeenPeriodStart = periodStart
                    sanitizeFalseResetFragments()
                    save()
                    return
                }
                if (ageMs >= HARD_RESET_GRACE_MS) {
                    pendingHardReset = null
                }
            }
        }

        // Real weekly periodStart change → freeze previous period as ·prev.
        val prev = lastSeenPeriodStart
        if (prev != null) {
            val prevBase = basePeriodStart(prev)
            if (!samePeriod(prevBase, periodStart) && !samePeriod(prev, periodStart)) {
                freezePeriod(prev, day, asPreReset = true)
                freezeHardResetPosts(prevBase, day)
                pendingHardReset = null
            }
        }

        val idx = openSliceIndex(day, periodStart)
        if (idx >= 0) {
            val s = slices[idx]
            val alreadyPost = isHardResetPost(s.periodStart, periodStart)
            if (!alreadyPost &&
                s.lastUsedPercent >= HARD_RESET_MIN_PREV_USED &&
                used <= HARD_RESET_MAX_POST_USED &&
                (s.lastUsedPercent - used) >= HARD_RESET_MIN_DROP
            ) {
                applyHardReset(idx, day, periodStart, used, productLast, nowMs)
            } else {
                s.lastUsedPercent = max(s.lastUsedPercent, used)
                for ((k, v) in productLast) {
                    s.productLast[k] = max(s.productLast[k] ?: 0, v)
                }
                // Post hard-reset bars must not use yesterday's cumulative as baseline
                s.dayDelta = computeDelta(s)
                s.productDelta = computeProductDelta(s)
                s.isPreResetFragment = false
            }
        } else {
            val hasConfirmedSplit = dayHasPreResetFragment(day)
            if (!hasConfirmedSplit) {
                slices.removeAll {
                    it.dayKey == day && samePeriod(it.periodStart, periodStart) && it.isPreResetFragment
                }
            }
            // After a mid-week wipe, open bar uses synthetic |reset| key + zero baseline
            val openPeriod = if (hasConfirmedSplit) {
                slices.firstOrNull {
                    it.dayKey == day && isHardResetPost(it.periodStart, periodStart)
                }?.periodStart ?: "$periodStart|reset|${nowMs / 1000}"
            } else {
                periodStart
            }
            appendOpen(day, openPeriod, used, productLast)
        }
        lastSeenPeriodStart = periodStart
        sanitizeFalseResetFragments()
        save()
    }

    private fun openSliceIndex(day: String, periodStart: String): Int {
        val exact = slices.indexOfFirst {
            it.dayKey == day && samePeriod(it.periodStart, periodStart) && !it.frozen
        }
        if (exact >= 0) return exact
        return slices.indexOfFirst {
            it.dayKey == day && !it.frozen && isHardResetPost(it.periodStart, periodStart)
        }
    }

    private fun applyHardReset(
        idx: Int,
        day: String,
        apiPeriodStart: String,
        used: Int,
        productLast: Map<String, Int>,
        atEpochMs: Long
    ) {
        val pre = slices[idx]
        val prevUsed = pre.lastUsedPercent
        val prevProducts = pre.productLast.toMap()
        pre.frozen = true
        pre.isPreResetFragment = true
        pre.dayDelta = computeDelta(pre)
        pre.productDelta = computeProductDelta(pre)

        val postPeriod = "$apiPeriodStart|reset|${atEpochMs / 1000}"
        pendingHardReset = PendingHardReset(
            dayKey = day,
            previousUsed = prevUsed,
            previousProductLast = prevProducts,
            prePeriodStart = pre.periodStart,
            postPeriodStart = postPeriod,
            atEpochMs = atEpochMs
        )
        appendOpen(day, postPeriod, used, productLast)
    }

    private fun undoHardReset(
        pending: PendingHardReset,
        used: Int,
        productLast: Map<String, Int>
    ) {
        val base = basePeriodStart(pending.prePeriodStart)
        slices.removeAll {
            it.dayKey == pending.dayKey &&
                (it.periodStart == pending.postPeriodStart || isHardResetPost(it.periodStart, base))
        }
        val i = slices.indexOfFirst {
            it.dayKey == pending.dayKey && samePeriod(it.periodStart, pending.prePeriodStart)
        }
        if (i >= 0) {
            val s = slices[i]
            s.frozen = false
            s.isPreResetFragment = false
            s.lastUsedPercent = max(s.lastUsedPercent, max(used, pending.previousUsed))
            for ((k, v) in pending.previousProductLast) {
                s.productLast[k] = max(s.productLast[k] ?: 0, v)
            }
            for ((k, v) in productLast) {
                s.productLast[k] = max(s.productLast[k] ?: 0, v)
            }
            s.dayDelta = computeDelta(s)
            s.productDelta = computeProductDelta(s)
        }
        pendingHardReset = null
    }

    private fun freezeHardResetPosts(apiPeriod: String, day: String) {
        for (s in slices) {
            if (s.dayKey != day || s.frozen || !isHardResetPost(s.periodStart, apiPeriod)) continue
            s.frozen = true
            s.isPreResetFragment = false
            s.dayDelta = computeDelta(s)
            s.productDelta = computeProductDelta(s)
        }
    }

    /**
     * Product ids with real usage in stored history (import + samples).
     * Ignores keys that only ever sat at 0% (legacy API empty product rows).
     * Used so category legend / palette stay stable when today’s API only
     * returns products with non-zero usage.
     */
    fun knownProductIds(): Set<Int> {
        val ids = mutableSetOf<Int>()
        for (s in slices) {
            for ((k, v) in s.productLast) {
                if (v > 0) k.toIntOrNull()?.let { ids.add(it) }
            }
            for ((k, v) in s.productDelta) {
                if (v > 0) k.toIntOrNull()?.let { ids.add(it) }
            }
        }
        return ids
    }

    /**
     * Bars for a trailing window ending on [endDayKey].
     * @param dayCount number of calendar days (7 / 14 / 21 for 1–3 weeks)
     */
    fun bars(endDayKey: String, dayCount: Int = 7): List<ChartBar> {
        val n = dayCount.coerceIn(1, 42)
        val dayKeys = (0 until n).reversed().mapNotNull { addDays(endDayKey, -it) }
        val result = mutableListOf<ChartBar>()
        for (day in dayKeys) {
            // Pre-reset (black ·prev) always LEFT of the new post-reset bar for that day
            val daySlices = slices.filter { it.dayKey == day }.sortedWith(
                compareByDescending<DaySlice> { it.isPreResetFragment }
                    .thenBy { it.periodStart }
            )
            if (daySlices.isEmpty()) {
                result.add(
                    ChartBar(day, weekdayShort(day), 0, false, "", emptyList())
                )
            } else {
                for (s in daySlices) {
                    var label = weekdayShort(day)
                    if (s.isPreResetFragment) {
                        label = "$label·prev"
                    }
                    // Prefer stored deltas (match Mac). Only recompute live open post-reset.
                    val delta = if (s.isPreResetFragment || s.frozen) {
                        s.dayDelta.coerceIn(0, 100)
                    } else {
                        computeDelta(s)
                    }
                    var prodMap = s.productDelta.toMutableMap()
                    if (prodMap.isEmpty() && !s.isPreResetFragment) {
                        // Historical day with empty productDelta — derive from productLast
                        prodMap = computeProductDelta(s)
                    } else if (!s.isPreResetFragment && !s.frozen) {
                        prodMap = computeProductDelta(s)
                    }
                    val segs = prodMap.mapNotNull { (k, v) ->
                        val id = k.toIntOrNull() ?: return@mapNotNull null
                        if (v > 0) ProductSegment(id, v) else null
                    }.sortedBy { it.product }
                    result.add(
                        ChartBar(
                            day, label, delta, s.isPreResetFragment, s.periodStart, segs
                        )
                    )
                }
            }
        }
        return result
    }

    private fun appendOpen(
        day: String,
        periodStart: String,
        used: Int,
        productLast: Map<String, Int>
    ) {
        val s = DaySlice(
            dayKey = day,
            periodStart = periodStart,
            lastUsedPercent = used,
            firstUsedPercent = used,
            dayDelta = 0,
            isPreResetFragment = false,
            frozen = false,
            productLast = productLast.toMutableMap()
        )
        // Baseline 0 after mid-week hard reset; else previous calendar day in period
        s.dayDelta = computeDelta(s)
        s.productDelta = computeProductDelta(s)
        slices.add(s)
    }

    private fun freezePeriod(periodStart: String, day: String, asPreReset: Boolean) {
        for (i in slices.indices) {
            if (!samePeriod(slices[i].periodStart, periodStart) || slices[i].frozen) continue
            if (slices[i].dayKey == day) slices[i].isPreResetFragment = asPreReset
            slices[i].frozen = true
            slices[i].dayDelta = computeDelta(slices[i])
            slices[i].productDelta = computeProductDelta(slices[i])
        }
    }

    private fun dayHasPreResetFragment(day: String): Boolean =
        slices.any { it.dayKey == day && it.isPreResetFragment }

    /** True for the open half of a mid-week hard reset (quota restarted at ~0). */
    private fun isPostHardResetSlice(slice: DaySlice): Boolean {
        if (slice.isPreResetFragment) return false
        if (slice.periodStart.contains("|reset|")) return true
        return dayHasPreResetFragment(slice.dayKey)
    }

    private fun previousDaySlice(dayKey: String, periodStart: String): DaySlice? {
        val prevKey = addDays(dayKey, -1) ?: return null
        return slices
            .filter {
                it.dayKey == prevKey &&
                    samePeriod(basePeriodStart(it.periodStart), basePeriodStart(periodStart)) &&
                    !it.isPreResetFragment
            }
            .maxByOrNull { it.lastUsedPercent }
            ?: slices
                .filter {
                    it.dayKey == prevKey &&
                        samePeriod(basePeriodStart(it.periodStart), basePeriodStart(periodStart))
                }
                .maxByOrNull { it.lastUsedPercent }
    }

    private fun previousDayLast(dayKey: String, periodStart: String): Int =
        previousDaySlice(dayKey, periodStart)?.lastUsedPercent ?: 0

    private fun computeDelta(slice: DaySlice): Int {
        // Post hard-reset: used% is already "today since wipe" — do not subtract yesterday
        if (isPostHardResetSlice(slice)) {
            return slice.lastUsedPercent.coerceIn(0, 100)
        }
        val prevLast = previousDayLast(slice.dayKey, slice.periodStart)
        return (slice.lastUsedPercent - prevLast).coerceIn(0, 100)
    }

    private fun computeProductDelta(slice: DaySlice): MutableMap<String, Int> {
        val prev = if (isPostHardResetSlice(slice)) {
            emptyMap()
        } else {
            previousDaySlice(slice.dayKey, slice.periodStart)?.productLast ?: emptyMap()
        }
        val out = mutableMapOf<String, Int>()
        val keys = slice.productLast.keys + prev.keys
        for (k in keys) {
            val d = max(0, (slice.productLast[k] ?: 0) - (prev[k] ?: 0))
            if (d > 0) out[k] = d
        }
        return out
    }

    private fun productMap(products: List<ProductUsage>): Map<String, Int> =
        products
            .filter { it.usagePercent > 0 }
            .associate { it.product.toString() to it.usagePercent.coerceIn(0, 100) }

    private fun samePeriod(a: String, b: String) = periodKey(a) == periodKey(b)

    private fun periodKey(raw: String): String {
        val s = raw.trim()
        // Synthetic hard-reset posts keep a unique key so the same day can have two bars.
        if (s.contains("|reset|")) return s
        return if (s.length >= 16) s.take(16) else s
    }

    private fun basePeriodStart(raw: String): String {
        val i = raw.indexOf("|reset|")
        return if (i >= 0) raw.substring(0, i) else raw
    }

    private fun isHardResetPost(slicePeriod: String, apiPeriod: String): Boolean {
        if (!slicePeriod.contains("|reset|")) return false
        return samePeriod(basePeriodStart(slicePeriod), apiPeriod)
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            lastSeenPeriodStart = root.optString("lastSeenPeriodStart", null).ifBlank { null }
            pendingHardReset = parsePending(root.optJSONObject("pendingHardReset"))
            val arr = root.optJSONArray("slices") ?: return
            val list = mutableListOf<DaySlice>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pl = mutableMapOf<String, Int>()
                val plObj = o.optJSONObject("productLast")
                plObj?.keys()?.forEach { k -> pl[k] = plObj.optInt(k) }
                val pd = mutableMapOf<String, Int>()
                val pdObj = o.optJSONObject("productDelta")
                pdObj?.keys()?.forEach { k -> pd[k] = pdObj.optInt(k) }
                list.add(
                    DaySlice(
                        dayKey = o.getString("dayKey"),
                        periodStart = o.getString("periodStart"),
                        lastUsedPercent = o.optInt("lastUsedPercent"),
                        firstUsedPercent = o.optInt("firstUsedPercent"),
                        dayDelta = o.optInt("dayDelta"),
                        isPreResetFragment = o.optBoolean("isPreResetFragment"),
                        frozen = o.optBoolean("frozen"),
                        productLast = pl,
                        productDelta = pd
                    )
                )
            }
            slices = list
            // Do NOT sanitize on load — that rewrote imported Mac mid-week ·prev bars.
            // Sanitize runs after live record() only.
        } catch (_: Exception) { }
    }

    private fun parsePending(o: JSONObject?): PendingHardReset? {
        if (o == null) return null
        val day = o.optString("dayKey").ifBlank { return null }
        val pre = o.optString("prePeriodStart").ifBlank { return null }
        val post = o.optString("postPeriodStart").ifBlank { return null }
        val pl = mutableMapOf<String, Int>()
        o.optJSONObject("previousProductLast")?.keys()?.forEach { k ->
            pl[k] = o.getJSONObject("previousProductLast").optInt(k)
        }
        return PendingHardReset(
            dayKey = day,
            previousUsed = o.optInt("previousUsed"),
            previousProductLast = pl,
            prePeriodStart = pre,
            postPeriodStart = post,
            atEpochMs = o.optLong("atEpochMs", o.optLong("atEpoch", 0L) * 1000L)
        )
    }

    /**
     * Only a real weekly period change may put two bars on one day.
     * Same-period ·prev fragments (API usage jitter) are collapsed.
     * @return true if anything changed
     */
    @Synchronized
    fun sanitizeFalseResetFragments(): Boolean {
        val today = dayKey()
        val byDay = slices.groupBy { it.dayKey }
        val next = mutableListOf<DaySlice>()
        var changed = false

        for (day in byDay.keys.sorted()) {
            val daySlices = byDay[day].orEmpty()
            val byPeriod = daySlices.groupBy { periodKey(it.periodStart) }
            val periodKeys = byPeriod.keys.sorted()

            if (periodKeys.size <= 1) {
                val group = byPeriod[periodKeys.firstOrNull()] ?: continue
                // ONLY explicit isPreResetFragment is black ·prev — never every frozen day
                // (frozen normal days like Jul 8 still have category colors).
                val preGroup = group.filter { it.isPreResetFragment }
                val restGroup = group.filter { !it.isPreResetFragment }
                if (preGroup.isNotEmpty() && restGroup.isNotEmpty()) {
                    if (group.size > 2) changed = true
                    var pre = preGroup.reduce { a, b -> mergeSlices(a, b) }
                    pre.isPreResetFragment = true
                    pre.frozen = true
                    var rest = restGroup.reduce { a, b -> mergeSlices(a, b) }
                    rest.isPreResetFragment = false
                    if (day == today && !rest.frozen) rest.frozen = false
                    next.add(pre)
                    next.add(rest)
                } else if (preGroup.isNotEmpty()) {
                    if (group.size > 1) changed = true
                    var pre = preGroup.reduce { a, b -> mergeSlices(a, b) }
                    pre.isPreResetFragment = true
                    pre.frozen = true
                    next.add(pre)
                } else {
                    if (group.size > 1) changed = true
                    var merged = restGroup.reduce { a, b -> mergeSlices(a, b) }
                    merged.isPreResetFragment = false
                    // Keep frozen flag if all inputs frozen (historical day)
                    if (day == today && restGroup.any { !it.frozen }) {
                        merged.frozen = false
                    }
                    next.add(merged)
                }
            } else {
                for ((i, pk) in periodKeys.withIndex()) {
                    val group = byPeriod[pk] ?: continue
                    if (group.size > 1) changed = true
                    var merged = group.reduce { a, b -> mergeSlices(a, b) }
                    val isPrev = i < periodKeys.lastIndex
                    if (merged.isPreResetFragment != isPrev) changed = true
                    merged.isPreResetFragment = isPrev
                    if (isPrev) {
                        merged.frozen = true
                    } else if (day == today) {
                        merged.frozen = false
                    }
                    next.add(merged)
                }
            }
        }

        next.sortWith(
            compareBy<DaySlice> { it.dayKey }
                .thenBy { it.periodStart }
                .thenBy { it.isPreResetFragment }
        )
        if (next.size != slices.size) changed = true
        if (changed) slices = next
        return changed
    }

    private fun save() {
        try {
            file.writeText(toStorageJson().toString())
        } catch (_: Exception) { }
    }

    private fun toStorageJson(): JSONObject {
        val arr = JSONArray()
        for (s in slices) {
            arr.put(sliceToJson(s))
        }
        val root = JSONObject()
        root.put("slices", arr)
        root.put("lastSeenPeriodStart", lastSeenPeriodStart)
        pendingHardReset?.let { p ->
            val po = JSONObject()
            po.put("dayKey", p.dayKey)
            po.put("previousUsed", p.previousUsed)
            po.put("prePeriodStart", p.prePeriodStart)
            po.put("postPeriodStart", p.postPeriodStart)
            po.put("atEpochMs", p.atEpochMs)
            po.put("previousProductLast", JSONObject(p.previousProductLast as Map<*, *>))
            root.put("pendingHardReset", po)
        }
        return root
    }

    private fun sliceToJson(s: DaySlice): JSONObject {
        val o = JSONObject()
        o.put("dayKey", s.dayKey)
        o.put("periodStart", s.periodStart)
        o.put("lastUsedPercent", s.lastUsedPercent)
        o.put("firstUsedPercent", s.firstUsedPercent)
        o.put("dayDelta", s.dayDelta)
        o.put("isPreResetFragment", s.isPreResetFragment)
        o.put("frozen", s.frozen)
        o.put("productLast", JSONObject(s.productLast as Map<*, *>))
        o.put("productDelta", JSONObject(s.productDelta as Map<*, *>))
        return o
    }

    /**
     * Cross-platform export (macOS + Android).
     * Past days: full history.
     * Today: only frozen / pre-reset fragments (mid-week hard reset black bars) so the
     * other device can show two bars for today; open "live" today is rebuilt from API.
     */
    fun exportDocument(appVersion: String): String {
        val today = dayKey()
        val arr = JSONArray()
        for (s in slices) {
            if (s.dayKey == today) {
                // Carry mid-week pre-reset half only
                if (s.isPreResetFragment || (s.frozen && s.dayDelta > 0)) {
                    val copy = s.copy(
                        isPreResetFragment = true,
                        frozen = true
                    )
                    arr.put(sliceToJson(copy))
                }
                continue
            }
            arr.put(sliceToJson(s))
        }
        val root = JSONObject()
        root.put("slices", arr)
        root.put("lastSeenPeriodStart", lastSeenPeriodStartExcludingDay(today))
        root.put("format", EXPORT_FORMAT)
        root.put("formatVersion", EXPORT_FORMAT_VERSION)
        root.put("exportedAt", isoNow())
        root.put("exportedFrom", "android")
        root.put("appVersion", appVersion)
        return root.toString(2)
    }

    /**
     * Merge imported history into the local store.
     * - Past days: merge as usual.
     * - Today pre-reset fragments from the file are kept (black ·prev bars).
     * - Today open/live bars stay local (or rebuild from next refresh).
     * @return number of slices added or updated
     */
    @Synchronized
    fun importDocument(jsonText: String): Int {
        val root = JSONObject(jsonText.trim())
        val arr = root.optJSONArray("slices")
            ?: throw IllegalArgumentException("Not a GRLD history file (missing slices)")
        if (root.has("format")) {
            val fmt = root.optString("format")
            if (fmt.isNotEmpty() && fmt != EXPORT_FORMAT) {
                throw IllegalArgumentException("Unsupported format: $fmt")
            }
        }
        val today = dayKey()
        var changed = 0
        val byKey = LinkedHashMap<String, DaySlice>()

        // Keep local history + today's live (non pre-reset) bars
        for (s in slices) {
            if (s.dayKey == today && (s.isPreResetFragment || s.frozen)) {
                // Local pre-reset may be replaced by a richer import; keep for now
                byKey[sliceKey(s)] = s
            } else if (s.dayKey == today) {
                // Live open post-reset (or normal today) — preserve
                byKey[sliceKey(s)] = s
            } else {
                byKey[sliceKey(s)] = s
            }
        }

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            var incoming = daySliceFromJson(o) ?: continue
            if (incoming.dayKey == today) {
                // Only accept pre-reset / frozen mid-week fragments for today
                if (!incoming.isPreResetFragment && !incoming.frozen) continue
                incoming = incoming.copy(
                    isPreResetFragment = true,
                    frozen = true
                )
            }
            val key = sliceKey(incoming)
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = incoming
                changed++
            } else {
                val merged = mergeSlices(existing, incoming)
                // Prefer pre-reset flags when either side is pre-reset
                if (incoming.isPreResetFragment || existing.isPreResetFragment) {
                    merged.isPreResetFragment = true
                    merged.frozen = true
                }
                if (!merged.sameContent(existing) ||
                    merged.isPreResetFragment != existing.isPreResetFragment
                ) {
                    byKey[key] = merged
                    changed++
                }
            }
        }

        slices = byKey.values.sortedWith(
            compareBy<DaySlice> { it.dayKey }
                .thenBy { it.periodStart }
                .thenBy { it.isPreResetFragment }
        ).toMutableList()

        val importedLast = root.optString("lastSeenPeriodStart", null)
        lastSeenPeriodStart = when {
            !importedLast.isNullOrBlank() -> importedLast
            else -> lastSeenPeriodStartExcludingDay(today)
        }
        if (lastSeenPeriodStart.isNullOrBlank()) {
            lastSeenPeriodStart = slices.maxOfOrNull { it.periodStart }
        }
        // Prefer live API period if we still have an open today post-reset bar
        val openToday = slices.filter {
            it.dayKey == today && !it.frozen && !it.isPreResetFragment
        }
        if (openToday.isNotEmpty()) {
            lastSeenPeriodStart = basePeriodStart(openToday.maxOf { it.periodStart })
                .ifBlank { lastSeenPeriodStart }
        }
        sanitizeFalseResetFragments()
        save()
        return changed
    }

    /** Most recent periodStart among slices not on [excludeDay] (or all if null). */
    private fun lastSeenPeriodStartExcludingDay(excludeDay: String?): String? {
        val candidates = slices.filter { excludeDay == null || it.dayKey != excludeDay }
        if (candidates.isEmpty()) return null
        return candidates.maxOfOrNull { it.periodStart }
    }

    private fun daySliceFromJson(o: JSONObject): DaySlice? {
        val dayKey = o.optString("dayKey").ifBlank { return null }
        val periodStart = o.optString("periodStart").ifBlank { return null }
        val pl = mutableMapOf<String, Int>()
        val plObj = o.optJSONObject("productLast")
        plObj?.keys()?.forEach { k -> pl[k] = plObj.optInt(k) }
        val pd = mutableMapOf<String, Int>()
        val pdObj = o.optJSONObject("productDelta")
        pdObj?.keys()?.forEach { k -> pd[k] = pdObj.optInt(k) }
        return DaySlice(
            dayKey = dayKey,
            periodStart = periodStart,
            lastUsedPercent = o.optInt("lastUsedPercent"),
            firstUsedPercent = o.optInt("firstUsedPercent"),
            dayDelta = o.optInt("dayDelta"),
            isPreResetFragment = o.optBoolean("isPreResetFragment"),
            frozen = o.optBoolean("frozen"),
            productLast = pl,
            productDelta = pd
        )
    }

    private fun sliceKey(s: DaySlice): String =
        "${s.dayKey}|${periodKey(s.periodStart)}|${s.isPreResetFragment}"

    private fun mergeSlices(a: DaySlice, b: DaySlice): DaySlice {
        // Prefer higher cumulative used%; merge product maps with max values.
        // Always take max dayDelta so imported daily bars aren't zeroed by a live sample.
        val productLast = a.productLast.toMutableMap()
        for ((k, v) in b.productLast) {
            productLast[k] = max(productLast[k] ?: 0, v)
        }
        val productDelta = a.productDelta.toMutableMap()
        for ((k, v) in b.productDelta) {
            productDelta[k] = max(productDelta[k] ?: 0, v)
        }
        // If one side has deltas and the other doesn't, prefer the side with more product detail
        // but never drop dayDelta.
        return DaySlice(
            dayKey = a.dayKey,
            periodStart = a.periodStart,
            lastUsedPercent = max(a.lastUsedPercent, b.lastUsedPercent),
            firstUsedPercent = min(a.firstUsedPercent, b.firstUsedPercent),
            dayDelta = max(a.dayDelta, b.dayDelta),
            isPreResetFragment = a.isPreResetFragment,
            frozen = a.frozen || b.frozen,
            productLast = productLast,
            productDelta = productDelta
        )
    }

    private fun DaySlice.sameContent(other: DaySlice): Boolean =
        lastUsedPercent == other.lastUsedPercent &&
            firstUsedPercent == other.firstUsedPercent &&
            dayDelta == other.dayDelta &&
            frozen == other.frozen &&
            productLast == other.productLast &&
            productDelta == other.productDelta

    companion object {
        const val EXPORT_FORMAT = "grld-history"
        const val EXPORT_FORMAT_VERSION = 1
        const val EXPORT_MIME = "application/json"
        const val EXPORT_FILENAME = "grld-history.json"

        /** Grace window: if used% recovers, treat plunge as API glitch. */
        private const val HARD_RESET_GRACE_MS = 5 * 60 * 1000L
        private const val HARD_RESET_MIN_PREV_USED = 5
        private const val HARD_RESET_MAX_POST_USED = 2
        private const val HARD_RESET_MIN_DROP = 5

        @Volatile
        private var instance: DailyHistoryStore? = null

        fun get(context: Context): DailyHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: DailyHistoryStore(context.applicationContext).also { instance = it }
            }
        }

        fun isoNow(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }

        fun dayKey(date: Date = Date()): String {
            val c = Calendar.getInstance()
            c.time = date
            return String.format(
                Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
            )
        }

        fun addDays(key: String, delta: Int): String? {
            val d = parseDayKey(key) ?: return null
            val c = Calendar.getInstance()
            c.time = d
            c.add(Calendar.DAY_OF_MONTH, delta)
            return dayKey(c.time)
        }

        fun parseDayKey(key: String): Date? {
            return try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)
            } catch (_: Exception) {
                null
            }
        }

        fun weekdayShort(key: String): String {
            val d = parseDayKey(key) ?: return "—"
            return SimpleDateFormat("EEE", Locale.US).format(d)
        }

        fun dayOfMonth(key: String): String {
            val parts = key.split("-")
            if (parts.size != 3) return ""
            return parts[2].toIntOrNull()?.toString() ?: ""
        }
    }
}
