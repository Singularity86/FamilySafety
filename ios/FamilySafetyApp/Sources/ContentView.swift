import FamilySafetyCore
import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = HarnessViewModel()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Group {
                        Text("Me: \(viewModel.myName)").font(.headline)
                        Text(viewModel.myMemberId).font(.caption).foregroundStyle(.secondary)
                        Text("Connection: \(String(describing: viewModel.connectionState))")
                            .foregroundStyle(color(for: viewModel.connectionState))
                            .fontWeight(.semibold)
                    }

                    Divider()

                    Group {
                        Text("Peer: \(viewModel.peerName)").font(.headline)
                        Text(viewModel.peerMemberId).font(.caption).foregroundStyle(.secondary)
                        if let online = viewModel.peerIsOnline {
                            Text(online ? "\u{1F7E2} Online" : "\u{1F534} Offline")
                                .font(.title2).fontWeight(.bold)
                        } else {
                            Text("\u{26AA} No presence seen yet")
                        }
                        if let lastSeen = viewModel.peerLastSeen {
                            Text("as of \(lastSeen.formatted(date: .omitted, time: .standard))")
                                .font(.caption)
                        }
                        if let valid = viewModel.peerSignatureValid {
                            Text(valid ? "signature valid" : "SIGNATURE INVALID")
                                .font(.caption)
                                .foregroundStyle(valid ? .green : .red)
                        }
                    }

                    Divider()

                    Button("Run crypto/model self-tests") {
                        viewModel.runSelfTests()
                    }
                    ForEach(viewModel.selfTestResults, id: \.self) { line in
                        Text(line).font(.system(.caption, design: .monospaced))
                    }

                    Divider()

                    Text("Log").font(.headline)
                    ForEach(Array(viewModel.log.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(.caption2, design: .monospaced))
                    }
                }
                .padding()
            }
            .navigationTitle("FamilySafety Harness")
        }
        .task {
            viewModel.start()
        }
    }

    private func color(for state: MqttConnectionState) -> Color {
        switch state {
        case .connected: return .green
        case .connecting: return .orange
        case .disconnected: return .red
        }
    }
}
