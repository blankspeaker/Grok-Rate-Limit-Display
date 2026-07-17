import Foundation

/// App auth façade — OAuth tokens only ([OidcSession]).
final class GrokAuth {
    static let shared = GrokAuth()

    private init() {}

    var isSignedIn: Bool { OidcSession.shared.hasTokens }

    func clear() {
        OidcSession.shared.clear()
        // Remove leftover WebKit data from pre-OAuth builds
        Self.purgeLegacyWebKitData()
    }

    /// Call at launch so old cookie sessions never count as signed-in.
    func purgeLegacyArtifacts() {
        Self.purgeLegacyWebKitData()
    }

    private static func purgeLegacyWebKitData() {
        let fm = FileManager.default
        let home = fm.homeDirectoryForCurrentUser
        let candidates = [
            home.appendingPathComponent("Library/WebKit/com.blankspeaker.GRLD"),
            home.appendingPathComponent("Library/HTTPStorages/com.blankspeaker.GRLD"),
            home.appendingPathComponent("Library/HTTPStorages/com.blankspeaker.GRLD.binarycookies"),
            home.appendingPathComponent("Library/Caches/com.blankspeaker.GRLD")
        ]
        for url in candidates {
            try? fm.removeItem(at: url)
        }
    }
}
