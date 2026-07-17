import Foundation

/// RFC 8628 device-code login (same as `grok login --device-auth` / Android GRLD).
enum DeviceAuth {
    enum AuthError: LocalizedError {
        case notSignedIn
        case http(Int, String)
        case rateLimited(String)
        case parseFailed
        case denied(String)
        case expired
        case network(Error)

        var errorDescription: String? {
            switch self {
            case .notSignedIn: return "Not signed in"
            case .http(let c, let b): return "HTTP \(c): \(b.prefix(120))"
            case .rateLimited(let m): return m
            case .parseFailed: return "Could not parse auth response"
            case .denied(let m): return m
            case .expired: return "Sign-in timed out — try again"
            case .network(let e): return e.localizedDescription
            }
        }

        /// True when the user should wait before requesting another device code.
        var isRateLimited: Bool {
            if case .rateLimited = self { return true }
            if case .http(429, _) = self { return true }
            return false
        }
    }

    struct Pending {
        let deviceCode: String
        let userCode: String
        let verificationUri: String
        let verificationUriComplete: String?
        let intervalSec: Int
        let expiresInSec: TimeInterval

        var browserURL: URL? {
            let s = verificationUriComplete?.isEmpty == false
                ? verificationUriComplete!
                : verificationUri
            return URL(string: s)
        }
    }

    enum PollResult {
        case success(access: String, refresh: String?, expiresIn: TimeInterval?, userId: String?)
        case pending
        case slowDown
        case denied(String)
        case expired
        case error(String)
    }

    static func requestCode() async throws -> Pending {
        var req = URLRequest(url: OidcSession.deviceCodeURL)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("grld-macos-oauth", forHTTPHeaderField: "x-grok-client-version")
        req.setValue("ui", forHTTPHeaderField: "x-grok-client-surface")
        req.timeoutInterval = 15
        let body = OidcSession.form([
            "client_id": OidcSession.clientId,
            "scope": OidcSession.scopes.joined(separator: " "),
            "referrer": "grok-build"
        ])
        req.httpBody = Data(body.utf8)

        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse else {
            throw AuthError.parseFailed
        }
        if !(200...299).contains(http.statusCode) {
            let text = String(data: data, encoding: .utf8) ?? ""
            // SpaceXAI returns 429 + {"error":"slow_down",...} when device-code is spammed
            if http.statusCode == 429
                || text.contains("slow_down")
                || text.localizedCaseInsensitiveContains("too many")
            {
                let desc = friendlyErrorDescription(from: text)
                    ?? "Too many sign-in attempts. Wait about a minute, then try again."
                throw AuthError.rateLimited(desc)
            }
            throw AuthError.http(http.statusCode, friendlyErrorDescription(from: text) ?? text)
        }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let deviceCode = json["device_code"] as? String,
              let userCode = json["user_code"] as? String,
              let verificationUri = json["verification_uri"] as? String else {
            throw AuthError.parseFailed
        }
        let complete = json["verification_uri_complete"] as? String
        let interval = (json["interval"] as? NSNumber)?.intValue ?? 5
        let expires = (json["expires_in"] as? NSNumber)?.doubleValue ?? 1800
        return Pending(
            deviceCode: deviceCode,
            userCode: userCode,
            verificationUri: verificationUri,
            verificationUriComplete: complete,
            intervalSec: max(1, interval),
            expiresInSec: max(60, expires)
        )
    }

    /// Pull `error_description` from JSON body when present.
    private static func friendlyErrorDescription(from body: String) -> String? {
        guard let data = body.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        if let d = json["error_description"] as? String, !d.isEmpty { return d }
        if let e = json["error"] as? String, !e.isEmpty {
            if e == "slow_down" {
                return "Too many sign-in attempts. Wait about a minute, then try again."
            }
            return e
        }
        return nil
    }

    static func pollOnce(_ pending: Pending) async -> PollResult {
        var req = URLRequest(url: OidcSession.tokenURL)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("grld-macos-oauth", forHTTPHeaderField: "x-grok-client-version")
        req.setValue("ui", forHTTPHeaderField: "x-grok-client-surface")
        req.timeoutInterval = 15
        let body = OidcSession.form([
            "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
            "device_code": pending.deviceCode,
            "client_id": OidcSession.clientId
        ])
        req.httpBody = Data(body.utf8)

        do {
            let (data, response) = try await URLSession.shared.data(for: req)
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            if (200...299).contains(code) {
                guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let access = json["access_token"] as? String else {
                    return .error("Missing access_token")
                }
                let refresh = json["refresh_token"] as? String
                let expiresIn = (json["expires_in"] as? NSNumber)?.doubleValue
                let uid = OidcSession.peekUserId(access)
                return .success(access: access, refresh: refresh, expiresIn: expiresIn, userId: uid)
            }
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let err = json?["error"] as? String ?? "error"
            let desc = json?["error_description"] as? String ?? err
            switch err {
            case "authorization_pending": return .pending
            case "slow_down": return .slowDown
            case "access_denied": return .denied(desc)
            case "expired_token": return .expired
            default: return .error(desc)
            }
        } catch {
            return .error(error.localizedDescription)
        }
    }
}
