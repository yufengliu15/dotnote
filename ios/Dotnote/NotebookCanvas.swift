import SwiftUI
import UIKit
import PDFKit
import DotnoteCore

struct NotebookCanvas: UIViewRepresentable {
    @ObservedObject var model: NotebookModel
    func makeUIView(context: Context) -> CanvasView { CanvasView(model: model) }
    func updateUIView(_ view: CanvasView, context: Context) { view.setNeedsDisplay() }
}

final class CanvasView: UIView, UIGestureRecognizerDelegate {
    let model: NotebookModel
    private var owned: UITouch?
    private var points: [Pt] = []
    private var pressures: [KotlinFloat] = []
    private var activeTool = "PEN"
    private var start: CGPoint = .zero
    private var movingSelection = false
    private let pdfs = NSCache<NSString, PDFDocument>()
    init(model: NotebookModel) {
        self.model = model
        super.init(frame: .zero)
        backgroundColor = UIColor(red: 0.98, green: 0.975, blue: 0.95, alpha: 1)
        isMultipleTouchEnabled = true
        isAccessibilityElement = true
        accessibilityLabel = "Notebook canvas"
        accessibilityHint = "Draw with Apple Pencil. Use two fingers to pan and pinch to zoom."
        pdfs.countLimit = 4
        let pan = UIPanGestureRecognizer(target: self, action: #selector(pan(_:)))
        pan.minimumNumberOfTouches = 1; pan.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]; pan.delegate = self
        addGestureRecognizer(pan)
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinch(_:)))
        pinch.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]; pinch.delegate = self
        addGestureRecognizer(pinch)
        let pencil = UIPencilInteraction(); pencil.delegate = self; addInteraction(pencil)
    }
    required init?(coder: NSCoder) { fatalError("Use init(model:)") }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool { true }
    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if owned?.type == .pencil { return false }
        if let pan = gestureRecognizer as? UIPanGestureRecognizer { return !model.fingerDrawing || pan.numberOfTouches >= 2 }
        return true
    }
    @objc private func pan(_ gesture: UIPanGestureRecognizer) {
        guard owned?.type != .pencil else { return }
        cancelStroke()
        let delta = gesture.translation(in: self); gesture.setTranslation(.zero, in: self)
        let camera = model.editor.document.camera
        model.editor.camera(x: camera.x + Float(delta.x), y: camera.y + Float(delta.y), zoom: camera.zoom)
        setNeedsDisplay()
        if gesture.state == .ended { model.changed() }
    }
    @objc private func pinch(_ gesture: UIPinchGestureRecognizer) {
        guard owned?.type != .pencil else { return }
        cancelStroke()
        let focus = gesture.location(in: self)
        let c = model.editor.document.camera.zoomAt(focus: Pt(x: Float(focus.x), y: Float(focus.y)), factor: Float(gesture.scale))
        model.editor.camera(x: c.x, y: c.y, zoom: c.zoom); gesture.scale = 1
        setNeedsDisplay()
        if gesture.state == .ended { model.changed() }
    }
    private func world(_ point: CGPoint) -> Pt { model.editor.document.camera.world(p: Pt(x: Float(point.x), y: Float(point.y))) }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !model.busy, owned == nil, let touch = touches.first(where: { $0.type == .pencil || model.fingerDrawing }) else { return }
        owned = touch; activeTool = model.tool; points = []; pressures = []; start = touch.location(in: self)
        movingSelection = activeTool == "LASSO" && model.editor.document.items.contains { model.editor.isSelected(id: $0.id) && $0.bounds.contains(p: world(start)) }
        append(touch, event: event)
    }
    private func append(_ touch: UITouch, event: UIEvent?) {
        // Only real/coalesced samples enter the document. Predictions are never persisted.
        for sample in event?.coalescedTouches(for: touch) ?? [touch] {
            points.append(world(sample.location(in: self)))
            let force = sample.maximumPossibleForce > 0 ? sample.force / sample.maximumPossibleForce : 1
            pressures.append(KotlinFloat(float: Float(max(0, min(1, force)))))
        }
        setNeedsDisplay()
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) { if let owned, touches.contains(owned) { append(owned, event: event) } }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let owned, touches.contains(owned) else { return }
        append(owned, event: event)
        guard let first = points.first, let last = points.last else { cancelStroke(); return }
        switch activeTool {
        case "PEN", "HIGHLIGHTER": model.editor.stroke(points: points, pressures: pressures, color: model.color, width: model.width * (activeTool == "HIGHLIGHTER" ? 5 : 1), highlight: activeTool == "HIGHLIGHTER")
        case "ERASER": model.editor.erase(path: points, radius: 14 / model.editor.document.camera.zoom)
        case "LASSO":
            if movingSelection { model.editor.moveSelection(dx: last.x - first.x, dy: last.y - first.y) }
            else { model.editor.select(path: points, radius: 12 / model.editor.document.camera.zoom) }
        case "TEXT": presentText(at: first)
        default: model.editor.shape(kind: activeTool, start: first, end: last, color: model.color, width: model.width)
        }
        cancelStroke(); model.changed()
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { cancelStroke() }
    private func cancelStroke() { owned = nil; points = []; pressures = []; setNeedsDisplay() }
    private func presentText(at point: Pt) {
        let alert = UIAlertController(title: "Add text", message: nil, preferredStyle: .alert)
        alert.addTextField { $0.placeholder = "Text" }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Add", style: .default) { [weak self] _ in
            guard let self, let text = alert.textFields?.first?.text, !text.isEmpty else { return }
            let size = (text as NSString).boundingRect(with: CGSize(width: 320, height: 10000), options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: UIFont.systemFont(ofSize: 24)], context: nil)
            self.model.editor.text(content: text, at: point, color: self.model.color, fontSize: 24, width: 320, height: Float(ceil(size.height)))
            self.model.changed()
        })
        var responder: UIResponder? = self
        while let current = responder { if let controller = current as? UIViewController { controller.present(alert, animated: true); break }; responder = current.next }
    }
    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        let doc = model.editor.document
        let camera = doc.camera
        if doc.dots {
            let spacing = max(8, CGFloat(24 * camera.zoom))
            context.setFillColor(UIColor(white: 0.65, alpha: 0.5).cgColor)
            var x = CGFloat(camera.x).truncatingRemainder(dividingBy: spacing)
            while x < bounds.width { var y = CGFloat(camera.y).truncatingRemainder(dividingBy: spacing)
                while y < bounds.height { context.fillEllipse(in: CGRect(x: x, y: y, width: 1.2, height: 1.2)); y += spacing }; x += spacing }
        }
        context.saveGState(); context.translateBy(x: CGFloat(camera.x), y: CGFloat(camera.y)); context.scaleBy(x: CGFloat(camera.zoom), y: CGFloat(camera.zoom))
        let viewport = context.boundingBoxOfClipPath
        let scene = model.editor.visible(viewport: Bounds(left: Float(viewport.minX), top: Float(viewport.minY), right: Float(viewport.maxX), bottom: Float(viewport.maxY)))
        let visible = scene.pages + scene.highlights + scene.foreground
        for item in visible where item.kind == "PDF" { drawItem(item, in: context) }
        context.saveGState(); context.setAlpha(1.0 / 3); context.beginTransparencyLayer(auxiliaryInfo: nil)
        for item in visible where item.kind == "HIGHLIGHTER" { drawItem(item, in: context) }
        if activeTool == "HIGHLIGHTER" { drawLive(context) }
        context.endTransparencyLayer(); context.restoreGState()
        for item in visible where item.kind != "PDF" && item.kind != "HIGHLIGHTER" { drawItem(item, in: context) }
        if activeTool != "HIGHLIGHTER" { drawLive(context) }
        context.setStrokeColor(UIColor.systemBlue.cgColor); context.setLineWidth(1 / CGFloat(camera.zoom))
        for item in visible where model.editor.isSelected(id: item.id) { context.stroke(cgRect(item.bounds)) }
        context.restoreGState()
    }
    private func drawLive(_ context: CGContext) {
        guard let first = points.first else { return }
        context.setStrokeColor(uiColor(model.color).cgColor)
        context.setLineWidth(CGFloat(model.width * (activeTool == "HIGHLIGHTER" ? 5 : 1)))
        context.setLineCap(.round); context.setLineJoin(.round)
        context.beginPath(); context.move(to: cgPoint(first))
        for point in points.dropFirst() { context.addLine(to: cgPoint(point)) }
        context.strokePath()
    }
    func drawItem(_ item: Item, in context: CGContext) {
        context.saveGState(); defer { context.restoreGState() }
        let t = item.transform
        context.translateBy(x: CGFloat(t.tx), y: CGFloat(t.ty)); context.scaleBy(x: CGFloat(t.sx), y: CGFloat(t.sy))
        context.setStrokeColor(uiColor(item.color).cgColor); context.setFillColor(uiColor(item.color).cgColor)
        context.setLineWidth(CGFloat(item.width)); context.setLineCap(.round); context.setLineJoin(.round)
        guard let first = item.points.first else { return }
        if item.fill {
            var index = 0
            while index + 1 < item.points.count { context.fill(rectangle(item.points[index], item.points[index + 1])); index += 2 }
        } else if item.kind == "PDF" {
            guard let asset = item.asset, let vault = model.vault, let last = item.points.last else { return }
            let url = vault.url.appendingPathComponent("attachments/" + asset)
            let key = url.path as NSString
            let pdf = pdfs.object(forKey: key) ?? PDFDocument(url: url)
            if let pdf { pdfs.setObject(pdf, forKey: key) }
            guard let page = pdf?.page(at: Int(item.page)) else { return }
            let target = rectangle(first, last); let source = page.bounds(for: .mediaBox)
            context.setFillColor(UIColor.white.cgColor); context.fill(target)
            context.translateBy(x: target.minX, y: target.maxY); context.scaleBy(x: target.width / source.width, y: -target.height / source.height)
            context.translateBy(x: -source.minX, y: -source.minY)
            page.draw(with: .mediaBox, to: context)
        } else if item.kind == "TEXT", let text = item.text, let last = item.points.last {
            (text as NSString).draw(in: rectangle(first, last), withAttributes: [.font: UIFont.systemFont(ofSize: CGFloat(item.fontSize)), .foregroundColor: uiColor(item.color)])
        } else if item.kind == "PEN" || item.kind == "HIGHLIGHTER" {
            for (index, point) in item.points.enumerated() {
                let pressure = item.kind == "PEN" ? item.pressures[safe: index]?.floatValue ?? 1 : 1
                let width = CGFloat(item.width * (0.2 + 0.8 * pressure))
                context.setLineWidth(width)
                if index == 0 { context.fillEllipse(in: CGRect(x: CGFloat(point.x) - width / 2, y: CGFloat(point.y) - width / 2, width: width, height: width)) }
                else { context.beginPath(); context.move(to: cgPoint(item.points[index - 1])); context.addLine(to: cgPoint(point)); context.strokePath() }
            }
        } else {
            // Shared shape geometry already includes its transform.
            context.concatenate(CGAffineTransform(a: CGFloat(t.sx), b: 0, c: 0, d: CGFloat(t.sy), tx: CGFloat(t.tx), ty: CGFloat(t.ty)).inverted())
            context.setLineWidth(CGFloat(item.width * max(t.sx, t.sy)))
            for segment in Core().segments(item: item) { context.move(to: cgPoint(segment.start)); context.addLine(to: cgPoint(segment.end)) }
            context.strokePath()
        }
    }
    func exportPDF() {
        let document = model.editor.document
        guard let content = document.bounds else { return }
        let bounds = cgRect(content).insetBy(dx: -24, dy: -24)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".pdf")
        do {
            try UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: bounds.size)).writePDF(to: url) { renderer in
                renderer.beginPage(); let context = renderer.cgContext
                context.translateBy(x: -bounds.minX, y: -bounds.minY)
                for item in document.items where item.kind == "PDF" { drawItem(item, in: context) }
                context.saveGState(); context.setAlpha(1.0 / 3); context.beginTransparencyLayer(auxiliaryInfo: nil)
                for item in document.items where item.kind == "HIGHLIGHTER" { drawItem(item, in: context) }
                context.endTransparencyLayer(); context.restoreGState()
                for item in document.items where item.kind != "PDF" && item.kind != "HIGHLIGHTER" { drawItem(item, in: context) }
            }
            model.shareURL = url
        } catch { model.error = error.localizedDescription }
    }
}
extension CanvasView: UIPencilInteractionDelegate {
    func pencilInteractionDidTap(_ interaction: UIPencilInteraction) { model.tool = model.tool == "ERASER" ? "PEN" : "ERASER" }
}
func cgPoint(_ p: Pt) -> CGPoint { CGPoint(x: CGFloat(p.x), y: CGFloat(p.y)) }
func cgRect(_ b: Bounds) -> CGRect { CGRect(x: CGFloat(b.left), y: CGFloat(b.top), width: CGFloat(b.width), height: CGFloat(b.height)) }
func rectangle(_ a: Pt, _ b: Pt) -> CGRect { CGRect(x: CGFloat(min(a.x, b.x)), y: CGFloat(min(a.y, b.y)), width: CGFloat(abs(b.x - a.x)), height: CGFloat(abs(b.y - a.y))) }
func uiColor(_ color: Int32) -> UIColor { let c = UInt32(bitPattern: color); return UIColor(red: CGFloat((c >> 16) & 255) / 255, green: CGFloat((c >> 8) & 255) / 255, blue: CGFloat(c & 255) / 255, alpha: 1) }
extension Array { subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil } }
