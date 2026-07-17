import Foundation

/// Grok Build–style credits fetch via CLI proxy JSON (Bearer OAuth).
enum BillingClient {
    static func fetch() async throws -> UsageResponse {
        let access = try await OidcSession.shared.ensureAccessToken()
        let tokens = OidcSession.shared.load()

        var req = URLRequest(url: OidcSession.billingURL)
        req.httpMethod = "GET"
        req.timeoutInterval = 15
        req.setValue("Bearer \(access)", forHTTPHeaderField: "Authorization")
        req.setValue(OidcSession.tokenHeader, forHTTPHeaderField: "X-XAI-Token-Auth")
        if let uid = tokens?.userId, !uid.isEmpty {
            req.setValue(uid, forHTTPHeaderField: "x-userid")
        }
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("grld-macos-oauth", forHTTPHeaderField: "x-grok-client-version")
        req.setValue("GRLD-macOS/oauth", forHTTPHeaderField: "User-Agent")

        do {
            let (data, response) = try await URLSession.shared.data(for: req)
            if let http = response as? HTTPURLResponse {
                if http.statusCode == 401 || http.statusCode == 403 {
                    throw GrokDirectClient.ClientError.http(
                        http.statusCode,
                        "Session rejected — Sign In to Grok again"
                    )
                }
                if !(200...299).contains(http.statusCode) {
                    let body = String(data: data, encoding: .utf8) ?? ""
                    throw GrokDirectClient.ClientError.http(http.statusCode, body)
                }
            }
            return try parse(data)
        } catch let e as GrokDirectClient.ClientError {
            throw e
        } catch {
            throw GrokDirectClient.ClientError.network(error)
        }
    }

    static func parse(_ data: Data) throws -> UsageResponse {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw GrokDirectClient.ClientError.parseFailed
        }
        guard let cfg = root["config"] as? [String: Any] else {
            throw GrokDirectClient.ClientError.parseFailed
        }

        let usedF: Double? = {
            if let n = cfg["creditUsagePercent"] as? NSNumber { return n.doubleValue }
            if let d = cfg["creditUsagePercent"] as? Double { return d }
            return nil
        }()
        let used = usedF.map { min(100, max(0, Int($0.rounded()))) }

        var periodType = "weekly"
        var periodStart: String?
        var periodEnd: String?
        if let period = cfg["currentPeriod"] as? [String: Any] {
            let t = period["type"] as? String ?? ""
            if t.uppercased().contains("MONTHLY") { periodType = "monthly" }
            periodStart = period["start"] as? String
            periodEnd = period["end"] as? String
        }
        if periodStart == nil { periodStart = cfg["billingPeriodStart"] as? String }
        if periodEnd == nil { periodEnd = cfg["billingPeriodEnd"] as? String }

        var products: [ProductUsage] = []
        if let arr = cfg["productUsage"] as? [[String: Any]] {
            for p in arr {
                let nameRaw = p["product"] as? String ?? "Unknown"
                // API often lists every product with no usagePercent (or 0).
                // Only keep categories that actually have used %.
                guard let n = p["usagePercent"] as? NSNumber else { continue }
                let pct = min(100, max(0, Int(n.doubleValue.rounded())))
                guard pct > 0 else { continue }
                let id = productId(for: nameRaw)
                let display = displayName(for: id, fallback: nameRaw)
                products.append(ProductUsage(product: id, name: display, usagePercent: pct))
            }
        }

        let hasSignal = used != nil
            || products.contains(where: { $0.usagePercent > 0 })
            || periodStart != nil
            || periodEnd != nil
        if !hasSignal {
            throw GrokDirectClient.ClientError.freePlan
        }

        let usedFinal = used ?? products.map(\.usagePercent).max() ?? 0
        let tier = (root["subscriptionTier"] as? String)
            ?? (root["subscription_tier"] as? String)

        let fetched = ISO8601DateFormatter().string(from: Date())
        return UsageResponse(
            ok: true,
            remainingPercent: max(0, min(100, 100 - usedFinal)),
            usedPercent: usedFinal,
            weeklyUsageAvailable: true,
            tierName: tier,
            currentPeriod: UsagePeriod(type: periodType, start: periodStart, end: periodEnd),
            productUsage: products.sorted { $0.usagePercent > $1.usagePercent },
            fetchedAt: fetched,
            cached: false
        )
    }

    private static func productId(for raw: String) -> Int {
        let s = raw.lowercased()
            .replacingOccurrences(of: "_", with: "")
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: " ", with: "")
        if s.contains("build") { return 2 }
        if s.contains("chat") { return 4 }
        if s.contains("imagine") || s.contains("image") { return 5 }
        if s.contains("plugin") { return 3 }
        if s.contains("voice") { return 6 }
        if s.contains("api") { return 1 }
        if s.contains("third") || s.contains("3rd") { return 0 }
        return 100 + abs(s.hashValue % 200)
    }

    private static func displayName(for id: Int, fallback: String) -> String {
        switch id {
        case 0: return "3rd Party"
        case 1: return "API"
        case 2: return "Grok Build"
        case 3: return "Grok Plugins"
        case 4: return "Chat"
        case 5: return "Imagine"
        case 6: return "Voice"
        default:
            // "GrokBuild" → "Grok Build"
            var out = ""
            for (i, ch) in fallback.enumerated() {
                if i > 0, ch.isUppercase, fallback[fallback.index(fallback.startIndex, offsetBy: i - 1)].isLowercase {
                    out.append(" ")
                }
                out.append(ch)
            }
            return out.isEmpty ? fallback : out
        }
    }
}
