import AppKit
// Redraws the Play icon (playstore-assets/icon-512.png) at any size, from geometry measured on it,
// for the iOS app icon (1024 × 1024, opaque; iOS rounds the corners itself). The pin's lower sides
// are fitted to the 512 px original, so pass that file as the first argument:
//   swiftc -O scripts/render-app-icon.swift -o /tmp/render-app-icon
//   /tmp/render-app-icon ../../playstore-assets/icon-512.png 1024 App/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png
// Shrunk back to 512, the result differs from the original only along edges (anti-aliasing).
let src = NSBitmapImageRep(data: NSImage(contentsOfFile: CommandLine.arguments[1])!.tiffRepresentation!)!
let size = Int(CommandLine.arguments[2])!, out = CommandLine.arguments[3]
func isOrange(_ x: Int, _ y: Int) -> Bool { var p = [Int](repeating: 0, count: 4); src.getPixel(&p, atX: x, y: y); return p[0] > 200 && p[1] > 140 && p[1] < 190 && p[2] < 100 }
// Pin half-width per row below the circle, on the outer edges (left edge only, row by row).
var ys: [Double] = [], hws: [Double] = []
for y in 230...436 { if let a = (0..<256).first(where: { isOrange($0, y) }) { ys.append(Double(y)); hws.append(256.0 - Double(a)) } }
// Least-squares cubic hw(y).
func fit(_ xs: [Double], _ ys: [Double], _ deg: Int) -> [Double] {
    let n = deg + 1; var A = [[Double]](repeating: [Double](repeating: 0, count: n + 1), count: n)
    for (x, y) in zip(xs, ys) { let t = (x - 330) / 100; for i in 0..<n { for j in 0..<n { A[i][j] += pow(t, Double(i + j)) }; A[i][n] += y * pow(t, Double(i)) } }
    for c in 0..<n { let p = (c..<n).max { abs(A[$0][c]) < abs(A[$1][c]) }!; A.swapAt(c, p)
        for r in 0..<n where r != c { let f = A[r][c] / A[c][c]; for k in c...n { A[r][k] -= f * A[c][k] } } }
    return (0..<n).map { A[$0][n] / A[$0][$0] }
}
let k = fit(ys, hws, 3)
func hw(_ y: Double) -> Double { let t = (y - 330) / 100; return k[0] + k[1]*t + k[2]*t*t + k[3]*t*t*t }
var tip = 430.0; while hw(tip) > 0 { tip += 0.01 }
let cx = 256.0, cy = 200.0, r = 138.0
let join = 205.0, blendEnd = 275.0  // the circle eases into the fitted sides between these rows
func circle(_ y: Double) -> Double { (r*r - (y-cy)*(y-cy)).squareRoot() }
func side(_ y: Double) -> Double {
    if y <= join { return circle(y) }
    if y >= blendEnd { return hw(y) }
    let t = (y - join) / (blendEnd - join), w = t * t * (3 - 2 * t)
    return circle(y) * (1 - w) + hw(y) * w
}
let cs = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
let s = Double(size) / 512
ctx.translateBy(x: 0, y: CGFloat(size)); ctx.scaleBy(x: CGFloat(s), y: CGFloat(-s))   // 512-unit, y-down
func rgb(_ h: UInt32, _ a: CGFloat = 1) -> CGColor { CGColor(colorSpace: cs, components: [CGFloat((h>>16)&255)/255, CGFloat((h>>8)&255)/255, CGFloat(h&255)/255, a])! }
let dark = rgb(0x16231C), amber = rgb(0xE9A23C), cream = 0xF4E1BE as UInt32
ctx.setFillColor(dark); ctx.fill(CGRect(x: 0, y: 0, width: 512, height: 512))
// Pin: circle above `join`, fitted sides below, meeting at the tip.
let pin = CGMutablePath()
let a0 = asin((join - cy) / r)
pin.addArc(center: CGPoint(x: cx, y: cy), radius: r, startAngle: CGFloat(Double.pi - a0), endAngle: CGFloat(a0), clockwise: false)
var y = join; while y < tip { pin.addLine(to: CGPoint(x: cx + side(y), y: y)); y += 0.25 }
pin.addLine(to: CGPoint(x: cx, y: tip))
y = tip; while y > join { pin.addLine(to: CGPoint(x: cx - side(y), y: y)); y -= 0.25 }
pin.closeSubpath()
ctx.addPath(pin); ctx.setFillColor(amber); ctx.fillPath()
// House: 45° roof, body, base bar (all background colour).
ctx.setFillColor(dark)
ctx.move(to: CGPoint(x: 256, y: 103.5)); ctx.addLine(to: CGPoint(x: 347.5, y: 191)); ctx.addLine(to: CGPoint(x: 164.5, y: 191)); ctx.closePath(); ctx.fillPath()
ctx.fill(CGRect(x: 182.5, y: 190.5, width: 147, height: 98.5))
ctx.addPath(CGPath(roundedRect: CGRect(x: 163.5, y: 297, width: 184.5, height: 28.5), cornerWidth: 3, cornerHeight: 3, transform: nil)); ctx.fillPath()
// The lit window: glow, pane, and the beam falling to the porch.
ctx.setFillColor(rgb(cream, 0.18)); ctx.fill(CGRect(x: 187, y: 200.5, width: 82, height: 68.5))
ctx.setFillColor(rgb(cream)); ctx.fill(CGRect(x: 210, y: 215.5, width: 42, height: 40.5))
ctx.setFillColor(rgb(cream, 0.34))
ctx.move(to: CGPoint(x: 210, y: 256)); ctx.addLine(to: CGPoint(x: 252, y: 256)); ctx.addLine(to: CGPoint(x: 246.5, y: 297)); ctx.addLine(to: CGPoint(x: 177, y: 297)); ctx.closePath(); ctx.fillPath()
let img = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: img)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
