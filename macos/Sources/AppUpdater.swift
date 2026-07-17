import AppKit
import Foundation

/// Checks the monorepo `Binaries/mac-latest.json` manifest and downloads
/// `Binaries/GRLD-macOS-arm64.zip` directly when a newer version is available.
enum AppUpdater {
    static let repoOwner = "blankspeaker"
    static let repoName = "Grok-Rate-Limit-Display"

    /// Version manifest on `main` (raw) — small JSON, always fetchable.
    static let versionManifestURL = URL(
        string: "https://raw.githubusercontent.com/\(repoOwner)/\(repoName)/main/Binaries/mac-latest.json"
    )!

    /// Direct zip download (same folder as the manifest).
    static let defaultZipURL = URL(
        string: "https://github.com/\(repoOwner)/\(repoName)/raw/main/Binaries/GRLD-macOS-arm64.zip"
    )!

    struct ReleaseInfo {
        let tag: String
        let version: String // without leading v
        let zipURL: URL
        let htmlURL: URL?
    }

    static var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    /// Semantic-ish compare: "1.0.2" > "1.0.1"
    static func isNewer(_ remote: String, than local: String) -> Bool {
        let r = remote.trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
        let l = local.trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
        let rp = r.split(separator: ".").map { Int($0) ?? 0 }
        let lp = l.split(separator: ".").map { Int($0) ?? 0 }
        let n = max(rp.count, lp.count)
        for i in 0..<n {
            let a = i < rp.count ? rp[i] : 0
            let b = i < lp.count ? lp[i] : 0
            if a != b { return a > b }
        }
        return false
    }

    /// Reads Binaries/mac-latest.json from the monorepo.
    /// Expected shape:
    ///   { "version": "1.0.0", "zip": "GRLD-macOS-arm64.zip", "notes": "..." }
    /// Optional: "zip_url" absolute override.
    static func fetchLatestRelease() async throws -> ReleaseInfo {
        // Cache-bust raw.githubusercontent.com (it can serve stale manifests for minutes).
        let bust = URL(string: "\(versionManifestURL.absoluteString)?t=\(Int(Date().timeIntervalSince1970))")
            ?? versionManifestURL
        var req = URLRequest(url: bust)
        req.setValue("GRLD/\(currentVersion) (macOS)", forHTTPHeaderField: "User-Agent")
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        req.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        let (data, response) = try await URLSession.shared.data(for: req)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw NSError(domain: "AppUpdater", code: http.statusCode, userInfo: [
                NSLocalizedDescriptionKey: "Version check HTTP \(http.statusCode)"
            ])
        }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let versionRaw = json["version"] as? String
        else {
            throw NSError(domain: "AppUpdater", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Invalid mac-latest.json"
            ])
        }
        let version = versionRaw.trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
        let tag = (json["tag"] as? String) ?? "v\(version)"

        let zipURL: URL
        if let absolute = json["zip_url"] as? String, let u = URL(string: absolute) {
            zipURL = u
        } else {
            let fileName = (json["zip"] as? String) ?? "GRLD-macOS-arm64.zip"
            // Direct raw/main link so auto-update downloads the binary from Binaries/
            zipURL = URL(
                string: "https://github.com/\(repoOwner)/\(repoName)/raw/main/Binaries/\(fileName)"
            ) ?? defaultZipURL
        }

        let html = URL(string: "https://github.com/\(repoOwner)/\(repoName)")
        return ReleaseInfo(tag: tag, version: version, zipURL: zipURL, htmlURL: html)
    }

    /// Returns release if newer than running app, else nil.
    static func checkForUpdate() async throws -> ReleaseInfo? {
        let latest = try await fetchLatestRelease()
        if isNewer(latest.version, than: currentVersion) {
            return latest
        }
        return nil
    }

    /// Download zip, replace app in /Applications (or current bundle parent), relaunch.
    /// Re-fetches the live manifest so a stale "pending" menu item can't install the wrong zip.
    static func installAndRelaunch(_ release: ReleaseInfo) async throws {
        // Prefer the live manifest over a stale in-memory pending release.
        let live = try await fetchLatestRelease()
        let toInstall: ReleaseInfo
        if isNewer(live.version, than: currentVersion) {
            toInstall = live
        } else if isNewer(release.version, than: currentVersion) {
            toInstall = release
        } else {
            throw NSError(domain: "AppUpdater", code: 5, userInfo: [
                NSLocalizedDescriptionKey:
                    "No newer version on GitHub (you have \(currentVersion); remote is \(live.version))."
            ])
        }

        let (tmpZip, _) = try await URLSession.shared.download(from: toInstall.zipURL)
        let work = FileManager.default.temporaryDirectory
            .appendingPathComponent("GRLD-update-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: work) }

        let zipDest = work.appendingPathComponent("GRLD.zip")
        if FileManager.default.fileExists(atPath: zipDest.path) {
            try FileManager.default.removeItem(at: zipDest)
        }
        try FileManager.default.moveItem(at: tmpZip, to: zipDest)

        // Unzip
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        proc.arguments = ["-x", "-k", zipDest.path, work.path]
        try proc.run()
        proc.waitUntilExit()
        guard proc.terminationStatus == 0 else {
            throw NSError(domain: "AppUpdater", code: 3, userInfo: [
                NSLocalizedDescriptionKey: "Failed to unzip release"
            ])
        }

        // Find .app in extract root (prefer full product name; accept legacy GRLD.app)
        let contents = try FileManager.default.contentsOfDirectory(
            at: work,
            includingPropertiesForKeys: nil
        )
        let extracted = contents.first { $0.lastPathComponent == "Grok Rate Limit Display.app" }
            ?? contents.first { $0.pathExtension == "app" }
        guard let extracted else {
            throw NSError(domain: "AppUpdater", code: 4, userInfo: [
                NSLocalizedDescriptionKey: "App bundle not found in zip"
            ])
        }

        // Refuse to install if the zip's Info.plist is not actually newer than us.
        let plistURL = extracted.appendingPathComponent("Contents/Info.plist")
        let zipVersion = (NSDictionary(contentsOf: plistURL)?["CFBundleShortVersionString"] as? String) ?? ""
        guard isNewer(zipVersion, than: currentVersion) else {
            throw NSError(domain: "AppUpdater", code: 6, userInfo: [
                NSLocalizedDescriptionKey:
                    "Downloaded app is \(zipVersion.isEmpty ? "unknown" : zipVersion), " +
                    "not newer than \(currentVersion). GitHub zip may be out of date."
            ])
        }

        let target = preferredInstallURL()
        let legacy = URL(fileURLWithPath: "/Applications/GRLD.app")

        // Copy extracted app to a sticky temp location the script can use after this process dies
        let sticky = FileManager.default.temporaryDirectory
            .appendingPathComponent("GRLD-update-payload-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: sticky, withIntermediateDirectories: true)
        let stickyApp = sticky.appendingPathComponent(extracted.lastPathComponent)
        try FileManager.default.copyItem(at: extracted, to: stickyApp)
        let stickyScript = sticky.appendingPathComponent("install.sh")
        // Single-shot install (no loops). Log to /tmp for debugging failed updates.
        let stickyBody = """
        #!/bin/bash
        set -e
        LOG=/tmp/grld-update-install.log
        {
          echo "=== $(date) installing \(toInstall.version) ==="
          sleep 1
          # Wait until old process is gone (up to ~10s)
          for i in $(seq 1 20); do
            pgrep -x GRLD >/dev/null 2>&1 || break
            sleep 0.5
          done
          pkill -x GRLD 2>/dev/null || true
          sleep 0.5
          rm -rf "\(target.path)"
          rm -rf "\(legacy.path)"
          ditto "\(stickyApp.path)" "\(target.path)"
          xattr -dr com.apple.quarantine "\(target.path)" 2>/dev/null || true
          codesign --force --deep --sign - --identifier "com.blankspeaker.GRLD" "\(target.path)" 2>/dev/null || true
          echo "installed version: $(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "\(target.path)/Contents/Info.plist" 2>/dev/null || echo '?')"
          open "\(target.path)"
          rm -rf "\(sticky.path)"
        } >>"$LOG" 2>&1
        """
        try stickyBody.write(to: stickyScript, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: stickyScript.path)

        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/bin/bash")
        task.arguments = [stickyScript.path]
        try task.run()

        await MainActor.run {
            NSApp.terminate(nil)
        }
    }

    static func preferredInstallURL() -> URL {
        let apps = URL(fileURLWithPath: "/Applications/Grok Rate Limit Display.app")
        if FileManager.default.fileExists(atPath: apps.path) {
            return apps
        }
        // Prefer current install location if already running from an .app
        if Bundle.main.bundleURL.pathExtension == "app" {
            return Bundle.main.bundleURL
        }
        return apps
    }
}
