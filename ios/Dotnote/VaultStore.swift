import Foundation
import DotnoteCore
import PDFKit

struct NoteRecord: Identifiable {
    let id: String
    var title: String
    var url: URL
    var template: Bool
}

struct VaultRecord: Identifiable {
    var id: String { url.lastPathComponent }
    let portableID: String
    let name: String
    let url: URL
}

enum VaultError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { if case .invalid(let reason) = self { return reason }; return nil }
}

/// All filesystem operations run on this serial queue. Canonical files are authoritative.
final class VaultStore: @unchecked Sendable {
    let queue = DispatchQueue(label: "dev.dotnote.vault", qos: .userInitiated)
    let root: URL
    private let fm = FileManager.default
    init(root: URL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Vaults")) { self.root = root }

    func vaults() throws -> [VaultRecord] {
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        let urls = try fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil).filter { !$0.lastPathComponent.hasPrefix(".") }
        return try urls.map { url in
            let meta = try dictionary(url.appendingPathComponent(".dotnote/vault.json"))
            guard meta["format"] as? String == "dotnote-vault", meta["version"] as? Int == 1,
                  let id = meta["id"] as? String, let name = meta["name"] as? String else { throw VaultError.invalid("Invalid vault manifest") }
            return VaultRecord(portableID: id, name: name, url: url)
        }.sorted { $0.name < $1.name }
    }
    func createVault(name: String) throws -> VaultRecord {
        let id = UUID().uuidString.lowercased()
        let url = root.appendingPathComponent(id)
        try fm.createDirectory(at: url.appendingPathComponent(".dotnote"), withIntermediateDirectories: true)
        try fm.createDirectory(at: url.appendingPathComponent("attachments"), withIntermediateDirectories: true)
        try write(["format": "dotnote-vault", "version": 1, "id": id, "name": name], to: url.appendingPathComponent(".dotnote/vault.json"))
        return VaultRecord(portableID: id, name: name, url: url)
    }
    func notes(in vault: VaultRecord) throws -> [NoteRecord] {
        guard let walk = fm.enumerator(at: vault.url, includingPropertiesForKeys: [.isSymbolicLinkKey], options: [.skipsHiddenFiles]) else { return [] }
        var notes: [NoteRecord] = []
        var ids = Set<String>()
        for case let url as URL in walk {
            if try url.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink == true { throw VaultError.invalid("Vaults cannot contain symbolic links") }
            guard url.pathExtension == "dotnote" else { continue }
            let data = try dictionary(url)
            guard data["format"] as? String == "dotnote", data["version"] as? Int == 1,
                  let id = data["id"] as? String, ids.insert(id).inserted,
                  let title = data["title"] as? String else { throw VaultError.invalid("Invalid or duplicate note") }
            try Core().validate(json: documentJSON(data))
            notes.append(NoteRecord(id: id, title: title, url: url, template: data["template"] as? Bool ?? false))
        }
        return notes.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }
    func load(_ note: NoteRecord) throws -> String { try documentJSON(dictionary(note.url)) }
    func save(_ note: NoteRecord, document: String) throws {
        // Preserve envelope metadata when saving an imported note.
        var envelope = fm.fileExists(atPath: note.url.path) ? try dictionary(note.url) : [:]
        envelope.merge(["format": "dotnote", "version": 1, "id": note.id, "title": note.title,
                        "modified": Int64(Date().timeIntervalSince1970 * 1000), "template": note.template]) { _, new in new }
        envelope["document"] = try JSONSerialization.jsonObject(with: Data(document.utf8))
        try write(envelope, to: note.url)
    }
    func createNote(title: String, folder: URL, document: String = Core().emptyDocument()) throws -> NoteRecord {
        let id = UUID().uuidString.lowercased()
        let note = NoteRecord(id: id, title: title, url: folder.appendingPathComponent("\(safeName(title)) [\(id)].dotnote"), template: false)
        try save(note, document: document)
        return note
    }
    func createFolder(name: String, parent: URL) throws {
        let id = UUID().uuidString.lowercased()
        let folder = parent.appendingPathComponent("\(safeName(name)) [\(id)]")
        try fm.createDirectory(at: folder, withIntermediateDirectories: true)
        try write(["id": id, "name": name], to: folder.appendingPathComponent(".folder.json"))
    }
    func folders(in vault: VaultRecord) throws -> [URL] {
        guard let walk = fm.enumerator(at: vault.url, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else { return [] }
        return [vault.url] + walk.compactMap { value in
            guard let url = value as? URL, fm.fileExists(atPath: url.appendingPathComponent(".folder.json").path) else { return nil }
            return url
        }
    }
    func trash(_ note: NoteRecord, vault: VaultRecord) throws {
        let trash = vault.url.appendingPathComponent(".dotnote/trash")
        try fm.createDirectory(at: trash, withIntermediateDirectories: true)
        try fm.moveItem(at: note.url, to: trash.appendingPathComponent(UUID().uuidString + ".dotnote"))
    }
    func importNote(from source: URL, vault: VaultRecord) throws -> NoteRecord {
        let scoped = source.startAccessingSecurityScopedResource(); defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let envelope = try dictionary(source)
        guard envelope["format"] as? String == "dotnote", envelope["version"] as? Int == 1 else { throw VaultError.invalid("Unsupported note format") }
        let json = try documentJSON(envelope)
        let session = EditorSession(); try session.load(json: json)
        guard session.document.items.allSatisfy({ $0.asset == nil }) else { throw VaultError.invalid("This note has attachments. Import its entire vault folder instead.") }
        return try createNote(title: envelope["title"] as? String ?? source.deletingPathExtension().lastPathComponent, folder: vault.url, document: json)
    }
    func importVault(from source: URL) throws -> VaultRecord {
        let scoped = source.startAccessingSecurityScopedResource(); defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        guard try source.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { throw VaultError.invalid("Cannot import a linked vault") }
        let staging = root.appendingPathComponent(".import-" + UUID().uuidString)
        defer { try? fm.removeItem(at: staging) }
        // Reject symlinks before copying, including hidden directories.
        if let walk = fm.enumerator(at: source, includingPropertiesForKeys: [.isSymbolicLinkKey]) {
            for case let url as URL in walk {
                if try url.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink == true { throw VaultError.invalid("Cannot import linked files") }
            }
        }
        try fm.copyItem(at: source, to: staging)
        let meta = try dictionary(staging.appendingPathComponent(".dotnote/vault.json"))
        guard meta["format"] as? String == "dotnote-vault", meta["version"] as? Int == 1,
              let id = meta["id"] as? String, let name = meta["name"] as? String else { throw VaultError.invalid("Choose a Dotnote vault folder") }
        guard !["transaction.json", "transaction.json.bak"].contains(where: { fm.fileExists(atPath: staging.appendingPathComponent(".dotnote/" + $0).path) }) else { throw VaultError.invalid("Open this vault in Android to finish its pending recovery before importing") }
        let stagedVault = VaultRecord(portableID: id, name: name, url: staging)
        for note in try notes(in: stagedVault) {
            let session = EditorSession(); try session.load(json: load(note))
            for item in session.document.items where item.asset != nil {
                let asset = staging.appendingPathComponent("attachments/" + item.asset!)
                guard let pdf = PDFDocument(url: asset), Int(item.page) < pdf.pageCount else { throw VaultError.invalid("Missing or invalid PDF attachment") }
            }
        }
        let destination = root.appendingPathComponent(UUID().uuidString.lowercased())
        try fm.moveItem(at: staging, to: destination)
        return VaultRecord(portableID: id, name: name, url: destination)
    }
    private func dictionary(_ url: URL) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any] else { throw VaultError.invalid("Invalid JSON file") }
        return object
    }
    private func documentJSON(_ envelope: [String: Any]) throws -> String {
        guard let document = envelope["document"], JSONSerialization.isValidJSONObject(document) else { throw VaultError.invalid("Missing note document") }
        return String(decoding: try JSONSerialization.data(withJSONObject: document), as: UTF8.self)
    }
    private func write(_ object: [String: Any], to url: URL) throws {
        try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    private func safeName(_ name: String) -> String {
        let clean = name.map { "/\\:*?\"<>|".contains($0) || $0.isNewline ? "_" : String($0) }.joined().trimmingCharacters(in: .whitespacesAndNewlines)
        return String((clean.isEmpty ? "Untitled" : clean).prefix(40))
    }
}
