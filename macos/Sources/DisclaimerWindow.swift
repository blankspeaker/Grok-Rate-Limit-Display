import AppKit

/// First-run disclaimer: centered gauge icon + balanced copy + single action.
final class DisclaimerWindowController: NSWindowController {
    var onAccepted: (() -> Void)?

    convenience init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 340),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "Grok Rate Limit Display"
        window.isReleasedWhenClosed = false
        window.titlebarAppearsTransparent = false
        self.init(window: window)
        buildUI()
        window.delegate = self
        WindowPlacement.centerOnPrimaryScreen(window)
    }

    private func buildUI() {
        guard let content = window?.contentView else { return }

        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 16
        stack.edgeInsets = NSEdgeInsets(top: 28, left: 28, bottom: 28, right: 28)
        stack.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(stack)

        // Exact gauge app icon (same asset as Dock / extension)
        let iconView = NSImageView()
        iconView.imageScaling = .scaleProportionallyUpOrDown
        iconView.imageAlignment = .alignCenter
        if let img = loadDisclaimerIcon() {
            iconView.image = img
        }
        iconView.translatesAutoresizingMaskIntoConstraints = false
        iconView.widthAnchor.constraint(equalToConstant: 72).isActive = true
        iconView.heightAnchor.constraint(equalToConstant: 72).isActive = true

        let title = NSTextField(labelWithString: "Grok Rate Limit Display")
        title.font = .systemFont(ofSize: 18, weight: .semibold)
        title.alignment = .center
        title.isEditable = false
        title.isBordered = false
        title.backgroundColor = .clear
        title.maximumNumberOfLines = 2
        title.preferredMaxLayoutWidth = 340

        let body = NSTextField(wrappingLabelWithString: """
        This is an open-source, third-party tool with no affiliation, support, or endorsement by SpaceXAI, X, or Grok.

        Use at your own risk.
        """)
        body.font = .systemFont(ofSize: 13)
        body.textColor = .secondaryLabelColor
        body.alignment = .center
        body.isEditable = false
        body.isBordered = false
        body.backgroundColor = .clear
        body.preferredMaxLayoutWidth = 340
        body.maximumNumberOfLines = 0

        let ok = NSButton(title: "I Understand", target: self, action: #selector(accept))
        ok.bezelStyle = .rounded
        ok.keyEquivalent = "\r"
        ok.translatesAutoresizingMaskIntoConstraints = false
        ok.widthAnchor.constraint(greaterThanOrEqualToConstant: 140).isActive = true

        stack.addArrangedSubview(iconView)
        stack.addArrangedSubview(title)
        stack.addArrangedSubview(body)
        stack.addArrangedSubview(ok)
        stack.setCustomSpacing(20, after: body)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: content.topAnchor),
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: content.bottomAnchor)
        ])
    }

    private func loadDisclaimerIcon() -> NSImage? {
        let names = ["DisclaimerIcon", "AppIcon"]
        for name in names {
            if let url = Bundle.main.url(forResource: name, withExtension: "png")
                ?? Bundle.main.url(forResource: name, withExtension: "icns"),
               let img = NSImage(contentsOf: url) {
                img.size = NSSize(width: 72, height: 72)
                return img
            }
            if let img = Bundle.main.image(forResource: name) {
                let copy = img.copy() as? NSImage ?? img
                copy.size = NSSize(width: 72, height: 72)
                return copy
            }
        }
        // Fallback: AppIcon from icns
        if let url = Bundle.main.url(forResource: "AppIcon", withExtension: "icns"),
           let img = NSImage(contentsOf: url) {
            img.size = NSSize(width: 72, height: 72)
            return img
        }
        return nil
    }

    @objc private func accept() {
        UserDefaults.standard.set(true, forKey: "disclaimerAcceptedV1")
        window?.close()
        onAccepted?()
        onAccepted = nil
    }

    func show() {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }
}

extension DisclaimerWindowController: NSWindowDelegate {
    func windowWillClose(_ notification: Notification) {
        // Closing via red traffic light still counts as accept so app can continue
        if !UserDefaults.standard.bool(forKey: "disclaimerAcceptedV1") {
            UserDefaults.standard.set(true, forKey: "disclaimerAcceptedV1")
        }
        let cb = onAccepted
        onAccepted = nil
        cb?()
    }
}
