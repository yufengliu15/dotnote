import SwiftUI
import UniformTypeIdentifiers
import DotnoteCore

@main struct DotnoteApp: App {
    @StateObject private var model = NotebookModel()
    var body: some Scene { WindowGroup { LibraryView(model: model) } }
}

struct LibraryView: View {
    @ObservedObject var model: NotebookModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var importing = false
    @State private var naming: String?
    @State private var name = ""
    @State private var destination: URL?
    @State private var deleting: NoteRecord?
    var body: some View {
        NavigationSplitView {
            List {
                Section("Vault") {
                    ForEach(model.vaults) { vault in
                        Button { model.switchVault(vault) } label: { Label(vault.name, systemImage: model.vault?.url == vault.url ? "folder.fill" : "folder") }
                    }
                }
                Section("Notes") {
                    ForEach(model.notes) { note in
                        Button { model.open(note) } label: {
                            VStack(alignment: .leading) {
                                Label(note.title, systemImage: note.template ? "doc.on.doc" : "doc.text")
                                if let vault = model.vault, note.url.deletingLastPathComponent() != vault.url {
                                    Text(note.url.deletingLastPathComponent().lastPathComponent).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }.contextMenu {
                            Button("Delete", role: .destructive) { deleting = note }
                        }
                    }
                }
            }
            .navigationTitle("Dotnote")
            .toolbar {
                ToolbarItem { Menu {
                    Button("New note", systemImage: "square.and.pencil") { destination = model.vault?.url; naming = "New note" }
                    Button("New folder", systemImage: "folder.badge.plus") { destination = model.vault?.url; naming = "New folder" }
                    Button("New vault", systemImage: "externaldrive.badge.plus") { naming = "New vault" }
                    Button("Import note, PDF, image or vault", systemImage: "square.and.arrow.down") { importing = true }
                    Button("Export vault folder", systemImage: "square.and.arrow.up") { model.exportVault() }
                } label: { Image(systemName: "plus") }.accessibilityLabel("Library actions") }
            }
        } detail: {
            if model.note != nil { EditorView(model: model, importing: $importing) }
            else { ContentUnavailableView("Your space to think", systemImage: "pencil.and.outline", description: Text("Create a note or import a Dotnote vault. Everything stays on this iPad.")) }
        }
        .disabled(model.busy)
        .overlay { if model.busy { ProgressView("Working…").padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)) } }
        .task { if model.vault == nil { model.start() } }
        .onChange(of: scenePhase) { _, phase in if phase != .active { model.save() } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.folder, .pdf, .image, UTType(filenameExtension: "dotnote") ?? .data]) { result in
            switch result { case .success(let url): model.importURL(url); case .failure(let error): model.error = error.localizedDescription }
        }
        .sheet(isPresented: Binding(get: { naming != nil }, set: { if !$0 { naming = nil } })) {
            NavigationStack {
                Form {
                    TextField("Name", text: $name).accessibilityIdentifier("nameField")
                    if naming != "New vault" {
                        Picker("Folder", selection: $destination) {
                            ForEach(model.folders, id: \.self) { folder in Text(folder == model.vault?.url ? "Vault root" : folder.lastPathComponent).tag(Optional(folder)) }
                        }
                    }
                }.navigationTitle(naming ?? "New")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { naming = nil; name = "" } }
                        ToolbarItem(placement: .confirmationAction) { Button("Create") {
                            let title = name.trimmingCharacters(in: .whitespacesAndNewlines)
                            if naming == "New vault" { model.createVault(name: title) }
                            else if let folder = destination { if naming == "New folder" { model.createFolder(name: title, parent: folder) } else { model.createNote(title: title, folder: folder) } }
                            naming = nil; name = ""
                        }.disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
                    }
            }.presentationDetents([.medium])
        }
        .sheet(isPresented: Binding(get: { model.shareURL != nil }, set: { if !$0 { model.shareURL = nil } })) { if let url = model.shareURL { ShareView(url: url) } }
        .alert("Dotnote", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) { Button("OK") { model.error = nil } } message: { Text(model.error ?? "") }
        .alert("Delete note?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("Cancel", role: .cancel) { deleting = nil }
            Button("Delete", role: .destructive) { if let value = deleting { model.delete(value) }; deleting = nil }
        } message: { Text("A recovery copy stays in this vault’s local trash.") }
    }
}

struct EditorView: View {
    @ObservedObject var model: NotebookModel
    @Binding var importing: Bool
    private let tools: [(String, String, String)] = [("PEN", "pencil.tip", "Pen"), ("HIGHLIGHTER", "highlighter", "Highlighter"), ("ERASER", "eraser", "Eraser"), ("LASSO", "lasso", "Select"), ("TEXT", "textformat", "Text")]
    var body: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 16) {
                    ForEach(tools, id: \.0) { tool in
                        Button { model.tool = tool.0 } label: { Image(systemName: tool.1).frame(width: 32, height: 36).background(model.tool == tool.0 ? Color.accentColor.opacity(0.15) : .clear, in: RoundedRectangle(cornerRadius: 8)) }.accessibilityLabel(tool.2)
                    }
                    Menu { ForEach(["LINE", "ARROW", "RECTANGLE", "SQUARE", "ELLIPSE", "CIRCLE", "GRID"], id: \.self) { shape in Button(shape.capitalized) { model.tool = shape } } } label: { Image(systemName: "square.on.circle") }.accessibilityLabel("Shapes")
                    Divider().frame(height: 24)
                    ForEach([UInt32(0xff25342e), 0xff2255aa, 0xffc43e36, 0xffdcae32, 0xff8844aa], id: \.self) { color in
                        Button { model.color = Int32(bitPattern: color) } label: { Circle().fill(Color(uiColor: uiColor(Int32(bitPattern: color)))).frame(width: 24, height: 24).overlay(Circle().stroke(.primary, lineWidth: model.color == Int32(bitPattern: color) ? 2 : 0).padding(-3)) }.accessibilityLabel("Ink color \(color)")
                    }
                    Slider(value: $model.width, in: 1...12).frame(width: 90).accessibilityLabel("Pen width")
                    Button { model.editor.undo(); model.changed() } label: { Image(systemName: "arrow.uturn.backward") }.disabled(!model.editor.canUndo).accessibilityLabel("Undo").keyboardShortcut("z", modifiers: .command)
                    Button { model.editor.redo(); model.changed() } label: { Image(systemName: "arrow.uturn.forward") }.disabled(!model.editor.canRedo).accessibilityLabel("Redo").keyboardShortcut("z", modifiers: [.command, .shift])
                }.padding(.horizontal).padding(.vertical, 8)
            }.background(.bar)
            GeometryReader { geometry in
                NotebookCanvas(model: model)
                    .overlay(alignment: .bottomTrailing) {
                        Button { model.editor.fit(width: Float(geometry.size.width), height: Float(geometry.size.height)); model.changed() } label: { Image(systemName: "arrow.up.left.and.arrow.down.right").padding(12).background(.regularMaterial, in: Circle()) }.padding().accessibilityLabel("Fit content")
                    }
            }
        }
        .navigationTitle(model.note?.title ?? "Note")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem { Button(model.saved ? "Saved" : "Save") { model.save() }.font(.caption).keyboardShortcut("s", modifiers: .command) }
            ToolbarItem { Menu {
                Toggle("Draw with finger", isOn: $model.fingerDrawing)
                Button("Toggle dots") { model.editor.toggleDots(); model.changed() }
                Button("Import PDF or image") { importing = true }
                Button("Export note") { model.exportNote() }
                Button("Export PDF") { CanvasView(model: model).exportPDF() }
                Button("Recolor selection") { model.editor.recolor(color: model.color); model.changed() }
                Button("Enlarge selection") { model.editor.scaleSelection(factor: 1.1); model.changed() }
                Button("Shrink selection") { model.editor.scaleSelection(factor: 0.9); model.changed() }
                Button("Delete selection", role: .destructive) { model.editor.deleteSelection(); model.changed() }
            } label: { Image(systemName: "ellipsis.circle") }.accessibilityLabel("Note actions") }
        }
    }
}
struct ShareView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
