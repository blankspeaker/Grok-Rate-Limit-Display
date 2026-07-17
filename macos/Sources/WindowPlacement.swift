import AppKit

/// Places windows on the real primary (menu-bar) display — avoids secondary monitor popups.
enum WindowPlacement {
    /// Prefer the screen that hosts the menu bar; fall back to largest display.
    static var primaryScreen: NSScreen {
        if let main = NSScreen.screens.first(where: { $0 == NSScreen.main }) ?? NSScreen.main {
            // NSScreen.main is the screen with the active key window / menu bar focus.
            // Prefer the display marked as having the menu bar when possible.
            return main
        }
        return NSScreen.screens.max(by: {
            ($0.frame.width * $0.frame.height) < ($1.frame.width * $1.frame.height)
        }) ?? NSScreen.main ?? NSScreen.screens[0]
    }

    /// Largest screen by area (usually the main monitor when multi-display).
    static var largestScreen: NSScreen {
        NSScreen.screens.max(by: {
            ($0.frame.width * $0.frame.height) < ($1.frame.width * $1.frame.height)
        }) ?? primaryScreen
    }

    static func centerOnPrimaryScreen(_ window: NSWindow?) {
        guard let window else { return }
        // Use largest display so windows land on the big monitor, not a small secondary.
        let screen = largestScreen
        let visible = screen.visibleFrame
        var frame = window.frame
        if frame.width < 50 || frame.height < 50 {
            frame.size = window.contentLayoutRect.size
            if frame.width < 50 { frame.size = NSSize(width: 460, height: 400) }
        }
        frame.origin.x = visible.midX - frame.width / 2
        frame.origin.y = visible.midY - frame.height / 2
        // Clamp fully on-screen
        if frame.maxX > visible.maxX { frame.origin.x = visible.maxX - frame.width }
        if frame.maxY > visible.maxY { frame.origin.y = visible.maxY - frame.height }
        if frame.minX < visible.minX { frame.origin.x = visible.minX }
        if frame.minY < visible.minY { frame.origin.y = visible.minY }
        window.setFrame(frame, display: true)
    }
}
