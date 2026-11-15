import SwiftUI
import Speech
import AVFoundation

// MARK: - State

/** What "Ask AI" on Search is doing: the prompt and its titles (the chain below them is ChainView's) */
@Observable @MainActor
final class AISearchModel {
    enum Phase { case idle, loading, failed, empty, done }
    var on = false
    var prompt = ""
    var phase = Phase.idle
    var titles: [Title] = []
    private var findTask: Task<Void, Never>?

    func find(_ text: String, stremio: Stremio) {
        let q = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        prompt = q; on = true; phase = .loading
        titles = []
        findTask?.cancel()
        findTask = Task {
            let r = await stremio.aiSearch(q)
            if Task.isCancelled { return }
            if let r { titles = r; phase = r.isEmpty ? .empty : .done } else { phase = .failed }
        }
    }

    func retry(stremio: Stremio) { find(prompt, stremio: stremio) }

    func leave() {
        findTask?.cancel()
        on = false; phase = .idle; titles = []
    }
}

// MARK: - Results

struct AIResultsView: View {
    @Environment(Stremio.self) private var stremio
    var model: AISearchModel
    var edit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            Button { model.leave() } label: {
                Label("Back to Search", systemImage: "chevron.left").font(.subheadline.weight(.semibold))
            }
            .buttonStyle(.plain)
            .padding(.horizontal, Dx.gutter)

            switch model.phase {
            case .loading:
                VStack(spacing: 14) {
                    ProgressView()
                    Text("Finding titles…").foregroundStyle(Dx.muted)
                }
                .frame(maxWidth: .infinity).padding(.top, 60)
            case .failed:
                message("Couldn't search right now", icon: "exclamationmark.triangle", action: ("Retry", { model.retry(stremio: stremio) }))
            case .empty:
                message("Nothing matched, try describing it differently", icon: "sparkles", action: ("Edit", edit))
            case .done:
                // the AI's titles, then "More like" rows as on Genius Browse
                ChainView(title: model.prompt, firstRow: model.titles)
            case .idle:
                EmptyView()
            }
        }
        .padding(.top, 8)
    }

    private func message(_ text: String, icon: String, action: (String, () -> Void)) -> some View {
        VStack(spacing: 14) {
            Image(systemName: icon).font(.largeTitle).foregroundStyle(Dx.muted)
            Text(text).multilineTextAlignment(.center)
            Button(action.0, action: action.1).buttonStyle(.bordered).tint(.white)
        }
        .frame(maxWidth: .infinity).padding(40)
    }
}

// MARK: - The ask sheet

struct AskAISheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var text: String
    @State private var voice = Dictation()
    @FocusState private var focused: Bool
    var onFind: (String) -> Void

    init(initial: String, onFind: @escaping (String) -> Void) {
        _text = State(initialValue: initial)
        self.onFind = onFind
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("Describe what you feel like watching").font(.subheadline).foregroundStyle(Dx.muted)
                TextField("A slow sci-fi about grief, nothing too long", text: $text, axis: .vertical)
                    .lineLimit(4...8)
                    .font(.title3)
                    .focused($focused)
                    .submitLabel(.search)
                    .onSubmit(find)
                    .padding(14)
                    .background(.white.opacity(0.08), in: .rect(cornerRadius: 16))
                if voice.denied {
                    Text("Microphone or speech access is off, so type here, or use the keyboard's dictation.")
                        .font(.footnote).foregroundStyle(Dx.muted)
                }
                HStack(spacing: 12) {
                    Button { toggleMic() } label: {
                        Image(systemName: voice.listening ? "stop.fill" : "mic.fill")
                            .font(.title3).frame(width: 52, height: 52)
                            .glassEffect(.regular.interactive(), in: .circle)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(voice.listening ? "Stop listening" : "Speak")
                    Button(action: find) {
                        Text("Find").font(.headline).foregroundStyle(.black)
                            .frame(maxWidth: .infinity).frame(height: 52)
                            .background(.white, in: .capsule)
                    }
                    .buttonStyle(.plain)
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .opacity(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.4 : 1)
                }
                Spacer()
            }
            .padding(Dx.gutter)
            .screenBackground()
            .navigationTitle("Ask AI")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .onAppear { focused = true }
        .onDisappear { voice.stop() }
        // the live transcript fills the field as it comes
        .onChange(of: voice.transcript) { _, t in if voice.listening { text = t } }
    }

    private func toggleMic() {
        if voice.listening { voice.stop(); return }
        focused = false
        voice.start()
    }

    private func find() {
        voice.stop()
        let q = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        dismiss()
        onFind(q)
    }
}

// MARK: - Speech

/** Live speech to text: partial words arrive as they're said; stopping keeps what was heard */
@Observable @MainActor
final class Dictation {
    var transcript = ""
    var listening = false
    var denied = false
    private let recognizer = SFSpeechRecognizer(locale: Locale.current)
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    func start() {
        SFSpeechRecognizer.requestAuthorization { status in
            AVAudioApplication.requestRecordPermission { granted in
                Task { @MainActor in
                    guard status == .authorized, granted else { self.denied = true; return }
                    self.denied = false
                    self.begin()
                }
            }
        }
    }

    private func begin() {
        guard !listening, let recognizer, recognizer.isAvailable else { denied = recognizer == nil; return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            let req = SFSpeechAudioBufferRecognitionRequest()
            req.shouldReportPartialResults = true
            request = req
            let input = engine.inputNode
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in req.append(buffer) }
            engine.prepare()
            try engine.start()
            listening = true
            task = recognizer.recognitionTask(with: req) { [weak self] result, error in
                Task { @MainActor in
                    guard let self else { return }
                    if let result { self.transcript = result.bestTranscription.formattedString }
                    if error != nil || result?.isFinal == true { self.stop() }
                }
            }
        } catch {
            stop()
        }
    }

    func stop() {
        guard listening || task != nil else { return }
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        task = nil; request = nil
        listening = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
