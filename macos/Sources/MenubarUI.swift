import AppKit
import Foundation
import ServiceManagement
import UniformTypeIdentifiers

// MARK: - Models

struct ProductUsage: Decodable {
    let product: Int
    let name: String
    let usagePercent: Int
}

struct UsagePeriod: Decodable {
    let type: String?
    let start: String?
    let end: String?
}

struct UsageResponse: Decodable {
    var ok: Bool
    var remainingPercent: Int?
    var usedPercent: Int?
    var weeklyUsageAvailable: Bool
    var tierName: String?
    var currentPeriod: UsagePeriod?
    var productUsage: [ProductUsage]
    var fetchedAt: String?
    var cached: Bool?
}

// MARK: - Daily history

/// One slice of daily usage within a single SuperGrok weekly period.
/// A calendar day that straddles a weekly reset gets **two** slices
/// (pre-reset period + post-reset period).
struct DaySlice: Codable, Equatable {
    var dayKey: String
    var periodStart: String
    var lastUsedPercent: Int
    var firstUsedPercent: Int
    /// Percent of the weekly quota consumed during this slice (0–100).
    var dayDelta: Int
    /// True when this slice was closed because a mid-day weekly reset started a new period.
    var isPreResetFragment: Bool
    var frozen: Bool
    /// Cumulative product used% at last sample (keys are product ids as strings: "1","2","4"…).
    var productLast: [String: Int]
    /// Product contribution to this day's usage (day-over-day product cumulative delta).
    var productDelta: [String: Int]

    var id: String { "\(dayKey)|\(periodStart)" }

    enum CodingKeys: String, CodingKey {
        case dayKey, periodStart, lastUsedPercent, firstUsedPercent
        case dayDelta, isPreResetFragment, frozen, productLast, productDelta
    }

    init(
        dayKey: String,
        periodStart: String,
        lastUsedPercent: Int,
        firstUsedPercent: Int,
        dayDelta: Int,
        isPreResetFragment: Bool,
        frozen: Bool,
        productLast: [String: Int] = [:],
        productDelta: [String: Int] = [:]
    ) {
        self.dayKey = dayKey
        self.periodStart = periodStart
        self.lastUsedPercent = lastUsedPercent
        self.firstUsedPercent = firstUsedPercent
        self.dayDelta = dayDelta
        self.isPreResetFragment = isPreResetFragment
        self.frozen = frozen
        self.productLast = productLast
        self.productDelta = productDelta
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        dayKey = try c.decode(String.self, forKey: .dayKey)
        periodStart = try c.decode(String.self, forKey: .periodStart)
        lastUsedPercent = try c.decode(Int.self, forKey: .lastUsedPercent)
        firstUsedPercent = try c.decode(Int.self, forKey: .firstUsedPercent)
        dayDelta = try c.decode(Int.self, forKey: .dayDelta)
        isPreResetFragment = try c.decode(Bool.self, forKey: .isPreResetFragment)
        frozen = try c.decode(Bool.self, forKey: .frozen)
        productLast = try c.decodeIfPresent([String: Int].self, forKey: .productLast) ?? [:]
        productDelta = try c.decodeIfPresent([String: Int].self, forKey: .productDelta) ?? [:]
    }
}

/// One product segment inside a daily bar (product id → day delta %).
struct ProductSegment: Equatable {
    var product: Int
    var delta: Int
}

/// A single vertical bar in the week chart.
struct ChartBar: Equatable {
    var dayKey: String
    var weekdayLabel: String
    var dayDelta: Int
    var isPreResetFragment: Bool
    var periodStart: String
    var segments: [ProductSegment]
}

final class DailyHistoryStore {
    private(set) var slices: [DaySlice] = []
    private var lastSeenPeriodStart: String?
    /// Pending mid-period hard reset (usage → ~0 without periodStart change).
    /// If usage recovers within [hardResetGraceSec], treat as API glitch and merge back.
    private var pendingHardReset: PendingHardReset?

    /// Grace window: if used% climbs back toward the pre-reset high-water mark, undo the split.
    private static let hardResetGraceSec: TimeInterval = 5 * 60
    /// Min pre-reset used% and min drop to treat a plunge as a hard reset (not jitter).
    private static let hardResetMinPrevUsed = 5
    private static let hardResetMaxPostUsed = 2
    private static let hardResetMinDrop = 5

    private struct PendingHardReset: Codable, Equatable {
        var dayKey: String
        var previousUsed: Int
        var previousProductLast: [String: Int]
        var prePeriodStart: String
        var postPeriodStart: String
        var atEpoch: TimeInterval
    }

    private static var fileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = base.appendingPathComponent("GrokUsageMenubarStandalone", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("daily_history.json")
    }

    init() {
        load()
    }

    func load() {
        guard let data = try? Data(contentsOf: Self.fileURL),
              let decoded = try? JSONDecoder().decode(Persisted.self, from: data)
        else { return }
        slices = decoded.slices
        lastSeenPeriodStart = decoded.lastSeenPeriodStart
        pendingHardReset = decoded.pendingHardReset
        // Fix false double bars (e.g. two black Sundays from a usage dip)
        if sanitizeFalseResetFragments() {
            save()
        }
    }

    func save() {
        let payload = Persisted(
            slices: slices,
            lastSeenPeriodStart: lastSeenPeriodStart,
            pendingHardReset: pendingHardReset
        )
        if let data = try? JSONEncoder().encode(payload) {
            try? data.write(to: Self.fileURL, options: .atomic)
        }
    }

    private struct Persisted: Codable {
        var slices: [DaySlice]
        var lastSeenPeriodStart: String?
        var pendingHardReset: PendingHardReset?
    }

    // MARK: Cross-platform import / export (shared with Android)

    static let exportFormat = "grld-history"
    static let exportFormatVersion = 1
    static let exportFilename = "grld-history.json"

    /// Portable JSON for Mac ↔ Android history transfer.
    /// Past days in full. Today: only frozen / pre-reset fragments (mid-week hard reset)
    /// so the other device can show two bars; open live today is rebuilt from the API.
    func exportDocument(appVersion: String) throws -> Data {
        let today = Self.dayKey()
        let exported = slices.compactMap { s -> DaySlice? in
            if s.dayKey != today { return s }
            guard s.isPreResetFragment || (s.frozen && s.dayDelta > 0) else { return nil }
            var copy = s
            copy.isPreResetFragment = true
            copy.frozen = true
            return copy
        }
        var root: [String: Any] = [
            "format": Self.exportFormat,
            "formatVersion": Self.exportFormatVersion,
            "exportedAt": ISO8601DateFormatter().string(from: Date()),
            "exportedFrom": "macos",
            "appVersion": appVersion,
            "slices": exported.map { sliceDict($0) }
        ]
        if let cursor = lastSeenPeriodStartExcludingDay(today) {
            root["lastSeenPeriodStart"] = cursor
        }
        return try JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
    }

    /// Merge imported history. Today pre-reset fragments are kept; open live today stays local.
    @discardableResult
    func importDocument(data: Data) throws -> Int {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = root["slices"] as? [[String: Any]]
        else {
            throw NSError(domain: "DailyHistoryStore", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Not a GRLD history file (missing slices)"
            ])
        }
        if let fmt = root["format"] as? String, fmt != Self.exportFormat {
            throw NSError(domain: "DailyHistoryStore", code: 2, userInfo: [
                NSLocalizedDescriptionKey: "Unsupported format: \(fmt)"
            ])
        }

        let today = Self.dayKey()
        var byKey: [String: DaySlice] = [:]
        var changed = 0
        // Keep all local slices (including live today + any local pre-reset)
        for s in slices {
            byKey[sliceKey(s)] = s
        }

        for o in arr {
            guard var incoming = sliceFromDict(o) else { continue }
            if incoming.dayKey == today {
                // Only accept mid-week pre-reset fragments for today
                guard incoming.isPreResetFragment || incoming.frozen else { continue }
                incoming.isPreResetFragment = true
                incoming.frozen = true
            }
            let key = sliceKey(incoming)
            if let existing = byKey[key] {
                var merged = Self.mergeSlices(existing, incoming)
                if incoming.isPreResetFragment || existing.isPreResetFragment {
                    merged.isPreResetFragment = true
                    merged.frozen = true
                }
                if merged != existing {
                    byKey[key] = merged
                    changed += 1
                }
            } else {
                byKey[key] = incoming
                changed += 1
            }
        }

        slices = byKey.values.sorted {
            if $0.dayKey != $1.dayKey { return $0.dayKey < $1.dayKey }
            if $0.periodStart != $1.periodStart { return $0.periodStart < $1.periodStart }
            return (!$0.isPreResetFragment && $1.isPreResetFragment)
        }
        if let importedLast = root["lastSeenPeriodStart"] as? String, !importedLast.isEmpty {
            lastSeenPeriodStart = importedLast
        } else {
            lastSeenPeriodStart = lastSeenPeriodStartExcludingDay(today)
        }
        if lastSeenPeriodStart == nil || lastSeenPeriodStart?.isEmpty == true {
            lastSeenPeriodStart = slices.map(\.periodStart).max()
        }
        // Prefer live open period for today when present
        if let open = slices.first(where: {
            $0.dayKey == today && !$0.frozen && !$0.isPreResetFragment
        }) {
            lastSeenPeriodStart = Self.basePeriodStart(open.periodStart)
        }
        _ = sanitizeFalseResetFragments()
        save()
        return changed
    }

    private func lastSeenPeriodStartExcludingDay(_ excludeDay: String?) -> String? {
        let candidates = slices.filter { excludeDay == nil || $0.dayKey != excludeDay }
        return candidates.map(\.periodStart).max()
    }

    /**
     * Collapse false “reset” doubles: only a real weekly period change may put two
     * bars on one calendar day. Same-period pre-reset (from API usage dips) is removed.
     * @return true if anything changed
     */
    @discardableResult
    func sanitizeFalseResetFragments() -> Bool {
        let today = Self.dayKey()
        let grouped = Dictionary(grouping: slices) { $0.dayKey }
        var next: [DaySlice] = []
        var changed = false

        for day in grouped.keys.sorted() {
            let daySlices = grouped[day] ?? []
            let byPeriod = Dictionary(grouping: daySlices) { Self.periodKey($0.periodStart) }
            let periodKeys = byPeriod.keys.sorted()

            if periodKeys.count <= 1 {
                guard let only = periodKeys.first, let group = byPeriod[only], !group.isEmpty else { continue }
                // ONLY explicit isPreResetFragment is black ·prev — not every frozen day
                let preGroup = group.filter(\.isPreResetFragment)
                let restGroup = group.filter { !$0.isPreResetFragment }
                if !preGroup.isEmpty && !restGroup.isEmpty {
                    if group.count > 2 { changed = true }
                    var pre = Self.mergeSlicesList(preGroup)
                    pre.isPreResetFragment = true
                    pre.frozen = true
                    var rest = Self.mergeSlicesList(restGroup)
                    rest.isPreResetFragment = false
                    next.append(pre)
                    next.append(rest)
                } else if !preGroup.isEmpty {
                    if group.count > 1 { changed = true }
                    var pre = Self.mergeSlicesList(preGroup)
                    pre.isPreResetFragment = true
                    pre.frozen = true
                    next.append(pre)
                } else {
                    if group.count > 1 { changed = true }
                    var merged = Self.mergeSlicesList(restGroup)
                    merged.isPreResetFragment = false
                    if day == today && restGroup.contains(where: { !$0.frozen }) {
                        merged.frozen = false
                    }
                    next.append(merged)
                }
            } else {
                // Real mid-day reset: older periods = ·prev, newest = current week
                for (i, pk) in periodKeys.enumerated() {
                    guard let group = byPeriod[pk], !group.isEmpty else { continue }
                    var merged = Self.mergeSlicesList(group)
                    let isPrev = i < periodKeys.count - 1
                    if merged.isPreResetFragment != isPrev { changed = true }
                    merged.isPreResetFragment = isPrev
                    if isPrev {
                        merged.frozen = true
                    } else if day == today {
                        merged.frozen = false
                    }
                    if group.count > 1 { changed = true }
                    next.append(merged)
                }
            }
        }

        next.sort {
            if $0.dayKey != $1.dayKey { return $0.dayKey < $1.dayKey }
            if $0.periodStart != $1.periodStart { return $0.periodStart < $1.periodStart }
            return $0.isPreResetFragment && !$1.isPreResetFragment
        }
        if next.count != slices.count { changed = true }
        if changed {
            slices = next
        }
        return changed
    }

    private static func mergeSlicesList(_ list: [DaySlice]) -> DaySlice {
        guard var acc = list.first else {
            return DaySlice(
                dayKey: "", periodStart: "", lastUsedPercent: 0, firstUsedPercent: 0,
                dayDelta: 0, isPreResetFragment: false, frozen: false
            )
        }
        for other in list.dropFirst() {
            acc = mergeSlices(acc, other)
        }
        return acc
    }

    private func sliceKey(_ s: DaySlice) -> String {
        "\(s.dayKey)|\(Self.periodKey(s.periodStart))|\(s.isPreResetFragment)"
    }

    private func sliceDict(_ s: DaySlice) -> [String: Any] {
        [
            "dayKey": s.dayKey,
            "periodStart": s.periodStart,
            "lastUsedPercent": s.lastUsedPercent,
            "firstUsedPercent": s.firstUsedPercent,
            "dayDelta": s.dayDelta,
            "isPreResetFragment": s.isPreResetFragment,
            "frozen": s.frozen,
            "productLast": s.productLast,
            "productDelta": s.productDelta
        ]
    }

    private func sliceFromDict(_ o: [String: Any]) -> DaySlice? {
        guard let dayKey = o["dayKey"] as? String, !dayKey.isEmpty,
              let periodStart = o["periodStart"] as? String, !periodStart.isEmpty
        else { return nil }
        let productLast = Self.intStringMap(o["productLast"])
        let productDelta = Self.intStringMap(o["productDelta"])
        return DaySlice(
            dayKey: dayKey,
            periodStart: periodStart,
            lastUsedPercent: o["lastUsedPercent"] as? Int ?? 0,
            firstUsedPercent: o["firstUsedPercent"] as? Int ?? 0,
            dayDelta: o["dayDelta"] as? Int ?? 0,
            isPreResetFragment: o["isPreResetFragment"] as? Bool ?? false,
            frozen: o["frozen"] as? Bool ?? false,
            productLast: productLast,
            productDelta: productDelta
        )
    }

    private static func mergeSlices(_ a: DaySlice, _ b: DaySlice) -> DaySlice {
        let preferB = b.lastUsedPercent > a.lastUsedPercent
        var base = preferB ? b : a
        let other = preferB ? a : b
        var productLast = base.productLast
        for (k, v) in other.productLast {
            productLast[k] = max(productLast[k] ?? 0, v)
        }
        var productDelta = base.productDelta
        for (k, v) in other.productDelta {
            productDelta[k] = max(productDelta[k] ?? 0, v)
        }
        base.firstUsedPercent = min(a.firstUsedPercent, b.firstUsedPercent)
        base.lastUsedPercent = max(a.lastUsedPercent, b.lastUsedPercent)
        base.dayDelta = max(a.dayDelta, b.dayDelta)
        base.frozen = a.frozen || b.frozen
        base.productLast = productLast
        base.productDelta = productDelta
        return base
    }

    private static func intStringMap(_ any: Any?) -> [String: Int] {
        guard let d = any as? [String: Any] else { return [:] }
        var out: [String: Int] = [:]
        for (k, v) in d {
            if let i = v as? Int {
                out[k] = i
            } else if let n = v as? NSNumber {
                out[k] = n.intValue
            }
        }
        return out
    }

    static func dayKey(_ date: Date = Date()) -> String {
        let cal = Calendar.current
        let c = cal.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func parseDayKey(_ key: String) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        var c = DateComponents()
        c.year = parts[0]; c.month = parts[1]; c.day = parts[2]; c.hour = 12
        return Calendar.current.date(from: c)
    }

    static func addDays(_ key: String, _ delta: Int) -> String? {
        guard let d = parseDayKey(key),
              let next = Calendar.current.date(byAdding: .day, value: delta, to: d)
        else { return nil }
        return dayKey(next)
    }

    static func weekdayShort(_ key: String) -> String {
        guard let d = parseDayKey(key) else { return "—" }
        let f = DateFormatter()
        f.dateFormat = "EEE"
        return f.string(from: d)
    }

    /// Calendar day number for a dayKey, e.g. "11".
    static func dayOfMonth(_ key: String) -> String {
        let parts = key.split(separator: "-")
        guard parts.count == 3, let day = Int(parts[2]) else { return "" }
        return String(day)
    }

    /// Normalize period start so `…40.000Z` and `…40.104Z` match (API vs scrapers).
    /// Synthetic hard-reset posts (`…|reset|<epoch>`) keep a full unique key so the same
    /// calendar day can show two bars without the API periodStart changing.
    static func periodKey(_ raw: String) -> String {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.contains("|reset|") {
            return s
        }
        // Drop fractional seconds; keep up to the minute so tiny clock skew still matches.
        // "2026-07-09T21:25:40.104Z" → "2026-07-09T21:25"
        if s.count >= 16 {
            return String(s.prefix(16)) // YYYY-MM-DDTHH:MM
        }
        return s
    }

    /// API period embedded in a synthetic `|reset|` post key (or the key itself).
    static func basePeriodStart(_ raw: String) -> String {
        if let r = raw.range(of: "|reset|") {
            return String(raw[..<r.lowerBound])
        }
        return raw
    }

    private func samePeriod(_ a: String, _ b: String) -> Bool {
        Self.periodKey(a) == Self.periodKey(b)
    }

    /// True if `slicePeriod` is a hard-reset post bar for the live API `periodStart`.
    private func isHardResetPost(_ slicePeriod: String, of apiPeriod: String) -> Bool {
        guard slicePeriod.contains("|reset|") else { return false }
        return samePeriod(Self.basePeriodStart(slicePeriod), apiPeriod)
    }

    /// Index of the open (unfrozen) slice for today that should receive this sample.
    private func openSliceIndex(day: String, periodStart: String) -> Int? {
        if let i = slices.firstIndex(where: {
            $0.dayKey == day && samePeriod($0.periodStart, periodStart) && !$0.frozen
        }) {
            return i
        }
        // After mid-period hard reset: post bar uses synthetic `|reset|` period key.
        if let i = slices.firstIndex(where: {
            $0.dayKey == day && !$0.frozen && isHardResetPost($0.periodStart, of: periodStart)
        }) {
            return i
        }
        return nil
    }

    /// Record a live SuperGrok used% sample (cumulative weekly used + per-product cumulatives).
    func record(
        usedPercent: Int,
        periodStart: String?,
        productUsage: [ProductUsage] = [],
        at: Date = Date()
    ) {
        guard let periodStart, !periodStart.isEmpty else { return }
        let used = max(0, min(100, usedPercent))
        let day = Self.dayKey(at)
        let productLast = Self.productMap(from: productUsage)
        let nowEpoch = at.timeIntervalSince1970

        // Resolve pending mid-period hard reset (false-positive window).
        if let pending = pendingHardReset {
            if pending.dayKey != day {
                // New calendar day — confirm any prior split.
                pendingHardReset = nil
            } else {
                let age = nowEpoch - pending.atEpoch
                let recovered = used >= max(pending.previousUsed - 3, Self.hardResetMinPrevUsed)
                if recovered && age < Self.hardResetGraceSec {
                    undoHardReset(pending, used: used, productLast: productLast)
                    lastSeenPeriodStart = periodStart
                    _ = sanitizeFalseResetFragments()
                    save()
                    return
                }
                if age >= Self.hardResetGraceSec {
                    // Confirmed real hard reset (usage stayed low).
                    pendingHardReset = nil
                }
            }
        }

        // Real weekly periodStart change → freeze open slices for the previous period.
        // Compare normalized keys so millisecond differences are not false resets.
        if let prev = lastSeenPeriodStart {
            let prevBase = Self.basePeriodStart(prev)
            if !samePeriod(prevBase, periodStart) && !samePeriod(prev, periodStart) {
                freezePeriod(prev, onDay: day, asPreReset: true)
                // Also freeze any hard-reset post still open under the old period.
                freezeHardResetPosts(of: prevBase, onDay: day)
                pendingHardReset = nil
            }
        }

        if let idx = openSliceIndex(day: day, periodStart: periodStart) {
            var s = slices[idx]
            // Mid-period hard reset: usage plunges to ~0 while periodStart is unchanged
            // (SpaceXAI quota wipe mid-week). Split into black ·prev + new bar.
            // Skip if this slice is already a hard-reset post (low water is normal).
            let alreadyPost = isHardResetPost(s.periodStart, of: periodStart)
            if !alreadyPost &&
                s.lastUsedPercent >= Self.hardResetMinPrevUsed &&
                used <= Self.hardResetMaxPostUsed &&
                (s.lastUsedPercent - used) >= Self.hardResetMinDrop {
                applyHardReset(
                    atIndex: idx,
                    day: day,
                    apiPeriodStart: periodStart,
                    used: used,
                    productLast: productLast,
                    atEpoch: nowEpoch
                )
            } else {
                // Cumulative used% can jitter down slightly; keep the high-water mark.
                s.lastUsedPercent = max(s.lastUsedPercent, used)
                for (k, v) in productLast {
                    s.productLast[k] = max(s.productLast[k] ?? 0, v)
                }
                s.dayDelta = computeDelta(s)
                s.productDelta = computeProductDelta(s)
                s.isPreResetFragment = false
                slices[idx] = s
            }
        } else {
            // Drop any stale same-period frozen ·prev for today before appending
            // (only when there is no open post and no confirmed dual-period day).
            let hasConfirmedSplit = slices.contains {
                $0.dayKey == day && $0.isPreResetFragment
            }
            if !hasConfirmedSplit {
                slices.removeAll {
                    $0.dayKey == day && samePeriod($0.periodStart, periodStart) && $0.isPreResetFragment
                }
            }
            // After mid-week wipe, open bar uses synthetic |reset| key + zero baseline
            let openPeriod: String
            if hasConfirmedSplit {
                openPeriod = slices.first(where: {
                    $0.dayKey == day && isHardResetPost($0.periodStart, of: periodStart)
                })?.periodStart ?? "\(periodStart)|reset|\(Int(nowEpoch))"
            } else {
                openPeriod = periodStart
            }
            appendOpen(day: day, periodStart: openPeriod, used: used, productLast: productLast)
        }

        lastSeenPeriodStart = periodStart
        _ = sanitizeFalseResetFragments()
        save()
    }

    /// Freeze today's open slice as black ·prev and open a synthetic post-reset bar.
    private func applyHardReset(
        atIndex idx: Int,
        day: String,
        apiPeriodStart: String,
        used: Int,
        productLast: [String: Int],
        atEpoch: TimeInterval
    ) {
        var pre = slices[idx]
        let prevUsed = pre.lastUsedPercent
        let prevProducts = pre.productLast
        pre.frozen = true
        pre.isPreResetFragment = true
        pre.dayDelta = computeDelta(pre)
        pre.productDelta = computeProductDelta(pre)
        slices[idx] = pre

        let postPeriod = "\(apiPeriodStart)|reset|\(Int(atEpoch))"
        pendingHardReset = PendingHardReset(
            dayKey: day,
            previousUsed: prevUsed,
            previousProductLast: prevProducts,
            prePeriodStart: pre.periodStart,
            postPeriodStart: postPeriod,
            atEpoch: atEpoch
        )
        // Post bar baseline is 0 (new quota) — synthetic period has no prior calendar day.
        appendOpen(day: day, periodStart: postPeriod, used: used, productLast: productLast)
    }

    /// Usage recovered within the grace window — merge post back into pre (API glitch).
    private func undoHardReset(
        _ pending: PendingHardReset,
        used: Int,
        productLast: [String: Int]
    ) {
        // Remove synthetic post bar(s)
        slices.removeAll {
            $0.dayKey == pending.dayKey &&
                ($0.periodStart == pending.postPeriodStart ||
                 isHardResetPost($0.periodStart, of: Self.basePeriodStart(pending.prePeriodStart)))
        }
        // Restore pre slice as the single open bar for today
        if let i = slices.firstIndex(where: {
            $0.dayKey == pending.dayKey && samePeriod($0.periodStart, pending.prePeriodStart)
        }) {
            var s = slices[i]
            s.frozen = false
            s.isPreResetFragment = false
            s.lastUsedPercent = max(s.lastUsedPercent, max(used, pending.previousUsed))
            for (k, v) in pending.previousProductLast {
                s.productLast[k] = max(s.productLast[k] ?? 0, v)
            }
            for (k, v) in productLast {
                s.productLast[k] = max(s.productLast[k] ?? 0, v)
            }
            s.dayDelta = computeDelta(s)
            s.productDelta = computeProductDelta(s)
            slices[i] = s
        }
        pendingHardReset = nil
    }

    private func freezeHardResetPosts(of apiPeriod: String, onDay day: String) {
        for i in slices.indices {
            guard slices[i].dayKey == day,
                  !slices[i].frozen,
                  isHardResetPost(slices[i].periodStart, of: apiPeriod)
            else { continue }
            slices[i].frozen = true
            slices[i].isPreResetFragment = false
            slices[i].dayDelta = computeDelta(slices[i])
            slices[i].productDelta = computeProductDelta(slices[i])
        }
    }

    private static func productMap(from products: [ProductUsage]) -> [String: Int] {
        var m: [String: Int] = [:]
        for p in products where p.usagePercent > 0 {
            m[String(p.product)] = max(0, min(100, p.usagePercent))
        }
        return m
    }

    private func appendOpen(day: String, periodStart: String, used: Int, productLast: [String: Int]) {
        var s = DaySlice(
            dayKey: day,
            periodStart: periodStart,
            lastUsedPercent: used,
            firstUsedPercent: used,
            dayDelta: 0,
            isPreResetFragment: false,
            frozen: false,
            productLast: productLast,
            productDelta: [:]
        )
        s.dayDelta = computeDelta(s)
        s.productDelta = computeProductDelta(s)
        slices.append(s)
    }

    private func freezePeriod(_ periodStart: String, onDay day: String, asPreReset: Bool) {
        for i in slices.indices {
            guard samePeriod(slices[i].periodStart, periodStart), !slices[i].frozen else { continue }
            if slices[i].dayKey == day {
                slices[i].isPreResetFragment = asPreReset
            }
            slices[i].frozen = true
            slices[i].dayDelta = computeDelta(slices[i])
            slices[i].productDelta = computeProductDelta(slices[i])
        }
    }

    private func dayHasPreResetFragment(_ day: String) -> Bool {
        slices.contains { $0.dayKey == day && $0.isPreResetFragment }
    }

    /// Open half of a mid-week hard reset (quota restarted at ~0).
    private func isPostHardResetSlice(_ slice: DaySlice) -> Bool {
        if slice.isPreResetFragment { return false }
        if slice.periodStart.contains("|reset|") { return true }
        return dayHasPreResetFragment(slice.dayKey)
    }

    private func previousDaySlice(dayKey: String, periodStart: String) -> DaySlice? {
        guard let prevKey = Self.addDays(dayKey, -1) else { return nil }
        let base = Self.basePeriodStart(periodStart)
        let prev = slices.filter {
            $0.dayKey == prevKey &&
                samePeriod(Self.basePeriodStart($0.periodStart), base) &&
                !$0.isPreResetFragment
        }
        if let best = prev.max(by: { $0.lastUsedPercent < $1.lastUsedPercent }) {
            return best
        }
        return slices
            .filter {
                $0.dayKey == prevKey && samePeriod(Self.basePeriodStart($0.periodStart), base)
            }
            .max(by: { $0.lastUsedPercent < $1.lastUsedPercent })
    }

    private func previousDayLast(dayKey: String, periodStart: String) -> Int {
        previousDaySlice(dayKey: dayKey, periodStart: periodStart)?.lastUsedPercent ?? 0
    }

    /// Day contribution = cumulative used − previous day (same period),
    /// or full used% after a mid-week hard reset (baseline 0).
    private func computeDelta(_ slice: DaySlice) -> Int {
        if isPostHardResetSlice(slice) {
            return max(0, min(100, slice.lastUsedPercent))
        }
        let prevLast = previousDayLast(dayKey: slice.dayKey, periodStart: slice.periodStart)
        return max(0, min(100, slice.lastUsedPercent - prevLast))
    }

    private func computeProductDelta(_ slice: DaySlice) -> [String: Int] {
        let prev: [String: Int] = isPostHardResetSlice(slice)
            ? [:]
            : (previousDaySlice(dayKey: slice.dayKey, periodStart: slice.periodStart)?.productLast ?? [:])
        var out: [String: Int] = [:]
        let keys = Set(slice.productLast.keys).union(prev.keys)
        for k in keys {
            let cur = slice.productLast[k] ?? 0
            let p = prev[k] ?? 0
            let d = max(0, cur - p)
            if d > 0 { out[k] = d }
        }
        return out
    }

    /// Bars for a 7-day window ending on `endDayKey` (inclusive).
    /// Days with two period slices yield two bars (pre-reset then post-reset).
    func bars(endingOn endDayKey: String) -> [ChartBar] {
        var dayKeys: [String] = []
        for i in (0..<7).reversed() {
            if let k = Self.addDays(endDayKey, -i) { dayKeys.append(k) }
        }

        var result: [ChartBar] = []
        for day in dayKeys {
            // Pre-reset (black ·prev) always LEFT of the new post-reset bar
            let daySlices = slices
                .filter { $0.dayKey == day }
                .sorted { a, b in
                    if a.isPreResetFragment != b.isPreResetFragment {
                        return a.isPreResetFragment && !b.isPreResetFragment
                    }
                    return a.periodStart < b.periodStart
                }

            if daySlices.isEmpty {
                result.append(ChartBar(
                    dayKey: day,
                    weekdayLabel: Self.weekdayShort(day),
                    dayDelta: 0,
                    isPreResetFragment: false,
                    periodStart: "",
                    segments: []
                ))
            } else {
                for s in daySlices {
                    var label = Self.weekdayShort(day)
                    if s.isPreResetFragment {
                        label = "\(label)·prev"
                    }
                    // Live recompute so post-reset 1% is not zeroed by yesterday baseline
                    let delta = computeDelta(s)
                    let segs = computeProductDelta(s)
                        .compactMap { key, val -> ProductSegment? in
                            guard let id = Int(key), val > 0 else { return nil }
                            return ProductSegment(product: id, delta: val)
                        }
                        .sorted { $0.product < $1.product }
                    result.append(ChartBar(
                        dayKey: day,
                        weekdayLabel: label,
                        dayDelta: delta,
                        isPreResetFragment: s.isPreResetFragment,
                        periodStart: s.periodStart,
                        segments: segs
                    ))
                }
            }
        }
        return result
    }

    var oldestDayKey: String? {
        slices.map(\.dayKey).min()
    }

    var isEmpty: Bool { slices.isEmpty }
}

// MARK: - Colors (match Chrome extension)

enum ProductColors {
    static func color(for product: Int) -> NSColor {
        switch product {
        case 0: return NSColor(srgbRed: 0.42, green: 0.45, blue: 0.50, alpha: 1)
        case 1: return NSColor(srgbRed: 0.23, green: 0.51, blue: 0.96, alpha: 1)
        case 2: return NSColor(srgbRed: 0.06, green: 0.73, blue: 0.51, alpha: 1)
        case 3: return NSColor(srgbRed: 0.96, green: 0.62, blue: 0.04, alpha: 1)
        case 4: return NSColor(srgbRed: 0.02, green: 0.71, blue: 0.83, alpha: 1)
        case 5: return NSColor(srgbRed: 0.66, green: 0.33, blue: 0.97, alpha: 1)
        case 6: return NSColor(srgbRed: 0.93, green: 0.28, blue: 0.60, alpha: 1)
        case 7: return NSColor(srgbRed: 0.55, green: 0.55, blue: 0.58, alpha: 1) // App Builder
        default: return NSColor(srgbRed: 0.42, green: 0.45, blue: 0.50, alpha: 1)
        }
    }

    static func name(for product: Int) -> String {
        switch product {
        case 0: return "3rd Party"
        case 1: return "API"
        case 2: return "Grok Build"
        case 3: return "Grok Plugins"
        case 4: return "Chat"
        case 5: return "Imagine"
        case 6: return "Voice"
        case 7: return "App Builder"
        default: return "Other"
        }
    }

    /// Before Reset on a mid-day hard reset (not a category color).
    static let preReset = NSColor(srgbRed: 0.26, green: 0.26, blue: 0.26, alpha: 1) // black / dark gray
    static let postReset = NSColor(srgbRed: 0.23, green: 0.51, blue: 0.96, alpha: 1) // blue
    static let normalDay = NSColor(srgbRed: 0.02, green: 0.71, blue: 0.83, alpha: 1) // cyan
}

/// One row in the daily-chart legend (only items visible on at least one bar).
struct ChartLegendItem: Equatable {
    var color: NSColor
    var title: String
    /// Sum of day-deltas in the visible week window (0 for pre-reset / other-period).
    var weekPercent: Int
}

// MARK: - Custom usage panel (menu view)

/// Horizontal product bar that counts ⇧-clicks for secret debug mode.
/// Implemented as NSControl so NSMenu custom views deliver mouse events reliably.
private final class DebugTapBarView: NSControl {
    var onShiftClick: (() -> Void)?

    /// Capture all clicks on the bar (color segments are visual-only).
    override func hitTest(_ point: NSPoint) -> NSView? {
        let local = convert(point, from: superview)
        return bounds.contains(local) ? self : nil
    }

    override func mouseDown(with event: NSEvent) {
        // deviceIndependentFlagsMask: ignore caps-lock / num-lock noise in menu events
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        if flags.contains(.shift) {
            onShiftClick?()
            // brief flash so user knows the click registered
            let old = layer?.opacity ?? 1
            layer?.opacity = 0.55
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) { [weak self] in
                self?.layer?.opacity = old
            }
        }
    }

    override func mouseUp(with event: NSEvent) {
        // Keep menu open; swallow event
    }

    override var acceptsFirstResponder: Bool { true }
}

final class UsagePanelView: NSView {
    /// Must match [MenubarController.menuContentWidth] so the custom panel fills
    /// the full menu (avoids left-heavy / “off-center” look when ⌘ shortcuts
    /// and long titles widen the menu past a narrow fixed panel).
    static let preferredWidth: CGFloat = 340
    private var panelWidth: CGFloat { Self.preferredWidth }
    private let pad: CGFloat = 14
    weak var navTarget: AnyObject?
    var prevSelector: Selector?
    var nextSelector: Selector?
    private var onDebugBarShiftClick: (() -> Void)?

    init(
        usage: UsageResponse,
        chartBars: [ChartBar],
        weekLabel: String,
        canGoPrev: Bool,
        canGoNext: Bool,
        showUsedPercent: Bool,
        formatDate: (String) -> String,
        navTarget: AnyObject?,
        prevSelector: Selector?,
        nextSelector: Selector?,
        debugLines: [String] = [],
        onDebugBarShiftClick: (() -> Void)? = nil
    ) {
        self.navTarget = navTarget
        self.prevSelector = prevSelector
        self.nextSelector = nextSelector
        self.onDebugBarShiftClick = onDebugBarShiftClick
        super.init(frame: .zero)
        wantsLayer = true
        build(
            usage: usage,
            chartBars: chartBars,
            weekLabel: weekLabel,
            canGoPrev: canGoPrev,
            canGoNext: canGoNext,
            showUsedPercent: showUsedPercent,
            formatDate: formatDate,
            debugLines: debugLines
        )
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    private func label(
        _ text: String,
        font: NSFont,
        color: NSColor,
        align: NSTextAlignment = .left
    ) -> NSTextField {
        let f = NSTextField(labelWithString: text)
        f.font = font
        f.textColor = color
        f.alignment = align
        f.lineBreakMode = .byTruncatingTail
        f.drawsBackground = false
        f.isBezeled = false
        f.isEditable = false
        f.isSelectable = false
        return f
    }

    private func build(
        usage: UsageResponse,
        chartBars: [ChartBar],
        weekLabel: String,
        canGoPrev: Bool,
        canGoNext: Bool,
        showUsedPercent: Bool,
        formatDate: (String) -> String,
        debugLines: [String]
    ) {
        let rem = usage.remainingPercent ?? 0
        let used = usage.usedPercent ?? (100 - rem)
        let title: String = {
            if let tier = usage.tierName, !tier.isEmpty {
                return "Weekly \(tier) Limit"
            }
            return "Weekly SuperGrok Limit"
        }()
        // Primary line matches grok.com by default (used % first)
        let summaryText = showUsedPercent
            ? "\(used)% used · \(rem)% remaining"
            : "\(rem)% remaining · \(used)% used"
        let products = usage.productUsage
            .filter { $0.usagePercent > 0 }
            .sorted { $0.usagePercent > $1.usagePercent }
        let barProducts = products.isEmpty && used > 0
            ? [ProductUsage(product: 4, name: "Used", usagePercent: used)]
            : products

        let contentW = panelWidth - pad * 2
        let barH: CGFloat = 12
        let titleH: CGFloat = 18
        let summaryH: CGFloat = 16
        let rowH: CGFloat = 22
        // Extra room for weekday + day-number labels under bars
        let chartH: CGFloat = 108
        let navH: CGFloat = 28
        let gap: CGFloat = 10
        let legendItems = Self.legendItems(from: chartBars)
        let legendRowH: CGFloat = 16
        // 2-column legend
        let legendRows = max(1, (legendItems.count + 1) / 2)
        let legendH: CGFloat = legendItems.isEmpty ? 0 : CGFloat(legendRows) * legendRowH + 4
        let debugLineH: CGFloat = 12
        let debugBlockH: CGFloat = debugLines.isEmpty
            ? 0
            : 8 + 14 + CGFloat(debugLines.count) * debugLineH

        var contentH: CGFloat = titleH + 4 + summaryH + gap + barH + gap
        contentH += barProducts.isEmpty ? 16 : CGFloat(barProducts.count) * rowH
        contentH += gap + 14 + 4 + navH + 4 + chartH
        if !legendItems.isEmpty {
            contentH += 6 + legendH
        }
        if usage.currentPeriod?.end != nil { contentH += gap + 14 }
        if usage.cached == true { contentH += 12 }
        contentH += debugBlockH

        let totalH = pad + contentH + pad
        frame = NSRect(x: 0, y: 0, width: panelWidth, height: totalH)
        var top = totalH - pad

        let titleL = label(title, font: .systemFont(ofSize: 13, weight: .semibold), color: .labelColor)
        top -= titleH
        titleL.frame = NSRect(x: pad, y: top, width: contentW, height: titleH)
        addSubview(titleL)

        top -= 4
        let summaryL = label(
            summaryText,
            font: .systemFont(ofSize: 12, weight: .regular),
            color: .secondaryLabelColor
        )
        top -= summaryH
        summaryL.frame = NSRect(x: pad, y: top, width: contentW, height: summaryH)
        addSubview(summaryL)

        top -= gap
        top -= barH
        let bar = DebugTapBarView(frame: NSRect(x: pad, y: top, width: contentW, height: barH))
        bar.wantsLayer = true
        bar.layer?.cornerRadius = 5
        bar.layer?.masksToBounds = true
        bar.layer?.backgroundColor = NSColor.separatorColor.withAlphaComponent(0.4).cgColor
        bar.onShiftClick = { [weak self] in self?.onDebugBarShiftClick?() }
        var x: CGFloat = 0
        for p in barProducts {
            let segW = max(0, contentW * CGFloat(p.usagePercent) / 100.0)
            guard segW >= 0.5 else { continue }
            let seg = NSView(frame: NSRect(x: x, y: 0, width: segW, height: barH))
            seg.wantsLayer = true
            seg.layer?.backgroundColor = ProductColors.color(for: p.product).cgColor
            bar.addSubview(seg)
            x += segW
        }
        addSubview(bar)

        top -= gap
        if barProducts.isEmpty {
            let none = label("No category usage details", font: .systemFont(ofSize: 12), color: .secondaryLabelColor)
            top -= 16
            none.frame = NSRect(x: pad, y: top, width: contentW, height: 16)
            addSubview(none)
        } else {
            for p in barProducts {
                top -= rowH
                let row = makeProductRow(
                    name: p.name,
                    percent: p.usagePercent,
                    color: ProductColors.color(for: p.product),
                    width: contentW
                )
                row.frame.origin = NSPoint(x: pad, y: top)
                addSubview(row)
            }
        }

        // Daily chart section
        top -= gap
        let section = label("Daily use (% of weekly)", font: .systemFont(ofSize: 11, weight: .semibold), color: .labelColor)
        top -= 14
        section.frame = NSRect(x: pad, y: top, width: contentW, height: 14)
        addSubview(section)

        top -= 4
        top -= navH
        let nav = makeNavRow(
            labelText: weekLabel,
            canGoPrev: canGoPrev,
            canGoNext: canGoNext,
            width: contentW,
            height: navH
        )
        nav.frame.origin = NSPoint(x: pad, y: top)
        addSubview(nav)

        top -= 4
        top -= chartH
        let chart = makeChartView(bars: chartBars, width: contentW, height: chartH)
        chart.frame.origin = NSPoint(x: pad, y: top)
        addSubview(chart)

        if !legendItems.isEmpty {
            top -= 6
            top -= legendH
            let legendView = makeChartLegend(items: legendItems, width: contentW, rowHeight: legendRowH)
            legendView.frame.origin = NSPoint(x: pad, y: top)
            addSubview(legendView)
        }

        if let end = usage.currentPeriod?.end {
            top -= gap
            top -= 14
            let reset = label("Resets \(formatDate(end))", font: .systemFont(ofSize: 11), color: .secondaryLabelColor)
            reset.frame = NSRect(x: pad, y: top, width: contentW, height: 14)
            addSubview(reset)
        }

        if usage.cached == true {
            top -= 12
            let cached = label("Cached (≤90s)", font: .systemFont(ofSize: 10), color: .tertiaryLabelColor)
            cached.frame = NSRect(x: pad, y: top, width: contentW, height: 12)
            addSubview(cached)
        }

        if !debugLines.isEmpty {
            top -= 8
            let dbgTitle = label(
                "Debug",
                font: .systemFont(ofSize: 10, weight: .semibold),
                color: .systemOrange
            )
            top -= 14
            dbgTitle.frame = NSRect(x: pad, y: top, width: contentW, height: 14)
            addSubview(dbgTitle)
            for line in debugLines {
                top -= debugLineH
                let row = label(
                    line,
                    font: .monospacedSystemFont(ofSize: 9, weight: .regular),
                    color: .secondaryLabelColor
                )
                row.toolTip = line
                row.frame = NSRect(x: pad, y: top, width: contentW, height: debugLineH)
                addSubview(row)
            }
        }
    }

    private func makeNavRow(labelText: String, canGoPrev: Bool, canGoNext: Bool, width: CGFloat, height: CGFloat) -> NSView {
        let row = NSView(frame: NSRect(x: 0, y: 0, width: width, height: height))

        let prev = NSButton(frame: NSRect(x: 0, y: 2, width: 28, height: 24))
        prev.title = "‹"
        prev.bezelStyle = .roundRect
        prev.isEnabled = canGoPrev
        prev.font = .systemFont(ofSize: 16, weight: .semibold)
        if let navTarget, let prevSelector {
            prev.target = navTarget
            prev.action = prevSelector
        }
        row.addSubview(prev)

        let next = NSButton(frame: NSRect(x: width - 28, y: 2, width: 28, height: 24))
        next.title = "›"
        next.bezelStyle = .roundRect
        next.isEnabled = canGoNext
        next.font = .systemFont(ofSize: 16, weight: .semibold)
        if let navTarget, let nextSelector {
            next.target = navTarget
            next.action = nextSelector
        }
        row.addSubview(next)

        let mid = label(labelText, font: .systemFont(ofSize: 11, weight: .medium), color: .labelColor, align: .center)
        mid.frame = NSRect(x: 32, y: 4, width: width - 64, height: 18)
        row.addSubview(mid)

        return row
    }

    /// Legend entries for anything drawn on the visible week chart.
    /// Products only if they appear as a stack segment; pre-reset black = Before Reset.
    private static func legendItems(from bars: [ChartBar]) -> [ChartLegendItem] {
        var productTotals: [Int: Int] = [:]
        var hasPreReset = false
        for bar in bars {
            // Pre-reset bars are previous week — never counted as current-week categories
            if bar.isPreResetFragment {
                if bar.dayDelta > 0 || !bar.segments.isEmpty {
                    hasPreReset = true
                }
                continue
            }
            for seg in bar.segments where seg.delta > 0 {
                productTotals[seg.product, default: 0] += seg.delta
            }
        }

        var items: [ChartLegendItem] = productTotals
            .sorted { $0.key < $1.key }
            .map { pid, total in
                ChartLegendItem(
                    color: ProductColors.color(for: pid),
                    title: ProductColors.name(for: pid),
                    weekPercent: total
                )
            }

        if hasPreReset {
            items.append(ChartLegendItem(
                color: ProductColors.preReset,
                title: "Before Reset",
                weekPercent: 0
            ))
        }
        return items
    }

    private func makeChartLegend(items: [ChartLegendItem], width: CGFloat, rowHeight: CGFloat) -> NSView {
        let cols = 2
        let colW = width / CGFloat(cols)
        let rows = (items.count + cols - 1) / cols
        let view = NSView(frame: NSRect(x: 0, y: 0, width: width, height: CGFloat(rows) * rowHeight))

        for (i, item) in items.enumerated() {
            let col = i % cols
            let row = i / cols
            // AppKit y grows up; top row is highest
            let y = CGFloat(rows - 1 - row) * rowHeight
            let x = CGFloat(col) * colW

            let dotSize: CGFloat = 8
            let dot = NSView(frame: NSRect(x: x, y: y + (rowHeight - dotSize) / 2, width: dotSize, height: dotSize))
            dot.wantsLayer = true
            dot.layer?.cornerRadius = dotSize / 2
            dot.layer?.backgroundColor = item.color.cgColor
            view.addSubview(dot)

            let lab = label(
                item.title,
                font: .systemFont(ofSize: 10, weight: .regular),
                color: .secondaryLabelColor
            )
            lab.frame = NSRect(x: x + 12, y: y, width: colW - 14, height: rowHeight)
            view.addSubview(lab)
        }
        return view
    }

    private func makeChartView(bars: [ChartBar], width: CGFloat, height: CGFloat) -> NSView {
        let container = NSView(frame: NSRect(x: 0, y: 0, width: width, height: height))
        container.wantsLayer = true

        // Two lines under each bar: weekday name, then calendar day number
        let dayNumH: CGFloat = 11
        let weekdayH: CGFloat = 12
        let labelH = dayNumH + weekdayH
        let valueH: CGFloat = 12
        let chartTopPad: CGFloat = 2
        let barAreaH = height - labelH - valueH - chartTopPad
        let n = max(bars.count, 1)
        let slotW = width / CGFloat(n)
        let barW = min(16, max(7, slotW - 5))

        // Scale by max of headline dayDelta and stacked product sum
        let maxDelta = max(
            bars.map { max($0.dayDelta, $0.segments.reduce(0) { $0 + $1.delta }) }.max() ?? 0,
            1
        )
        let scale = CGFloat(max(maxDelta, 10))

        for (i, bar) in bars.enumerated() {
            let slotX = CGFloat(i) * slotW
            let segSum = bar.segments.reduce(0) { $0 + $1.delta }
            let totalUnits = max(bar.dayDelta, segSum)
            let h = barAreaH * CGFloat(totalUnits) / scale
            let drawH = totalUnits > 0 ? max(4, h) : 0
            let barX = slotX + (slotW - barW) / 2
            let barY = labelH + valueH

            // Track
            let track = NSView(frame: NSRect(x: barX, y: barY, width: barW, height: barAreaH))
            track.wantsLayer = true
            track.layer?.cornerRadius = 3
            track.layer?.backgroundColor = NSColor.separatorColor.withAlphaComponent(0.25).cgColor
            container.addSubview(track)

            if drawH > 0 {
                // Pre-reset = previous SuperGrok week — always solid black (not category colors)
                if bar.isPreResetFragment {
                    let fill = NSView(frame: NSRect(x: barX, y: barY, width: barW, height: drawH))
                    fill.wantsLayer = true
                    fill.layer?.cornerRadius = 3
                    fill.layer?.maskedCorners = [.layerMinXMaxYCorner, .layerMaxXMaxYCorner]
                    fill.layer?.backgroundColor = ProductColors.preReset.cgColor
                    container.addSubview(fill)
                } else if !bar.segments.isEmpty && segSum > 0 {
                    // Stack product colors bottom → top (product id order)
                    var yOff: CGFloat = 0
                    let ordered = bar.segments.sorted { $0.product < $1.product }
                    for (si, seg) in ordered.enumerated() {
                        let frac = CGFloat(seg.delta) / CGFloat(segSum)
                        var segH = drawH * frac
                        // Ensure visible slice when tiny
                        if seg.delta > 0 { segH = max(2, segH) }
                        // Clamp last segment to fill remaining so rounding doesn't leave a gap
                        if si == ordered.count - 1 {
                            segH = max(2, drawH - yOff)
                        }
                        let fill = NSView(frame: NSRect(x: barX, y: barY + yOff, width: barW, height: segH))
                        fill.wantsLayer = true
                        if si == ordered.count - 1 {
                            fill.layer?.cornerRadius = 3
                            fill.layer?.maskedCorners = [.layerMinXMaxYCorner, .layerMaxXMaxYCorner]
                        }
                        fill.layer?.backgroundColor = ProductColors.color(for: seg.product).cgColor
                        container.addSubview(fill)
                        yOff += segH
                        if yOff >= drawH { break }
                    }
                } else {
                    // Fallback solid (no product detail)
                    let fill = NSView(frame: NSRect(x: barX, y: barY, width: barW, height: drawH))
                    fill.wantsLayer = true
                    fill.layer?.cornerRadius = 3
                    fill.layer?.maskedCorners = [.layerMinXMaxYCorner, .layerMaxXMaxYCorner]
                    fill.layer?.backgroundColor = ProductColors.normalDay.cgColor
                    container.addSubview(fill)
                }
            }

            let valNum = bar.dayDelta > 0 ? bar.dayDelta : totalUnits
            let val = label(
                totalUnits > 0 ? "\(valNum)%" : "·",
                font: .monospacedDigitSystemFont(ofSize: 9, weight: .medium),
                color: .secondaryLabelColor,
                align: .center
            )
            val.frame = NSRect(x: slotX, y: labelH, width: slotW, height: valueH)
            container.addSubview(val)

            // Weekday on top of day number (e.g. Sat / 11)
            let dayL = label(
                bar.weekdayLabel,
                font: .systemFont(ofSize: 9, weight: .medium),
                color: .labelColor,
                align: .center
            )
            dayL.frame = NSRect(x: slotX, y: dayNumH, width: slotW, height: weekdayH)
            container.addSubview(dayL)

            let numText = DailyHistoryStore.dayOfMonth(bar.dayKey)
            let numL = label(
                numText,
                font: .monospacedDigitSystemFont(ofSize: 9, weight: .regular),
                color: .secondaryLabelColor,
                align: .center
            )
            numL.frame = NSRect(x: slotX, y: 0, width: slotW, height: dayNumH)
            container.addSubview(numL)
        }

        return container
    }

    private func makeProductRow(name: String, percent: Int, color: NSColor, width: CGFloat) -> NSView {
        let row = NSView(frame: NSRect(x: 0, y: 0, width: width, height: 22))

        let dotSize: CGFloat = 8
        let dot = NSView(frame: NSRect(x: 0, y: 7, width: dotSize, height: dotSize))
        dot.wantsLayer = true
        dot.layer?.cornerRadius = dotSize / 2
        dot.layer?.backgroundColor = color.cgColor
        row.addSubview(dot)

        let nameL = label(name, font: .systemFont(ofSize: 12, weight: .regular), color: .labelColor)
        nameL.frame = NSRect(x: 14, y: 2, width: width - 58, height: 18)
        row.addSubview(nameL)

        let pctL = label(
            "\(percent)%",
            font: .monospacedDigitSystemFont(ofSize: 12, weight: .semibold),
            color: .labelColor,
            align: .right
        )
        pctL.frame = NSRect(x: width - 44, y: 2, width: 44, height: 18)
        row.addSubview(pctL)

        return row
    }
}

// MARK: - Client (direct grok.com — see GrokDirectClient.swift)

// MARK: - App

final class MenubarController: NSObject, NSMenuDelegate {
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let client = GrokDirectClient()
    private let history = DailyHistoryStore()
    private var timer: Timer?
    private var lastUsage: UsageResponse?
    private var lastError: String?
    /// Set when signed in but weekly SuperGrok data is unavailable (free plan).
    private var isFreePlan = false
    private var isRefreshing = false
    private var loginWC: LoginWindowController?
    private var supportNagWC: SupportNagWindowController?
    private var disclaimerWC: DisclaimerWindowController?
    /// 0 = current 7 days ending today; -1 = previous window, etc. Never positive.
    private var weekOffset: Int = 0

    // Secret debug mode: ⇧-click the main usage bar 8 times to toggle
    private var debugMode = false
    private var debugShiftClicks = 0
    private var lastDebugShiftClick: Date?
    private var lastRefreshAt: Date?
    private var lastRefreshOK: Bool?
    private var lastRefreshError: String?
    private var appStartedAt = Date()

    private var showCategories: Bool {
        get { UserDefaults.standard.bool(forKey: "showCategories") }
        set { UserDefaults.standard.set(newValue, forKey: "showCategories") }
    }

    private var showBarInMenuBar: Bool {
        get { UserDefaults.standard.bool(forKey: "showBarInMenuBar") }
        set { UserDefaults.standard.set(newValue, forKey: "showBarInMenuBar") }
    }

    /// Menu bar headline: used % (default, matches grok.com) or remaining %.
    private var showUsedPercent: Bool {
        get {
            if UserDefaults.standard.object(forKey: "showUsedPercent") == nil { return true }
            return UserDefaults.standard.bool(forKey: "showUsedPercent")
        }
        set { UserDefaults.standard.set(newValue, forKey: "showUsedPercent") }
    }

    /// Check GitHub for new releases and install automatically when enabled.
    /// Default OFF so a bad update loop can't silently replace the running app.
    private var autoUpdateEnabled: Bool {
        get {
            if UserDefaults.standard.object(forKey: "autoUpdateEnabled") == nil { return false }
            return UserDefaults.standard.bool(forKey: "autoUpdateEnabled")
        }
        set { UserDefaults.standard.set(newValue, forKey: "autoUpdateEnabled") }
    }

    /// Settings block in the menu: collapsed by default; remembers last choice.
    private var settingsExpanded: Bool {
        get {
            if UserDefaults.standard.object(forKey: "settingsExpanded") == nil { return false }
            return UserDefaults.standard.bool(forKey: "settingsExpanded")
        }
        set { UserDefaults.standard.set(newValue, forKey: "settingsExpanded") }
    }

    private var updateTimer: Timer?
    private var pendingUpdate: AppUpdater.ReleaseInfo?
    private var updateStatus: String?

    func start() {
        if let button = statusItem.button {
            button.imagePosition = .imageLeft
            button.imageScaling = .scaleProportionallyDown
            button.image = loadLogo(remainingPercent: nil)
            button.title = " …"
            button.font = NSFont.monospacedDigitSystemFont(ofSize: 12, weight: .medium)
            button.toolTip = "Grok Rate Limit Display (weekly usage)"
        }
        GrokAuth.shared.purgeLegacyArtifacts()
        rebuildMenu()
        let launchSignedIn = GrokAuth.shared.isSignedIn
        presentDisclaimerIfNeeded { [weak self] in
            self?.handleLaunch(signedIn: launchSignedIn)
        }
        timer = Timer.scheduledTimer(withTimeInterval: client.pollInterval, repeats: true) { [weak self] _ in
            guard GrokAuth.shared.isSignedIn else { return }
            Task { [weak self] in
                await self?.refresh(force: false)
            }
        }
        if let timer {
            RunLoop.main.add(timer, forMode: .common)
        }

        // Auto-update check: on launch + once per day when enabled
        scheduleUpdateChecks()
        Task { await checkForUpdates(interactive: false) }
    }

    /// One-time third-party disclaimer on first run (centered gauge icon + copy).
    private func presentDisclaimerIfNeeded(then completion: @escaping () -> Void) {
        let key = "disclaimerAcceptedV1"
        if UserDefaults.standard.bool(forKey: key) {
            completion()
            return
        }
        if disclaimerWC?.window?.isVisible == true {
            disclaimerWC?.onAccepted = completion
            return
        }
        let wc = DisclaimerWindowController()
        wc.onAccepted = { [weak self] in
            self?.disclaimerWC = nil
            completion()
        }
        disclaimerWC = wc
        wc.show()
    }

    /// First launch: optional support nag, then sign-in or refresh.
    private func handleLaunch(signedIn: Bool) {
        if SupportNagStore.shouldShow {
            presentSupportNag { [weak self] in
                guard let self else { return }
                if signedIn {
                    NSApp.setActivationPolicy(.accessory)
                    Task { await self.refresh(force: false) }
                } else {
                    self.showLoginWindow()
                }
            }
        } else if signedIn {
            Task { await refresh(force: false) }
        } else {
            showLoginWindow()
        }
    }

    private func presentSupportNag(then completion: @escaping () -> Void) {
        // Avoid stacking multiple nags
        if supportNagWC?.window?.isVisible == true {
            supportNagWC?.onFinished = completion
            WindowPlacement.centerOnPrimaryScreen(supportNagWC?.window)
            supportNagWC?.window?.makeKeyAndOrderFront(nil)
            return
        }
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        let nag = SupportNagWindowController()
        nag.onFinished = { [weak self] in
            self?.supportNagWC = nil
            // Stay regular if login will open next; accessory restored after login closes
            completion()
        }
        supportNagWC = nag
        nag.showWindow(nil)
        WindowPlacement.centerOnPrimaryScreen(nag.window)
        nag.window?.makeKeyAndOrderFront(nil)
    }

    private func scheduleUpdateChecks() {
        updateTimer?.invalidate()
        // Once per day while the menubar app is running (plus an immediate check on launch).
        updateTimer = Timer.scheduledTimer(withTimeInterval: 24 * 60 * 60, repeats: true) { [weak self] _ in
            guard self?.autoUpdateEnabled == true else { return }
            Task { await self?.checkForUpdates(interactive: false) }
        }
        if let updateTimer {
            RunLoop.main.add(updateTimer, forMode: .common)
        }
    }

    /// Live Lucide gauge: needle = remaining % (right=full remaining, left=empty).
    private func loadLogo(remainingPercent: Int? = nil) -> NSImage {
        GaugeIcon.menubar(remainingPercent: remainingPercent)
    }

    private func color(forUsed used: Int) -> NSColor {
        // Keep text color in sync with gauge (based on remaining)
        let rem = max(0, min(100, 100 - used))
        return GaugeIcon.color(forRemaining: rem, template: false)
    }

    private func updateTitle() {
        guard let button = statusItem.button else { return }
        button.imagePosition = .imageLeft
        button.imageScaling = .scaleProportionallyDown

        if lastError != nil, lastUsage == nil {
            button.image = loadLogo(remainingPercent: nil)
            button.attributedTitle = NSAttributedString(string: " ?")
            return
        }

        guard let usage = lastUsage else {
            button.image = loadLogo(remainingPercent: nil)
            button.title = " …"
            return
        }

        if !usage.weeklyUsageAvailable || (usage.remainingPercent == nil && usage.usedPercent == nil) {
            button.image = loadLogo(remainingPercent: nil)
            let s = NSMutableAttributedString(string: " —")
            s.addAttribute(.foregroundColor, value: NSColor.secondaryLabelColor, range: NSRange(location: 0, length: s.length))
            button.attributedTitle = s
            return
        }

        let remaining = usage.remainingPercent ?? (100 - (usage.usedPercent ?? 0))
        let used = usage.usedPercent ?? (100 - remaining)
        // Needle: 0% used (full remaining) → right; 0% remaining → left
        button.image = loadLogo(remainingPercent: remaining)
        let headline = showUsedPercent ? used : remaining
        let font = NSFont.monospacedDigitSystemFont(ofSize: 12, weight: .medium)
        let pctColor = color(forUsed: used)
        let attr = NSMutableAttributedString()

        attr.append(NSAttributedString(string: " ", attributes: [.font: font]))
        attr.append(NSAttributedString(string: "\(headline)%", attributes: [
            .font: font,
            .foregroundColor: pctColor
        ]))

        let products = usage.productUsage
            .filter { $0.usagePercent > 0 }
            .sorted { $0.usagePercent > $1.usagePercent }

        if showBarInMenuBar {
            attr.append(NSAttributedString(string: " ", attributes: [.font: font]))
            let barProducts = products.isEmpty && used > 0
                ? [ProductUsage(product: 4, name: "Used", usagePercent: used)]
                : products
            if let barImg = makeBarImage(products: barProducts, width: 36, height: 8) {
                attr.append(attachmentString(image: barImg, font: font))
            }
        }

        if showCategories {
            for p in products {
                attr.append(NSAttributedString(string: "  ", attributes: [.font: font]))
                if let dot = makeDotImage(color: ProductColors.color(for: p.product), size: 7) {
                    attr.append(attachmentString(image: dot, font: font))
                    attr.append(NSAttributedString(string: " ", attributes: [.font: font]))
                }
                attr.append(NSAttributedString(
                    string: "\(shortName(p.name)) \(p.usagePercent)%",
                    attributes: [
                        .font: font,
                        .foregroundColor: NSColor.white
                    ]
                ))
            }
        }

        button.attributedTitle = attr
        button.toolTip = tooltip(for: usage)
    }

    private func makeDotImage(color: NSColor, size: CGFloat) -> NSImage? {
        let img = NSImage(size: NSSize(width: size, height: size), flipped: false) { rect in
            color.setFill()
            NSBezierPath(ovalIn: rect.insetBy(dx: 0.25, dy: 0.25)).fill()
            return true
        }
        img.isTemplate = false
        return img
    }

    private func makeBarImage(products: [ProductUsage], width: CGFloat, height: CGFloat) -> NSImage? {
        guard !products.isEmpty else { return nil }
        let img = NSImage(size: NSSize(width: width, height: height), flipped: false) { rect in
            let path = NSBezierPath(roundedRect: rect, xRadius: height / 2, yRadius: height / 2)
            NSColor.white.withAlphaComponent(0.18).setFill()
            path.fill()
            path.addClip()
            var x = rect.minX
            for p in products {
                let segW = max(0, rect.width * CGFloat(p.usagePercent) / 100.0)
                guard segW >= 0.4 else { continue }
                let seg = NSRect(x: x, y: rect.minY, width: segW, height: rect.height)
                ProductColors.color(for: p.product).setFill()
                NSBezierPath(rect: seg).fill()
                x += segW
            }
            return true
        }
        img.isTemplate = false
        return img
    }

    private func attachmentString(image: NSImage, font: NSFont) -> NSAttributedString {
        let attachment = NSTextAttachment()
        attachment.image = image
        let mid = font.capHeight / 2
        let yOffset = mid - image.size.height / 2
        attachment.bounds = NSRect(x: 0, y: yOffset, width: image.size.width, height: image.size.height)
        return NSAttributedString(attachment: attachment)
    }

    private func shortName(_ name: String) -> String {
        switch name {
        case "Grok Build": return "Build"
        case "Grok Plugins": return "Plugins"
        case "3rd Party": return "3P"
        case "App Builder": return "AppB"
        default: return name
        }
    }

    private func tooltip(for usage: UsageResponse) -> String {
        var lines: [String] = []
        let rem = usage.remainingPercent ?? (100 - (usage.usedPercent ?? 0))
        let used = usage.usedPercent ?? (100 - rem)
        if usage.weeklyUsageAvailable {
            lines.append("Used \(used)% · Remaining \(rem)%")
            lines.append(showUsedPercent ? "Menu bar shows used %" : "Menu bar shows remaining %")
        }
        if let end = usage.currentPeriod?.end {
            lines.append("Resets \(formatISO(end))")
        }
        if let err = lastError {
            lines.append("Last error: \(err)")
        }
        return lines.joined(separator: "\n")
    }

    private func formatISO(_ iso: String) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var date = f.date(from: iso)
        if date == nil {
            f.formatOptions = [.withInternetDateTime]
            date = f.date(from: iso)
        }
        guard let date else { return iso }
        let out = DateFormatter()
        out.dateStyle = .medium
        out.timeStyle = .short
        return out.string(from: date)
    }

    private func formatDayKey(_ key: String) -> String {
        guard let d = DailyHistoryStore.parseDayKey(key) else { return key }
        let f = DateFormatter()
        f.dateFormat = "MMM d"
        return f.string(from: d)
    }

    private func weekWindow() -> (endKey: String, bars: [ChartBar], label: String, canPrev: Bool, canNext: Bool) {
        let today = DailyHistoryStore.dayKey()
        // weekOffset 0 → end today; -1 → end 7 days ago, etc. Never > 0 (no future).
        let offset = min(0, weekOffset)
        let endKey = DailyHistoryStore.addDays(today, offset * 7) ?? today
        let startKey = DailyHistoryStore.addDays(endKey, -6) ?? endKey
        let bars = history.bars(endingOn: endKey)
        let label = "\(formatDayKey(startKey)) – \(formatDayKey(endKey))"
        let canNext = offset < 0
        var canPrev = offset > -52
        if history.isEmpty {
            canPrev = false
        } else if let oldest = history.oldestDayKey,
                  let windowStart = DailyHistoryStore.parseDayKey(startKey),
                  let old = DailyHistoryStore.parseDayKey(oldest) {
            canPrev = canPrev && windowStart > old
        }
        return (endKey, bars, label, canPrev, canNext)
    }

    private func infoItem(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: #selector(noop), keyEquivalent: "")
        item.target = self
        item.isEnabled = true
        item.attributedTitle = NSAttributedString(
            string: title,
            attributes: [
                .font: NSFont.menuFont(ofSize: 0),
                .foregroundColor: NSColor.labelColor
            ]
        )
        return item
    }

    /// Indented settings row (no leading spaces — keeps menu width = panel width).
    private func settingsItem(
        _ title: String,
        action: Selector?,
        key: String = "",
        enabled: Bool = true
    ) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
        item.target = action == nil ? nil : self
        item.isEnabled = enabled
        item.indentationLevel = 1
        return item
    }

    @objc private func noop() {}

    /// Shared width for the usage panel + menu so content stays aligned.
    private static let menuContentWidth = UsagePanelView.preferredWidth

    private func rebuildMenu() {
        let menu = NSMenu()
        menu.autoenablesItems = false
        // Keep menu as wide as the usage panel so the chart/bar isn't a narrow
        // left-aligned island next to the key-equivalent gutter.
        menu.minimumWidth = Self.menuContentWidth

        if !GrokAuth.shared.isSignedIn {
            menu.addItem(infoItem("Not signed in"))
            menu.addItem(infoItem("Sign in to track weekly usage"))
            menu.addItem(infoItem("Requires SuperGrok or SuperGrok Heavy"))
            menu.addItem(infoItem("Open Settings and Support to sign in"))
        } else if isFreePlan {
            menu.addItem(infoItem("This app requires a SuperGrok or"))
            menu.addItem(infoItem("SuperGrok Heavy plan to access usage limits."))
            menu.addItem(NSMenuItem.separator())
            let upgrade = NSMenuItem(title: "Open Grok Subscription…", action: #selector(openSubscribeGrok), keyEquivalent: "")
            upgrade.target = self
            menu.addItem(upgrade)
        } else if let usage = lastUsage, usage.weeklyUsageAvailable, usage.remainingPercent != nil {
            let win = weekWindow()
            let panel = UsagePanelView(
                usage: usage,
                chartBars: win.bars,
                weekLabel: win.label,
                canGoPrev: win.canPrev,
                canGoNext: win.canNext,
                showUsedPercent: showUsedPercent,
                formatDate: { [weak self] iso in self?.formatISO(iso) ?? iso },
                navTarget: self,
                prevSelector: #selector(weekPrev),
                nextSelector: #selector(weekNext),
                debugLines: debugMode ? debugInfoLines() : [],
                onDebugBarShiftClick: { [weak self] in self?.handleDebugBarShiftClick() }
            )
            let panelItem = NSMenuItem()
            panelItem.view = panel
            panelItem.isEnabled = true
            menu.addItem(panelItem)
        } else if let err = lastError {
            menu.addItem(infoItem("Error: \(err)"))
        } else if lastUsage != nil {
            menu.addItem(infoItem("Weekly usage unavailable"))
        } else {
            menu.addItem(infoItem("Loading…"))
        }

        menu.addItem(NSMenuItem.separator())

        let refresh = NSMenuItem(
            title: isRefreshing ? "Refreshing…" : "Refresh Now",
            action: #selector(refreshNow),
            keyEquivalent: "r"
        )
        refresh.target = self
        refresh.isEnabled = !isRefreshing && GrokAuth.shared.isSignedIn
        menu.addItem(refresh)

        let open = NSMenuItem(
            title: "Open Grok Usage…",
            action: #selector(openUsagePage),
            keyEquivalent: "o"
        )
        open.target = self
        menu.addItem(open)

        menu.addItem(NSMenuItem.separator())

        // Settings and Support — collapsible (collapsed by default; remembers last state)
        let settingsTitle = settingsExpanded ? "▼  Settings and Support" : "▶  Settings and Support"
        let settingsHeader = NSMenuItem(
            title: settingsTitle,
            action: #selector(toggleSettingsExpanded),
            keyEquivalent: ""
        )
        settingsHeader.target = self
        settingsHeader.isEnabled = true
        menu.addItem(settingsHeader)

        if settingsExpanded {
            // Account — use indentationLevel (not leading spaces) so titles
            // don't force the menu wider than the usage panel.
            if GrokAuth.shared.isSignedIn {
                menu.addItem(settingsItem("Signed in", action: nil, enabled: false))
                menu.addItem(settingsItem("Sign In…", action: #selector(openSignIn)))
                menu.addItem(settingsItem("Sign Out", action: #selector(signOut)))
            } else {
                menu.addItem(settingsItem("Sign In to Grok…", action: #selector(openSignIn), key: "s"))
            }

            menu.addItem(NSMenuItem.separator())

            // Display / launch
            let toggleCats = settingsItem("Show Categories in Menu Bar", action: #selector(toggleCategories))
            toggleCats.state = showCategories ? .on : .off
            menu.addItem(toggleCats)

            let toggleBar = settingsItem("Show Bar Graph in Menu Bar", action: #selector(toggleBarInMenuBar))
            toggleBar.state = showBarInMenuBar ? .on : .off
            menu.addItem(toggleBar)

            let showUsed = settingsItem("Show Used %", action: #selector(selectShowUsedPercent))
            showUsed.state = showUsedPercent ? .on : .off
            menu.addItem(showUsed)

            let showRemaining = settingsItem("Show Remaining %", action: #selector(selectShowRemainingPercent))
            showRemaining.state = showUsedPercent ? .off : .on
            menu.addItem(showRemaining)

            let launchLogin = settingsItem("Open at Login", action: #selector(toggleOpenAtLogin))
            launchLogin.state = isOpenAtLoginEnabled ? .on : .off
            menu.addItem(launchLogin)

            menu.addItem(NSMenuItem.separator())

            menu.addItem(settingsItem("Export History…", action: #selector(exportHistory)))
            menu.addItem(settingsItem("Import History…", action: #selector(importHistory)))

            menu.addItem(NSMenuItem.separator())

            // Updates (checks Binaries/mac-latest.json on the monorepo)
            let autoUp = settingsItem("Auto-Update", action: #selector(toggleAutoUpdate))
            autoUp.state = autoUpdateEnabled ? .on : .off
            menu.addItem(autoUp)

            if let pending = pendingUpdate {
                menu.addItem(settingsItem("Install Update \(pending.tag)…", action: #selector(installPendingUpdate)))
            } else {
                menu.addItem(settingsItem("Check for Updates…", action: #selector(checkForUpdatesManual)))
            }
            if let updateStatus {
                menu.addItem(settingsItem(updateStatus, action: nil, enabled: false))
            }
        }

        menu.addItem(NSMenuItem.separator())

        // Always visible support links
        let donate = NSMenuItem(title: "Donate (Buy Me a Coffee)", action: #selector(openDonate), keyEquivalent: "")
        donate.target = self
        donate.isEnabled = true
        menu.addItem(donate)

        let subscribe = NSMenuItem(title: "Subscribe on X (@blankspeaker)", action: #selector(openSubscribe), keyEquivalent: "")
        subscribe.target = self
        subscribe.isEnabled = true
        menu.addItem(subscribe)

        let author = NSMenuItem(title: "by @blankspeaker", action: #selector(openAuthor), keyEquivalent: "")
        author.target = self
        author.isEnabled = true
        menu.addItem(author)

        menu.addItem(NSMenuItem.separator())

        let quit = NSMenuItem(title: "Quit", action: #selector(quit), keyEquivalent: "q")
        quit.target = self
        quit.isEnabled = true
        menu.addItem(quit)

        // True bottom line: app name + version (always visible; do not use a
        // disabled blank-looking row). Bundle version so it matches the binary.
        let ver = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
            ?? AppUpdater.currentVersion
        let appFooter = NSMenuItem(
            title: "Grok Rate Limit Display \(ver)",
            action: #selector(noop),
            keyEquivalent: ""
        )
        appFooter.target = self
        appFooter.isEnabled = true
        menu.addItem(appFooter)

        menu.delegate = self
        statusItem.menu = menu
    }

    /// Clicking the menu bar item opens the menu and kicks off a fresh usage fetch.
    func menuWillOpen(_ menu: NSMenu) {
        guard GrokAuth.shared.isSignedIn, !isRefreshing else { return }
        Task { await refresh(force: true, rebuildWhileLoading: false) }
    }

    // MARK: - Open at Login (macOS 13+ SMAppService)

    private var isOpenAtLoginEnabled: Bool {
        if #available(macOS 13.0, *) {
            return SMAppService.mainApp.status == .enabled
        }
        return false
    }

    @objc private func toggleOpenAtLogin() {
        if #available(macOS 13.0, *) {
            do {
                if SMAppService.mainApp.status == .enabled {
                    try SMAppService.mainApp.unregister()
                } else {
                    try SMAppService.mainApp.register()
                }
            } catch {
                lastError = "Open at Login: \(error.localizedDescription)"
            }
            rebuildMenu()
        }
    }

    @objc private func openSignIn() {
        if SupportNagStore.shouldShow {
            presentSupportNag { [weak self] in
                self?.showLoginWindow()
            }
        } else {
            showLoginWindow()
        }
    }

    private func showLoginWindow() {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        if loginWC == nil {
            loginWC = LoginWindowController()
            loginWC?.onSignedIn = { [weak self] in
                Task { @MainActor in
                    await self?.refresh(force: true)
                    // Return to menu-bar-only mode after successful login
                    NSApp.setActivationPolicy(.accessory)
                    self?.loginWC?.close()
                    self?.loginWC = nil
                }
            }
        }
        loginWC?.showWindow(nil)
        WindowPlacement.centerOnPrimaryScreen(loginWC?.window)
        loginWC?.window?.makeKeyAndOrderFront(nil)
    }

    @objc private func signOut() {
        let alert = NSAlert()
        alert.messageText = "Sign Out"
        alert.informativeText = "Are you sure you want to sign out?"
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Sign Out")
        alert.addButton(withTitle: "Cancel")
        guard alert.runModal() == .alertFirstButtonReturn else { return }

        GrokAuth.shared.clear()
        // Allow support nag again after a future sign-in
        SupportNagStore.resetOnSignOut()
        lastUsage = nil
        lastError = nil
        isFreePlan = false
        updateTitle()
        rebuildMenu()
    }



    @objc private func weekPrev() {
        if weekOffset > -52 {
            weekOffset -= 1
            rebuildMenu()
            reopenMenu()
        }
    }

    @objc private func weekNext() {
        if weekOffset < 0 {
            weekOffset += 1
            rebuildMenu()
            reopenMenu()
        }
    }

    private func reopenMenu() {
        // Buttons inside custom menu views dismiss the menu; re-open so week nav feels sticky.
        DispatchQueue.main.async { [weak self] in
            self?.statusItem.button?.performClick(nil)
        }
    }

    @objc private func refreshNow() {
        Task { await refresh(force: true, rebuildWhileLoading: true) }
    }

    @objc private func toggleCategories() {
        showCategories.toggle()
        updateTitle()
        rebuildMenu()
    }

    @objc private func toggleBarInMenuBar() {
        showBarInMenuBar.toggle()
        updateTitle()
        rebuildMenu()
    }

    @objc private func toggleSettingsExpanded() {
        settingsExpanded.toggle()
        rebuildMenu()
        reopenMenu()
    }

    @objc private func exportHistory() {
        // Menu-bar apps have no key window — panel.begin often never appears.
        // Defer until the status menu has dismissed, then use runModal + activate.
        DispatchQueue.main.async { [weak self] in
            self?.runExportHistoryPanel()
        }
    }

    @objc private func importHistory() {
        DispatchQueue.main.async { [weak self] in
            self?.runImportHistoryPanel()
        }
    }

    private func runExportHistoryPanel() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSSavePanel()
        panel.allowedContentTypes = [UTType.json]
        panel.allowsOtherFileTypes = true
        panel.isExtensionHidden = false
        panel.nameFieldStringValue = DailyHistoryStore.exportFilename
        panel.canCreateDirectories = true
        panel.title = "Export GRLD History"
        panel.message = "Choose where to save grld-history.json (for Android or another Mac)"
        panel.directoryURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let ver = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
            let data = try history.exportDocument(appVersion: ver)
            try data.write(to: url, options: .atomic)
            showHistoryAlert(
                title: "History exported",
                message: "Saved \(history.slices.count) day slice(s) to:\n\(url.path)"
            )
        } catch {
            showHistoryAlert(title: "Export failed", message: error.localizedDescription)
        }
    }

    private func runImportHistoryPanel() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType.json, UTType.plainText]
        panel.allowsOtherFileTypes = true
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.title = "Import GRLD History"
        panel.message = "Choose a grld-history.json file from Mac or Android"
        panel.directoryURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let data = try Data(contentsOf: url)
            let n = try history.importDocument(data: data)
            rebuildMenu()
            let msg = n > 0
                ? "Merged \(n) day(s) into local history."
                : "No new days to import (already up to date)."
            showHistoryAlert(title: "Import complete", message: msg)
        } catch {
            showHistoryAlert(title: "Import failed", message: error.localizedDescription)
        }
    }

    private func showHistoryAlert(title: String, message: String) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.alertStyle = .informational
        alert.addButton(withTitle: "OK")
        alert.runModal()
    }

    @objc private func selectShowUsedPercent() {
        showUsedPercent = true
        updateTitle()
        rebuildMenu()
    }

    @objc private func selectShowRemainingPercent() {
        showUsedPercent = false
        updateTitle()
        rebuildMenu()
    }

    @objc private func openUsagePage() {
        if let url = URL(string: "https://grok.com/?_s=usage") {
            NSWorkspace.shared.open(url)
        }
    }

    @objc private func openSubscribeGrok() {
        if let url = URL(string: "https://grok.com/#subscribe") {
            NSWorkspace.shared.open(url)
        }
    }

    @objc private func openDonate() {
        if let url = URL(string: "https://buymeacoffee.com/blank_speaker") {
            NSWorkspace.shared.open(url)
        }
    }

    @objc private func openSubscribe() {
        if let url = URL(string: "https://x.com/blankspeaker/creator-subscriptions/subscribe") {
            NSWorkspace.shared.open(url)
        }
    }

    @objc private func openAuthor() {
        if let url = URL(string: "https://x.com/intent/follow?screen_name=blankspeaker") {
            NSWorkspace.shared.open(url)
        }
    }


    @objc private func toggleAutoUpdate() {
        autoUpdateEnabled.toggle()
        if autoUpdateEnabled {
            Task { await checkForUpdates(interactive: false) }
        }
        rebuildMenu()
    }

    @objc private func checkForUpdatesManual() {
        Task { await checkForUpdates(interactive: true) }
    }

    @objc private func installPendingUpdate() {
        guard let pending = pendingUpdate else { return }
        Task { await performInstall(pending) }
    }

    private func checkForUpdates(interactive: Bool) async {
        if interactive {
            await MainActor.run {
                updateStatus = "Checking for updates…"
                rebuildMenu()
            }
        }
        do {
            if let release = try await AppUpdater.checkForUpdate() {
                await MainActor.run {
                    pendingUpdate = release
                    updateStatus = "Update \(release.tag) available"
                    rebuildMenu()
                }
                // Manual check always shows a dialog (auto-install only for background checks).
                if interactive {
                    await MainActor.run {
                        let alert = NSAlert()
                        alert.messageText = "Update \(release.tag) available"
                        alert.informativeText =
                            "You have \(AppUpdater.currentVersion). Install the latest from GitHub?"
                        alert.addButton(withTitle: "Install")
                        alert.addButton(withTitle: "Later")
                        presentAlertModal(alert) { [weak self] resp in
                            if resp == .alertFirstButtonReturn {
                                Task { await self?.performInstall(release) }
                            }
                        }
                    }
                } else if autoUpdateEnabled {
                    await performInstall(release)
                }
            } else {
                await MainActor.run {
                    pendingUpdate = nil
                    updateStatus = "Up to date (v\(AppUpdater.currentVersion))"
                    rebuildMenu()
                    if interactive {
                        let alert = NSAlert()
                        alert.messageText = "You're up to date"
                        alert.informativeText =
                            "Grok Rate Limit Display \(AppUpdater.currentVersion) is the latest version on GitHub."
                        alert.addButton(withTitle: "OK")
                        presentAlertModal(alert)
                    } else {
                        updateStatus = nil
                        rebuildMenu()
                    }
                }
            }
        } catch {
            await MainActor.run {
                updateStatus = "Update check failed"
                rebuildMenu()
                if interactive {
                    let alert = NSAlert()
                    alert.messageText = "Update check failed"
                    alert.informativeText = error.localizedDescription
                    alert.alertStyle = .warning
                    alert.addButton(withTitle: "OK")
                    presentAlertModal(alert)
                }
            }
        }
    }

    /// Menubar apps are LSUIElement; activate briefly so NSAlert is visible.
    private func presentAlertModal(_ alert: NSAlert, then handler: ((NSApplication.ModalResponse) -> Void)? = nil) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        let resp = alert.runModal()
        NSApp.setActivationPolicy(.accessory)
        handler?(resp)
    }

    private func performInstall(_ release: AppUpdater.ReleaseInfo) async {
        await MainActor.run {
            updateStatus = "Installing \(release.tag)…"
            rebuildMenu()
        }
        do {
            try await AppUpdater.installAndRelaunch(release)
        } catch {
            await MainActor.run {
                updateStatus = "Install failed: \(error.localizedDescription)"
                rebuildMenu()
            }
        }
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }

    private func ingest(_ usage: UsageResponse) {
        guard usage.weeklyUsageAvailable, let used = usage.usedPercent else { return }
        history.record(
            usedPercent: used,
            periodStart: usage.currentPeriod?.start,
            productUsage: usage.productUsage
        )
    }

    /// - Parameter rebuildWhileLoading: when false (menu just opened), keep the open menu
    ///   intact until the fetch finishes, then rebuild with fresh data.
    private func refresh(force: Bool, rebuildWhileLoading: Bool = true) async {
        await MainActor.run {
            isRefreshing = true
            if rebuildWhileLoading {
                rebuildMenu()
            }
        }
        do {
            let usage = try await client.fetch(force: force)
            await MainActor.run {
                lastUsage = usage
                lastError = nil
                isFreePlan = false
                isRefreshing = false
                lastRefreshAt = Date()
                lastRefreshOK = true
                lastRefreshError = nil
                ingest(usage)
                updateTitle()
                rebuildMenu()
            }
        } catch {
            await MainActor.run {
                if let e = error as? GrokDirectClient.ClientError, case .freePlan = e {
                    isFreePlan = true
                    lastError = nil
                    lastUsage = nil
                } else {
                    isFreePlan = false
                    lastError = error.localizedDescription
                }
                isRefreshing = false
                lastRefreshAt = Date()
                lastRefreshOK = false
                lastRefreshError = error.localizedDescription
                updateTitle()
                rebuildMenu()
            }
        }
    }

    // MARK: - Secret debug mode (⇧-click main bar ×8)

    private func handleDebugBarShiftClick() {
        let now = Date()
        // Allow a slightly longer window between clicks
        if let last = lastDebugShiftClick, now.timeIntervalSince(last) < 4.0 {
            debugShiftClicks += 1
        } else {
            debugShiftClicks = 1
        }
        lastDebugShiftClick = now
        if debugShiftClicks >= 8 {
            debugMode.toggle()
            debugShiftClicks = 0
            rebuildMenu()
            reopenMenu()
        }
    }

    private func debugInfoLines() -> [String] {
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd HH:mm:ss"
        func fmt(_ d: Date?) -> String {
            guard let d else { return "—" }
            return df.string(from: d)
        }
        let tok = OidcSession.shared.load()
        let ver = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        var lines: [String] = [
            "v\(ver) (\(build))  pid \(ProcessInfo.processInfo.processIdentifier)",
            "Started \(fmt(appStartedAt))",
            "Last refresh \(fmt(lastRefreshAt))" + (lastRefreshOK == true ? " OK" : lastRefreshOK == false ? " FAIL" : ""),
        ]
        if let err = lastRefreshError, !err.isEmpty {
            lines.append("Refresh err: \(err.prefix(48))")
        }
        lines.append("Auth: OAuth only  signedIn: \(GrokAuth.shared.isSignedIn)  freePlan: \(isFreePlan)")
        if let tok {
            lines.append("Token expires \(fmt(tok.expiresAt))  userId \(tok.userId.prefix(8))…")
        } else {
            lines.append("No OAuth tokens on disk")
        }
        lines.append("Tier: \(lastUsage?.tierName ?? "—")")
        lines.append("Poll every \(Int(client.pollInterval))s  history slices \(history.slices.count)")
        if let u = lastUsage {
            lines.append("Used \(u.usedPercent.map(String.init) ?? "—")%  rem \(u.remainingPercent.map(String.init) ?? "—")%")
            if let start = u.currentPeriod?.start {
                lines.append("Period \(start.prefix(16))…")
            } else {
                lines.append("Period —")
            }
            lines.append("Products \(u.productUsage.count)  cached \(u.cached == true)")
        }
        if let e = lastError {
            lines.append("lastError: \(e.prefix(56))")
        }
        lines.append("⇧-click bar ×8 again to hide")
        return lines
    }
}

