import AppKit
import Foundation

/// Device-code OAuth sign-in (opens system browser — no in-app WebView).
final class LoginWindowController: NSWindowController {
    private var statusLabel: NSTextField!
    private var codeLabel: NSTextField!
    private var openBtn: NSButton!
    private var retryBtn: NSButton!
    private var cancelBtn: NSButton!
    private var progress: NSProgressIndicator!

    private var pending: DeviceAuth.Pending?
    private var pollTask: Task<Void, Never>?
    private var finishedOk = false

    var onSignedIn: (() -> Void)?

    convenience init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 440, height: 320),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "Sign in to Grok"
        window.isReleasedWhenClosed = false
        self.init(window: window)
        buildUI()
        WindowPlacement.centerOnPrimaryScreen(window)
        startDeviceFlow()
    }

    private func buildUI() {
        guard let content = window?.contentView else { return }

        let help = NSTextField(wrappingLabelWithString: """
        Sign in with your SpaceXAI / Grok account in your browser.

        This app requires a SuperGrok or SuperGrok Heavy plan to access usage limits.
        """)
        help.font = .systemFont(ofSize: 12)
        help.textColor = .secondaryLabelColor
        help.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(help)

        codeLabel = NSTextField(labelWithString: "————")
        codeLabel.font = .monospacedSystemFont(ofSize: 28, weight: .bold)
        codeLabel.alignment = .center
        codeLabel.textColor = .labelColor
        codeLabel.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(codeLabel)

        let codeHint = NSTextField(labelWithString: "If the browser asks for a code, use the one above")
        codeHint.font = .systemFont(ofSize: 11)
        codeHint.textColor = .tertiaryLabelColor
        codeHint.alignment = .center
        codeHint.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(codeHint)

        statusLabel = NSTextField(wrappingLabelWithString: "Preparing sign-in…")
        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.alignment = .center
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(statusLabel)

        progress = NSProgressIndicator()
        progress.style = .spinning
        progress.controlSize = .small
        progress.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(progress)

        openBtn = NSButton(title: "Open Browser to Sign In", target: self, action: #selector(openBrowser))
        openBtn.bezelStyle = .rounded
        openBtn.isEnabled = false
        openBtn.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(openBtn)

        retryBtn = NSButton(title: "Try Again", target: self, action: #selector(retrySignIn))
        retryBtn.bezelStyle = .rounded
        retryBtn.isHidden = true
        retryBtn.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(retryBtn)

        cancelBtn = NSButton(title: "Cancel", target: self, action: #selector(closeWindow))
        cancelBtn.bezelStyle = .rounded
        cancelBtn.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(cancelBtn)

        NSLayoutConstraint.activate([
            help.topAnchor.constraint(equalTo: content.topAnchor, constant: 16),
            help.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 20),
            help.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -20),

            codeLabel.topAnchor.constraint(equalTo: help.bottomAnchor, constant: 28),
            codeLabel.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            codeLabel.leadingAnchor.constraint(greaterThanOrEqualTo: content.leadingAnchor, constant: 20),
            codeLabel.trailingAnchor.constraint(lessThanOrEqualTo: content.trailingAnchor, constant: -20),

            codeHint.topAnchor.constraint(equalTo: codeLabel.bottomAnchor, constant: 8),
            codeHint.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 20),
            codeHint.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -20),

            statusLabel.topAnchor.constraint(equalTo: codeHint.bottomAnchor, constant: 20),
            statusLabel.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 20),
            statusLabel.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -20),

            progress.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 12),
            progress.centerXAnchor.constraint(equalTo: content.centerXAnchor),

            openBtn.bottomAnchor.constraint(equalTo: retryBtn.topAnchor, constant: -8),
            openBtn.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 20),
            openBtn.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -20),

            retryBtn.bottomAnchor.constraint(equalTo: cancelBtn.topAnchor, constant: -10),
            retryBtn.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 20),
            retryBtn.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -20),

            cancelBtn.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -16),
            cancelBtn.centerXAnchor.constraint(equalTo: content.centerXAnchor)
        ])
    }

    private func startDeviceFlow() {
        progress.startAnimation(nil)
        statusLabel.stringValue = "Preparing sign-in…"
        statusLabel.textColor = .secondaryLabelColor
        openBtn.isEnabled = false
        openBtn.isHidden = false
        retryBtn.isHidden = true
        codeLabel.stringValue = "————"

        pollTask?.cancel()
        pollTask = Task { [weak self] in
            do {
                let p = try await DeviceAuth.requestCode()
                guard !Task.isCancelled else { return }
                await MainActor.run {
                    guard let self else { return }
                    self.pending = p
                    self.codeLabel.stringValue = p.userCode
                    self.openBtn.isEnabled = true
                    self.openBtn.isHidden = false
                    self.retryBtn.isHidden = true
                    self.progress.stopAnimation(nil)
                    self.statusLabel.stringValue = "Complete sign-in in your browser, then return here."
                    self.statusLabel.textColor = .secondaryLabelColor
                    self.openBrowser()
                    self.startPolling(p)
                }
            } catch {
                await MainActor.run {
                    guard let self else { return }
                    self.progress.stopAnimation(nil)
                    let msg = error.localizedDescription
                    let rateLimited = (error as? DeviceAuth.AuthError)?.isRateLimited == true
                        || msg.localizedCaseInsensitiveContains("too many")
                        || msg.localizedCaseInsensitiveContains("slow_down")
                    if rateLimited {
                        self.statusLabel.stringValue =
                            "Too many sign-in attempts (rate limit).\nWait about a minute, then tap Try Again."
                    } else {
                        self.statusLabel.stringValue = "Sign-in failed: \(msg)"
                    }
                    self.statusLabel.textColor = .systemRed
                    self.openBtn.isHidden = true
                    self.retryBtn.isHidden = false
                    self.retryBtn.isEnabled = true
                    self.retryBtn.title = "Try Again"
                }
            }
        }
    }

    @objc private func openBrowser() {
        guard let url = pending?.browserURL else { return }
        NSWorkspace.shared.open(url)
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    @objc private func retrySignIn() {
        retryBtn.isEnabled = false
        startDeviceFlow()
    }

    private func startPolling(_ pending: DeviceAuth.Pending) {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            var intervalNs = UInt64(pending.intervalSec) * 1_000_000_000
            let deadline = Date().addingTimeInterval(pending.expiresInSec)

            await MainActor.run {
                self?.statusLabel.stringValue = "Waiting for you to finish in the browser…"
                self?.statusLabel.textColor = .secondaryLabelColor
                self?.progress.startAnimation(nil)
            }

            // First sleep — immediate poll is always pending
            try? await Task.sleep(nanoseconds: intervalNs)

            while !Task.isCancelled {
                if Date() > deadline {
                    await MainActor.run {
                        self?.progress.stopAnimation(nil)
                        self?.statusLabel.stringValue = "Sign-in timed out — close and try again."
                        self?.statusLabel.textColor = .systemOrange
                    }
                    return
                }

                let result = await DeviceAuth.pollOnce(pending)
                switch result {
                case .success(let access, let refresh, let expiresIn, let userId):
                    await MainActor.run {
                        self?.finishWithTokens(
                            access: access,
                            refresh: refresh,
                            expiresIn: expiresIn,
                            userId: userId
                        )
                    }
                    return
                case .pending:
                    await MainActor.run {
                        self?.statusLabel.stringValue = "Waiting for you to finish in the browser…"
                    }
                case .slowDown:
                    intervalNs += 5_000_000_000
                case .denied(let msg):
                    await MainActor.run {
                        self?.progress.stopAnimation(nil)
                        self?.statusLabel.stringValue = "Denied: \(msg)"
                        self?.statusLabel.textColor = .systemRed
                    }
                    return
                case .expired:
                    await MainActor.run {
                        self?.progress.stopAnimation(nil)
                        self?.statusLabel.stringValue = "Sign-in timed out — close and try again."
                        self?.statusLabel.textColor = .systemOrange
                    }
                    return
                case .error(let msg):
                    await MainActor.run {
                        self?.statusLabel.stringValue = "Still waiting… (\(msg))"
                    }
                }

                try? await Task.sleep(nanoseconds: intervalNs)
            }
        }
    }

    private func finishWithTokens(access: String, refresh: String?, expiresIn: TimeInterval?, userId: String?) {
        guard !finishedOk else { return }
        finishedOk = true
        pollTask?.cancel()
        OidcSession.shared.save(
            accessToken: access,
            refreshToken: refresh,
            expiresIn: expiresIn,
            userId: userId,
            email: nil
        )

        progress.stopAnimation(nil)
        statusLabel.stringValue = "Signed in — closing…"
        statusLabel.textColor = .systemGreen
        openBtn.isEnabled = false

        onSignedIn?()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
            self?.closeWindow()
        }
    }

    @objc private func closeWindow() {
        pollTask?.cancel()
        window?.close()
    }

    deinit {
        pollTask?.cancel()
    }
}
