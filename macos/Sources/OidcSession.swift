import Foundation
import Security

/// OAuth2 / OIDC tokens for Grok Build–style auth (auth.x.ai + public CLI client).
/// Stored in Application Support (no permanent WebView).
final class OidcSession {
    static let shared = OidcSession()

    static let clientId = "b1a00492-073a-47ea-816f-4c329264a828"
    static let issuer = "https://auth.x.ai"
    static let tokenHeader = "xai-grok-cli"
    static let billingURL = URL(string: "https://cli-chat-proxy.grok.com/v1/billing?format=credits")!
    static let deviceCodeURL = URL(string: "https://auth.x.ai/oauth2/device/code")!
    static let tokenURL = URL(string: "https://auth.x.ai/oauth2/token")!

    static let scopes = [
        "openid", "profile", "email", "offline_access",
        "grok-cli:access", "api:access",
        "conversations:read", "conversations:write"
    ]

    struct Tokens: Codable {
        var accessToken: String
        var refreshToken: String?
        var expiresAt: Date
        var userId: String
        var email: String?

        var isPresent: Bool { !accessToken.isEmpty }

        func isExpired(skew: TimeInterval = 60) -> Bool {
            Date() >= expiresAt.addingTimeInterval(-skew)
        }
    }

    private let fileURL: URL
    private var cached: Tokens?
    private let lock = NSLock()

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = base.appendingPathComponent("GRLD", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("oidc.json")
        cached = loadFromDisk()
    }

    var hasTokens: Bool {
        lock.lock(); defer { lock.unlock() }
        return cached?.isPresent == true
    }

    func load() -> Tokens? {
        lock.lock(); defer { lock.unlock() }
        return cached
    }

    func save(accessToken: String, refreshToken: String?, expiresIn: TimeInterval?, userId: String?, email: String?) {
        let exp = Date().addingTimeInterval(max(60, expiresIn ?? 6 * 3600))
        let uid = (userId?.isEmpty == false ? userId! : Self.peekUserId(accessToken)) ?? ""
        let t = Tokens(
            accessToken: accessToken,
            refreshToken: refreshToken,
            expiresAt: exp,
            userId: uid,
            email: email
        )
        lock.lock()
        cached = t
        lock.unlock()
        persist(t)
    }

    func clear() {
        lock.lock()
        cached = nil
        lock.unlock()
        try? FileManager.default.removeItem(at: fileURL)
    }

    /// Returns a valid access token, refreshing if needed.
    func ensureAccessToken() async throws -> String {
        if let t = load(), t.isPresent, !t.isExpired() {
            return t.accessToken
        }
        guard let refresh = load()?.refreshToken, !refresh.isEmpty else {
            throw DeviceAuth.AuthError.notSignedIn
        }
        var req = URLRequest(url: Self.tokenURL)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("grld-macos-oauth", forHTTPHeaderField: "x-grok-client-version")
        let body = Self.form([
            "grant_type": "refresh_token",
            "refresh_token": refresh,
            "client_id": Self.clientId
        ])
        req.httpBody = Data(body.utf8)
        req.timeoutInterval = 15

        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            let text = String(data: data, encoding: .utf8) ?? ""
            throw DeviceAuth.AuthError.http(code, text)
        }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        guard let access = json["access_token"] as? String else {
            throw DeviceAuth.AuthError.parseFailed
        }
        let newRefresh = json["refresh_token"] as? String ?? refresh
        let expiresIn = (json["expires_in"] as? NSNumber)?.doubleValue
        let prev = load()
        save(
            accessToken: access,
            refreshToken: newRefresh,
            expiresIn: expiresIn,
            userId: prev?.userId,
            email: prev?.email
        )
        return access
    }

    // MARK: - Persistence

    private func loadFromDisk() -> Tokens? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return try? JSONDecoder().decode(Tokens.self, from: data)
    }

    private func persist(_ t: Tokens) {
        guard let data = try? JSONEncoder().encode(t) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    static func form(_ pairs: [String: String]) -> String {
        pairs.map { key, value in
            let k = key.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? key
            let v = value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? value
            return "\(k)=\(v)"
        }.joined(separator: "&")
    }

    static func peekUserId(_ jwt: String) -> String? {
        let parts = jwt.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var b64 = String(parts[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64.append("=") }
        guard let data = Data(base64Encoded: b64),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        for key in ["principal_id", "principalId", "sub", "user_id", "userId"] {
            if let s = json[key] as? String, !s.isEmpty { return s }
        }
        return nil
    }
}
