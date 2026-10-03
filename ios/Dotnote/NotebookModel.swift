import SwiftUI
import DotnoteCore
import PDFKit
import UniformTypeIdentifiers

@MainActor final class NotebookModel: ObservableObject {
    nonisolated let store: VaultStore
    let editor = EditorSession()
    @Published var vaults: [VaultRecord] = []
    @Published var vault: VaultRecord?
    @Published var notes: [NoteRecord] = []
    @Published var folders: [URL] = []
    @Published var note: NoteRecord?
    @Published var revision = 0
    @Published var tool = "PEN"
    @Published var color: Int32 = Int32(bitPattern: 0xff25342e)
    @Published var width: Float = 3
    @Published var fingerDrawing = false
    @Published var error: String?
    @Published var busy = false
    @Published var saved = true
    @Published var shareURL: URL?
    private var saveRevision = 0
    private var failedSave = false
    init(store: VaultStore = VaultStore()) { self.store = store }

    func start() {
        work({
            var vaults = try self.store.vaults()
            if vaults.isEmpty { vaults = [try self.store.createVault(name: "My notes")] }
            return vaults
        }) { vaults in self.vaults = vaults; if let first = vaults.first(where: { $0.id == UserDefaults.standard.string(forKey: "selectedVault") }) ?? vaults.first { self.switchVault(first) } }
    }
    func switchVault(_ value: VaultRecord) {
        flushThen {
            self.work({ (try self.store.notes(in: value), try self.store.folders(in: value)) }) { result in
                UserDefaults.standard.set(value.id, forKey: "selectedVault")
                self.vault = value; self.notes = result.0; self.folders = result.1; self.note = nil
            }
        }
    }
    func refresh() {
        guard let vault else { return }
        work({ (try self.store.notes(in: vault), try self.store.folders(in: vault)) }) { self.notes = $0.0; self.folders = $0.1 }
    }
    func open(_ value: NoteRecord) {
        flushThen {
            self.work({ try self.store.load(value) }) { json in
                do { try self.editor.load(json: json); self.note = value; self.saved = true; self.revision += 1 }
                catch { self.error = error.localizedDescription }
            }
        }
    }
    func createNote(title: String, folder: URL) {
        flushThen {
            self.work({ try self.store.createNote(title: title, folder: folder) }) { value in self.notes.append(value); self.open(value) }
        }
    }
    func createVault(name: String) {
        flushThen { self.work({ try self.store.createVault(name: name) }) { self.vaults.append($0); self.switchVault($0) } }
    }
    func createFolder(name: String, parent: URL) { work({ try self.store.createFolder(name: name, parent: parent) }) { self.refresh() } }
    func delete(_ value: NoteRecord) {
        guard let vault else { return }
        flushThen { self.work({ try self.store.trash(value, vault: vault) }) { if self.note?.id == value.id { self.note = nil }; self.refresh() } }
    }
    func changed() { revision += 1; save() }
    func save() {
        guard let note else { return }
        let json = editor.encode()
        saveRevision += 1
        let capturedRevision = saveRevision
        saved = false
        let background = UIApplication.shared.beginBackgroundTask(withName: "Save note")
        store.queue.async {
            let result = Result { try self.store.save(note, document: json) }
            DispatchQueue.main.async {
                if background != .invalid { UIApplication.shared.endBackgroundTask(background) }
                switch result {
                case .success:
                    if capturedRevision == self.saveRevision { self.saved = true; self.failedSave = false }
                case .failure(let error): self.failedSave = true; self.error = "Could not save: \(error.localizedDescription). Your note is still open. Retry Save before leaving."
                }
            }
        }
    }
    private func flushThen(_ action: @escaping () -> Void) {
        guard !busy else { return }
        if failedSave { error = "Save failed. Retry Save before switching notes or vaults."; return }
        // Queue barrier waits for every captured write, including its main-thread completion.
        busy = true
        store.queue.async { DispatchQueue.main.async { self.busy = false; if !self.failedSave { action() } } }
    }
    func importURL(_ url: URL) {
        guard let vault else { return }
        if url.hasDirectoryPath {
            flushThen { self.work({ try self.store.importVault(from: url) }) { self.vaults.append($0); self.switchVault($0) } }
        } else if url.pathExtension.lowercased() == "dotnote" {
            work({ try self.store.importNote(from: url, vault: vault) }) { self.notes.append($0); self.open($0) }
        } else { importAttachment(url, vault: vault) }
    }
    private func importAttachment(_ url: URL, vault: VaultRecord) {
        guard let note else { error = "Open a note before importing a PDF or image."; return }
        work({ () -> (String, [KotlinFloat], [KotlinFloat], Bool) in
            let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let asset = UUID().uuidString.lowercased() + ".pdf"
            let target = vault.url.appendingPathComponent("attachments/" + asset)
            let image = url.pathExtension.lowercased() != "pdf"
            if image {
                guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                      let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 4096] as CFDictionary) else { throw VaultError.invalid("Unsupported image") }
                let bitmap = UIImage(cgImage: cg)
                let bounds = CGRect(origin: .zero, size: bitmap.size)
                try UIGraphicsPDFRenderer(bounds: bounds).writePDF(to: target) { context in context.beginPage(); bitmap.draw(in: bounds) }
            } else { try FileManager.default.copyItem(at: url, to: target) }
            guard let pdf = PDFDocument(url: target), pdf.pageCount > 0 else { throw VaultError.invalid("Cannot open PDF") }
            var widths: [KotlinFloat] = []; var heights: [KotlinFloat] = []
            // Store page geometry only. PDFKit rasterizes visible pages on demand.
            for page in 0..<pdf.pageCount {
                guard let bounds = pdf.page(at: page)?.bounds(for: .mediaBox), bounds.width > 0, bounds.height > 0 else { throw VaultError.invalid("Invalid PDF page") }
                widths.append(KotlinFloat(float: Float(bounds.width))); heights.append(KotlinFloat(float: Float(bounds.height)))
            }
            return (asset, widths, heights, image)
        }) { result in
            guard self.note?.id == note.id else { return }
            self.editor.addPages(asset: result.0, widths: result.1, heights: result.2, image: result.3)
            self.changed()
        }
    }
    func exportNote() {
        guard let note else { return }
        flushThen {
            self.work({ () -> URL in
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let url = directory.appendingPathComponent(note.url.lastPathComponent)
                try FileManager.default.copyItem(at: note.url, to: url)
                return url
            }) { self.shareURL = $0 }
        }
    }
    func exportVault() {
        guard let vault else { return }
        flushThen {
            self.work({ () -> URL in
                let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("Dotnote Vault")
                try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try FileManager.default.copyItem(at: vault.url, to: url)
                // Recovery files are local, not part of a portable snapshot.
                let trash = url.appendingPathComponent(".dotnote/trash")
                if FileManager.default.fileExists(atPath: trash.path) { try FileManager.default.removeItem(at: trash) }
                return url
            }) { self.shareURL = $0 }
        }
    }
    func work<T>(_ operation: @escaping () throws -> T, completion: @escaping (T) -> Void) {
        busy = true
        store.queue.async {
            let result = Result { try operation() }
            DispatchQueue.main.async {
                self.busy = false
                switch result { case .success(let value): completion(value); case .failure(let error): self.error = error.localizedDescription }
            }
        }
    }
}
