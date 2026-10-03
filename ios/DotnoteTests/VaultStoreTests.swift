import XCTest
import UIKit
import DotnoteCore
@testable import Dotnote

final class VaultStoreTests: XCTestCase {
    var root: URL!
    var store: VaultStore!
    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        store = VaultStore(root: root)
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: root) }
    @MainActor func testPortableStrokeRendersTheBend() throws {
        let model = NotebookModel(store: store)
        model.editor.stroke(points: [Pt(x: 20, y: 20), Pt(x: 80, y: 100), Pt(x: 140, y: 20)], pressures: [], color: Int32(bitPattern: 0xff000000), width: 10, highlight: false)
        let item = try XCTUnwrap(model.editor.document.items.first)
        let canvas = CanvasView(model: model)
        var pixels = [UInt8](repeating: 255, count: 180 * 140 * 4)
        pixels.withUnsafeMutableBytes { bytes in
            let context = CGContext(data: bytes.baseAddress, width: 180, height: 140, bitsPerComponent: 8, bytesPerRow: 180 * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            canvas.drawItem(item, in: context)
        }
        // Bitmap rows and UIKit have opposite vertical origins. The middle column
        // must contain ink away from either possible endpoint baseline.
        XCTAssertTrue((30..<110).contains { pixels[($0 * 180 + 80) * 4] < 128 })
        XCTAssertEqual(pixels[(20 * 180 + 80) * 4], 255)
    }
    func testSaveReopenAndTrashPreserveContent() throws {
        let vault = try store.createVault(name: "Test")
        let note = try store.createNote(title: "Portable", folder: vault.url)
        let editor = EditorSession()
        editor.stroke(points: [Pt(x: 0, y: 0), Pt(x: 10, y: 20)], pressures: [KotlinFloat(float: 0.4), KotlinFloat(float: 1)], color: -1, width: 3, highlight: false)
        try store.save(note, document: editor.encode())
        let reopened = EditorSession(); try reopened.load(json: store.load(note))
        XCTAssertEqual(reopened.document.items.count, 1)
        XCTAssertEqual(reopened.document.items.first?.pressures.last?.floatValue, 1)
        try store.trash(note, vault: vault)
        XCTAssertTrue(try store.notes(in: vault).isEmpty)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: vault.url.appendingPathComponent(".dotnote/trash").path).count, 1)
    }
    func testVaultImportIsIndependentAndRetainsNestedNotes() throws {
        let source = try store.createVault(name: "Source")
        try store.createFolder(name: "Course", parent: source.url)
        let folder = try XCTUnwrap(store.folders(in: source).first { $0 != source.url })
        let original = try store.createNote(title: "Lecture", folder: folder)
        let imported = try store.importVault(from: source.url)
        XCTAssertNotEqual(imported.id, source.id)
        XCTAssertEqual(imported.portableID, source.portableID)
        XCTAssertEqual(try store.notes(in: imported).first?.title, "Lecture")
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.url.path))
    }
    func testInvalidNoteImportDoesNotPublish() throws {
        let vault = try store.createVault(name: "Test")
        let bad = root.appendingPathComponent("bad.dotnote")
        try Data("{\"format\":\"dotnote\",\"version\":1,\"document\":{}}".utf8).write(to: bad)
        XCTAssertThrowsError(try store.importNote(from: bad, vault: vault))
        XCTAssertTrue(try store.notes(in: vault).isEmpty)
        XCTAssertTrue(FileManager.default.fileExists(atPath: bad.path))
    }
    func testPendingRecoveryVaultIsRejectedWithoutChangingSource() throws {
        let source = try store.createVault(name: "Source")
        let journal = source.url.appendingPathComponent(".dotnote/transaction.json")
        try Data("{}".utf8).write(to: journal)
        XCTAssertThrowsError(try store.importVault(from: source.url))
        XCTAssertTrue(FileManager.default.fileExists(atPath: journal.path))
        XCTAssertEqual(try store.vaults().count, 1)
    }
}
