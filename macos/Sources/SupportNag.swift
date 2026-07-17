import AppKit

// MARK: - Persistence

/// Controls when the support / tip nag appears.
///
/// - **I already have** → never again until sign-out
/// - **Skip** → again after a random day 30–60 days later (on launch or sign-in)
enum SupportNagStore {
    private static let foreverKey = "supportNagDismissedForever"
    private static let nextShowKey = "supportNagNextShowDate"

    static var dismissedForever: Bool {
        get { UserDefaults.standard.bool(forKey: foreverKey) }
        set { UserDefaults.standard.set(newValue, forKey: foreverKey) }
    }

    static var nextShowDate: Date? {
        get { UserDefaults.standard.object(forKey: nextShowKey) as? Date }
        set {
            if let newValue {
                UserDefaults.standard.set(newValue, forKey: nextShowKey)
            } else {
                UserDefaults.standard.removeObject(forKey: nextShowKey)
            }
        }
    }

    /// Whether the nag should be shown now (launch or opening Sign In).
    static var shouldShow: Bool {
        if dismissedForever { return false }
        if let next = nextShowDate {
            return Date() >= next
        }
        // Never decided → show (first launch / first sign-in prompt)
        return true
    }

    static func markAlreadySupporting() {
        dismissedForever = true
        nextShowDate = nil
    }

    /// Skip for ~1–2 months; next show lands on a random day in that range.
    static func markSkipped() {
        dismissedForever = false
        let days = Int.random(in: 30...60)
        nextShowDate = Calendar.current.startOfDay(for: Date())
            .addingTimeInterval(TimeInterval(days) * 24 * 60 * 60)
    }

    /// After logout, nag may appear again on next launch / sign-in.
    static func resetOnSignOut() {
        dismissedForever = false
        nextShowDate = nil
    }
}

// MARK: - Window

/// Soft support prompt: Follow / Subscribe / Buy Me a Coffee + Already have / Skip.
final class SupportNagWindowController: NSWindowController {
    var onFinished: (() -> Void)?

    private static let followURL = URL(string: "https://x.com/intent/follow?screen_name=blankspeaker")!
    private static let subscribeURL = URL(string: "https://x.com/blankspeaker/creator-subscriptions/subscribe")!
    private static let donateURL = URL(string: "https://buymeacoffee.com/blank_speaker")!

    /// X Subscribe pill color (user-specified): #C936CC
    private static let subscribePurple = NSColor(srgbRed: 0xC9 / 255, green: 0x36 / 255, blue: 0xCC / 255, alpha: 1)
    /// Buy Me a Coffee yellow.
    private static let bmcYellow = NSColor(srgbRed: 1.0, green: 0.867, blue: 0.0, alpha: 1)

    convenience init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 400),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "Support Grok Rate Limit Display"
        window.isReleasedWhenClosed = false
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
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(stack)

        let title = NSTextField(labelWithString: "Support this project")
        title.font = .systemFont(ofSize: 20, weight: .bold)
        title.alignment = .center
        title.isEditable = false
        title.isBordered = false
        title.backgroundColor = .clear

        let body = NSTextField(wrappingLabelWithString: """
        GRLD is free. If it helps you, please consider supporting the developer by following @blankspeaker on 𝕏, subscribing, or buying a coffee.
        """)
        body.font = .systemFont(ofSize: 13)
        body.textColor = .secondaryLabelColor
        body.alignment = .center
        body.preferredMaxLayoutWidth = 380
        body.isEditable = false
        body.isBordered = false
        body.backgroundColor = .clear

        let follow = makeActionButton(
            title: "Follow @blankspeaker on  𝕏",
            background: .black,
            foreground: .white,
            border: .white,
            action: #selector(openFollow)
        )
        let subscribe = makeActionButton(
            title: "Subscribe to @blankspeaker on  𝕏",
            background: Self.subscribePurple,
            foreground: .white,
            action: #selector(openSubscribe)
        )
        let coffee = makeActionButton(
            title: "Buy blankspeaker a Coffee",
            background: Self.bmcYellow,
            foreground: .black,
            action: #selector(openDonate)
        )

        let already = makeLinkButton(title: "I already have", action: #selector(alreadyHave))
        let skip = makeLinkButton(title: "Skip", action: #selector(skipForNow))

        let footer = NSStackView(views: [already, skip])
        footer.orientation = .horizontal
        footer.spacing = 28
        footer.alignment = .centerY

        let hint = NSTextField(labelWithString: "Links open in your browser — you can use more than one.")
        hint.font = .systemFont(ofSize: 11)
        hint.textColor = .tertiaryLabelColor
        hint.alignment = .center
        hint.isEditable = false
        hint.isBordered = false
        hint.backgroundColor = .clear

        stack.addArrangedSubview(title)
        stack.addArrangedSubview(body)
        stack.setCustomSpacing(20, after: body)
        stack.addArrangedSubview(follow)
        stack.addArrangedSubview(subscribe)
        stack.addArrangedSubview(coffee)
        stack.setCustomSpacing(22, after: coffee)
        stack.addArrangedSubview(footer)
        stack.addArrangedSubview(hint)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: content.topAnchor, constant: 28),
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 28),
            stack.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -28),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: content.bottomAnchor, constant: -24),

            follow.widthAnchor.constraint(equalToConstant: 360),
            follow.heightAnchor.constraint(equalToConstant: 44),
            subscribe.widthAnchor.constraint(equalToConstant: 360),
            subscribe.heightAnchor.constraint(equalToConstant: 44),
            coffee.widthAnchor.constraint(equalToConstant: 360),
            coffee.heightAnchor.constraint(equalToConstant: 44)
        ])
    }

    private func makeActionButton(
        title: String,
        background: NSColor,
        foreground: NSColor,
        border: NSColor? = nil,
        action: Selector
    ) -> ColoredButton {
        let btn = ColoredButton(
            title: title,
            background: background,
            foreground: foreground,
            border: border
        )
        btn.target = self
        btn.action = action
        btn.translatesAutoresizingMaskIntoConstraints = false
        return btn
    }

    private func makeLinkButton(title: String, action: Selector) -> NSButton {
        let btn = NSButton(title: title, target: self, action: action)
        btn.isBordered = false
        btn.focusRingType = .none
        let attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 13, weight: .medium),
            .foregroundColor: NSColor.linkColor,
            .underlineStyle: NSUnderlineStyle.single.rawValue
        ]
        btn.attributedTitle = NSAttributedString(string: title, attributes: attrs)
        return btn
    }

    @objc private func openFollow() {
        NSWorkspace.shared.open(Self.followURL)
    }

    @objc private func openSubscribe() {
        NSWorkspace.shared.open(Self.subscribeURL)
    }

    @objc private func openDonate() {
        NSWorkspace.shared.open(Self.donateURL)
    }

    @objc private func alreadyHave() {
        SupportNagStore.markAlreadySupporting()
        finish()
    }

    @objc private func skipForNow() {
        SupportNagStore.markSkipped()
        finish()
    }

    private func finish() {
        let done = onFinished
        onFinished = nil
        window?.close()
        done?()
    }
}

extension SupportNagWindowController: NSWindowDelegate {
    func windowWillClose(_ notification: Notification) {
        // Red traffic-light close acts like Skip (defer; don't forever-dismiss).
        if onFinished != nil {
            SupportNagStore.markSkipped()
            let done = onFinished
            onFinished = nil
            done?()
        }
    }
}

// MARK: - Colored pill action button (X-style capsule)

private final class ColoredButton: NSControl {
    private var tracking = false
    private let buttonHeight: CGFloat = 44

    init(title: String, background: NSColor, foreground: NSColor, border: NSColor? = nil) {
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = background.cgColor
        // Fully rounded capsule (matches X Subscribe pill)
        layer?.cornerRadius = buttonHeight / 2
        layer?.masksToBounds = true
        if let border {
            layer?.borderColor = border.cgColor
            layer?.borderWidth = 1.5
        }

        let label = NSTextField(labelWithString: title)
        label.font = NSFont.systemFont(ofSize: 14, weight: .semibold)
        label.textColor = foreground
        label.alignment = .center
        label.isEditable = false
        label.isBordered = false
        label.backgroundColor = .clear
        label.lineBreakMode = .byTruncatingTail
        label.translatesAutoresizingMaskIntoConstraints = false
        addSubview(label)

        NSLayoutConstraint.activate([
            label.centerYAnchor.constraint(equalTo: centerYAnchor),
            label.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            label.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16)
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func layout() {
        super.layout()
        // Keep capsule radius correct if height changes
        layer?.cornerRadius = bounds.height / 2
    }

    override func mouseDown(with event: NSEvent) {
        tracking = true
        layer?.opacity = 0.85
    }

    override func mouseUp(with event: NSEvent) {
        layer?.opacity = 1
        defer { tracking = false }
        guard tracking else { return }
        let p = convert(event.locationInWindow, from: nil)
        if bounds.contains(p), isEnabled, let action, let target {
            _ = NSApp.sendAction(action, to: target, from: self)
        }
    }

    override func mouseExited(with event: NSEvent) {
        layer?.opacity = 1
        tracking = false
    }

    override var acceptsFirstResponder: Bool { true }
}
