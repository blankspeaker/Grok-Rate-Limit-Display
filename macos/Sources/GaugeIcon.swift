import AppKit

/// Lucide "gauge" used by the Tampermonkey / Greasyfork Grok Rate Limit Display script:
///   path needle: m12 14 4-4
///   path arc:    M3.34 19a10 10 0 1 1 17.32 0
/// viewBox 0 0 24 24, stroke-width 2, round caps (kept proportional — not chunky end dots).
enum GaugeIcon {

    /// remaining 100 → needle at right; remaining 0 → needle at left.
    /// Colors (by remaining): >25% default, 5–25% yellow→orange, ≤5% red.
    static func image(
        size: CGFloat,
        remainingPercent: Int?,
        template: Bool,
        background: NSColor? = nil,
        roundedBackground: Bool = false
    ) -> NSImage {
        let s = max(12, size)
        let img = NSImage(size: NSSize(width: s, height: s))
        img.lockFocus()
        defer { img.unlockFocus() }

        let ctx = NSGraphicsContext.current!.cgContext
        let pad = s * 0.08
        let scale = (s - 2 * pad) / 24.0
        // Keep stroke close to Lucide 2/24 of the content box (not overscaled caps)
        let stroke = max(1.2, 2.0 * scale)

        func sx(_ x: CGFloat) -> CGFloat { pad + x * scale }
        // SVG y-down → AppKit y-up
        func sy(_ y: CGFloat) -> CGFloat { s - (pad + y * scale) }

        if let bg = background {
            ctx.setFillColor(bg.cgColor)
            if roundedBackground {
                let r = s / 5
                ctx.addPath(CGPath(
                    roundedRect: CGRect(x: 0, y: 0, width: s, height: s),
                    cornerWidth: r, cornerHeight: r, transform: nil
                ))
                ctx.fillPath()
            } else {
                ctx.fill(CGRect(x: 0, y: 0, width: s, height: s))
            }
        }

        let rem = remainingPercent.map { max(0, min(100, $0)) }
        let strokeColor = color(forRemaining: rem, template: template)
        ctx.setStrokeColor(strokeColor.cgColor)
        ctx.setLineWidth(stroke)
        // Round caps match Lucide; width is thin so ends are not “balls”
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)

        let arc = arcGeometry()
        // Draw arc
        let arcPath = CGMutablePath()
        let steps = max(40, Int(s))
        for i in 0...steps {
            let t = CGFloat(i) / CGFloat(steps)
            let th = arc.th1 + arc.dth * t
            let px = arc.cx + arc.rx * cos(th)
            let py = arc.cy + arc.ry * sin(th)
            let p = CGPoint(x: sx(px), y: sy(py))
            if i == 0 { arcPath.move(to: p) } else { arcPath.addLine(to: p) }
        }
        ctx.addPath(arcPath)
        ctx.strokePath()

        // Needle
        let pivot = CGPoint(x: sx(12), y: sy(14))
        let tip: CGPoint
        if let rem {
            // rem 100 → right end of arc (t=1); rem 0 → left (t=0)
            let t = CGFloat(rem) / 100.0
            let th = arc.th1 + arc.dth * t
            // Tip sits on arc (same as lucide length roughly)
            let tipX = arc.cx + arc.rx * cos(th)
            let tipY = arc.cy + arc.ry * sin(th)
            tip = CGPoint(x: sx(tipX), y: sy(tipY))
        } else {
            // Default lucide needle m12 14 → 16 10
            tip = CGPoint(x: sx(16), y: sy(10))
        }
        let needle = CGMutablePath()
        needle.move(to: pivot)
        needle.addLine(to: tip)
        ctx.addPath(needle)
        ctx.strokePath()

        img.isTemplate = template && (rem == nil || rem! > 25)
        return img
    }

    /// Menubar-sized gauge (16pt).
    static func menubar(remainingPercent: Int?) -> NSImage {
        let rem = remainingPercent
        let colored = rem != nil && rem! <= 25
        let img = image(
            size: 18,
            remainingPercent: rem,
            template: !colored,
            background: nil
        )
        img.size = NSSize(width: 16, height: 16)
        return img
    }

    // MARK: - Color (by remaining %)

    /// >25% left: label/white · 5–25%: yellow→orange · ≤5%: red
    static func color(forRemaining rem: Int?, template: Bool) -> NSColor {
        guard let rem else {
            return template ? NSColor.black : NSColor.white
        }
        if rem > 25 {
            return template ? NSColor.black : NSColor.white
        }
        if rem <= 5 {
            return NSColor.systemRed
        }
        // 5...25 → orange at 5, yellow at 25
        let t = CGFloat(rem - 5) / 20.0 // 0 at 5%, 1 at 25%
        let yellow = NSColor.systemYellow
        let orange = NSColor.systemOrange
        return orange.blended(withFraction: t, of: yellow) ?? orange
    }

    // MARK: - Arc geometry (SVG arc endpoint → center)

    private struct Arc {
        let cx, cy, rx, ry, th1, dth: CGFloat
    }

    /// M3.34 19 a10 10 0 1 1 17.32 0
    private static func arcGeometry() -> Arc {
        let x1: CGFloat = 3.34, y1: CGFloat = 19
        let x2: CGFloat = 20.66, y2: CGFloat = 19
        let rx: CGFloat = 10, ry: CGFloat = 10
        let fa = true, fs = true

        let dx = (x1 - x2) / 2, dy = (y1 - y2) / 2
        var rx2 = rx * rx, ry2 = ry * ry
        let x1p = dx, y1p = dy
        let lam = x1p * x1p / rx2 + y1p * y1p / ry2
        var crx = rx, cry = ry
        if lam > 1 {
            let s = sqrt(lam)
            crx *= s; cry *= s
            rx2 = crx * crx; ry2 = cry * cry
        }
        let num = max(0, (rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p)
            / (rx2 * y1p * y1p + ry2 * x1p * x1p))
        var coef = sqrt(num)
        if fa == fs { coef = -coef }
        let cxp = coef * (crx * y1p / cry)
        let cyp = coef * -(cry * x1p / crx)
        let cx = (x1 + x2) / 2 + cxp
        let cy = (y1 + y2) / 2 + cyp

        func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            let sign: CGFloat = (ux * vy - uy * vx < 0) ? -1 : 1
            let dot = ux * vx + uy * vy
            let nu = sqrt(ux * ux + uy * uy), nv = sqrt(vx * vx + vy * vy)
            let c = max(-1 as CGFloat, min(1, dot / (nu * nv)))
            return sign * acos(c)
        }
        let ux = (x1p - cxp) / crx, uy = (y1p - cyp) / cry
        let vx = (-x1p - cxp) / crx, vy = (-y1p - cyp) / cry
        let th1 = angle(1, 0, ux, uy)
        var dth = angle(ux, uy, vx, vy)
        if !fs && dth > 0 { dth -= 2 * .pi }
        if fs && dth < 0 { dth += 2 * .pi }
        return Arc(cx: cx, cy: cy, rx: crx, ry: cry, th1: th1, dth: dth)
    }
}
