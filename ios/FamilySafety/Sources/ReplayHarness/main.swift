import CocoaMQTT
import FamilySafetyCore
import HarnessSupport
import Foundation

setvbuf(stdout, nil, _IONBF, 0)

// Manual live-broker tool for joint check T3 ("replayed old profile refused").
//
//   swift run ReplayHarness capture <memberId> <outDir>
//       Listens on familysafe/{memberId}/group_sync and writes each sealed envelope it sees
//       to <outDir>/<n>-<epochMs>.bin, byte for byte. Runs until Ctrl-C.
//   swift run ReplayHarness replay <memberId> <file>
//       Publishes that captured envelope back to familysafe/{memberId}/group_sync, once.
//   swift run ReplayHarness watch <topicFilter> <outDir>
//       Like capture, for any filter (wildcards allowed), and says per message whether it's a
//       sealed §3 envelope (and from whom) or bare JSON. Used for joint checks of 3.4.
//
// Connects as its own throwaway client with a clean session and NO last-will, so it never
// touches the member's presence or broker session. It can't read the envelopes (E2EE); the
// tester matches captures to versions by timing. Credentials via HarnessSupport.

final class Listener: CocoaMQTTDelegate {
    let outDir: URL?
    var count = 0
    var connected: CheckedContinuation<Void, Never>?
    init(outDir: URL?) { self.outDir = outDir }

    func mqtt(_ mqtt: CocoaMQTT, didConnectAck ack: CocoaMQTTConnAck) {
        print("connack: \(ack)"); connected?.resume(); connected = nil
    }
    func mqtt(_ mqtt: CocoaMQTT, didReceiveMessage message: CocoaMQTTMessage, id: UInt16) {
        guard let outDir else { return }
        count += 1
        let ms = Int(Date().timeIntervalSince1970 * 1000)
        let file = outDir.appendingPathComponent("\(count)-\(ms).bin")
        let data = Data(message.payload)
        try? data.write(to: file)
        let shape = EncryptedEnvelope.peekSenderMemberId(json: data).map { "sealed from \($0.prefix(8))" } ?? "bare JSON"
        print("captured #\(count) \(message.payload.count) bytes at \(Date()) on \(message.topic) [\(shape)] -> \(file.lastPathComponent)")
    }
    func mqtt(_ mqtt: CocoaMQTT, didSubscribeTopics success: NSDictionary, failed: [String]) {
        print("subscribed: \(success) failed: \(failed)")
    }
    func mqtt(_ mqtt: CocoaMQTT, didPublishMessage message: CocoaMQTTMessage, id: UInt16) {}
    func mqtt(_ mqtt: CocoaMQTT, didPublishAck id: UInt16) { print("puback \(id)") }
    func mqtt(_ mqtt: CocoaMQTT, didUnsubscribeTopics topics: [String]) {}
    func mqttDidPing(_ mqtt: CocoaMQTT) {}
    func mqttDidReceivePong(_ mqtt: CocoaMQTT) {}
    func mqttDidDisconnect(_ mqtt: CocoaMQTT, withError err: Error?) { print("disconnected: \(String(describing: err))") }
}

let args = CommandLine.arguments
guard args.count == 4, ["capture", "replay", "watch"].contains(args[1]) else {
    print("usage: ReplayHarness capture <memberId> <outDir> | replay <memberId> <file> | watch <topicFilter> <outDir>"); exit(2)
}
guard let creds = HarnessCredentials.load() else { print(HarnessCredentials.missingMessage); exit(1) }
let topic = args[1] == "watch" ? args[2] : Topics.groupSyncInbox(memberId: args[2])
let capturing = args[1] != "replay"
let listener = Listener(outDir: capturing ? URL(fileURLWithPath: args[3]) : nil)

let client = CocoaMQTT(clientID: "replay-harness-\(UUID().uuidString.prefix(8))",
                       host: FamilySafetyBroker.host, port: FamilySafetyBroker.port)
client.username = creds.username
client.password = creds.password
client.enableSSL = true
client.cleanSession = true
client.keepAlive = 30
client.delegate = listener
await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
    listener.connected = c
    _ = client.connect()
}

if capturing {
    try FileManager.default.createDirectory(at: listener.outDir!, withIntermediateDirectories: true)
    client.subscribe(topic, qos: .qos1)
    print("listening on \(topic)")
    while true { try await Task.sleep(nanoseconds: 60_000_000_000) }
} else {
    let payload = try Data(contentsOf: URL(fileURLWithPath: args[3]))
    client.publish(CocoaMQTTMessage(topic: topic, payload: [UInt8](payload), qos: .qos1, retained: false))
    print("replayed \(payload.count) bytes to \(topic)")
    try await Task.sleep(nanoseconds: 3_000_000_000)
    client.disconnect()
}
