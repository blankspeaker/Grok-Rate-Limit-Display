import AppKit

let app = NSApplication.shared
app.setActivationPolicy(.accessory)

let controller = MenubarController()
controller.start()

app.run()
