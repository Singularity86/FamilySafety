import FamilySafetyCore
import SwiftUI

/// Chat: the family's group conversation on top, then one private conversation per member (B3).
struct ChatListView: View {
    @EnvironmentObject private var model: AppModel
    let group: GroupDefinition

    private var others: [FamilyMember] {
        group.members.filter { $0.memberId != model.chatMemberId }
            .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
    }

    var body: some View {
        NavigationStack {
            Screen {
                List {
                    NavigationLink {
                        ConversationView(group: group, peer: nil)
                    } label: {
                        ConversationRow(title: group.groupName, avatar: nil, conversationId: group.groupId)
                    }
                    .listRowBackground(Palette.background)
                    Section {
                        ForEach(others, id: \.memberId) { member in
                            NavigationLink {
                                ConversationView(group: group, peer: member)
                            } label: {
                                ConversationRow(title: member.displayName, avatar: member,
                                                conversationId: model.chatMemberId.map { ChatBook.privateConversationId($0, member.memberId) })
                            }
                            .listRowBackground(Palette.background)
                        }
                    } header: { Text("Just the two of you").font(Typeface.label) }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .padding(.horizontal, -Spacing.m)
            }
            .navigationTitle("Chat")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct ConversationRow: View {
    @EnvironmentObject private var model: AppModel
    let title: String
    /// nil for the family conversation.
    let avatar: FamilyMember?
    let conversationId: String?

    private var last: ChatMessage? { conversationId.flatMap { model.chat.conversation($0).last } }
    private var unread: Int { conversationId.map { model.chat.unreadCount($0) } ?? 0 }

    var body: some View {
        HStack(spacing: Spacing.m) {
            if let avatar {
                PersonAvatar(name: avatar.displayName, memberId: avatar.memberId, colorHue: avatar.colorHue)
            } else {
                Image(systemName: "person.3.fill")
                    .frame(width: 40, height: 40)
                    .background(Circle().fill(Palette.raised))
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Typeface.title)
                Text(last.map(preview) ?? "No messages yet")
                    .font(Typeface.small).foregroundStyle(Palette.secondaryText).lineLimit(1)
            }
            Spacer()
            if unread > 0 {
                Text("\(unread)")
                    .font(Typeface.label).foregroundStyle(Palette.onAmber)
                    .padding(.horizontal, Spacing.s).padding(.vertical, 2)
                    .background(Capsule().fill(Palette.amber))
                    .accessibilityLabel("\(unread) unread")
            }
        }
        .padding(.vertical, Spacing.xs)
    }

    private func preview(_ m: ChatMessage) -> String {
        let text = m.messageType == .location ? "Shared a location" : m.content
        return m.isOutgoing ? "You: \(text)" : text
    }
}

/// One conversation. `peer` nil is the whole family.
struct ConversationView: View {
    @EnvironmentObject private var model: AppModel
    let group: GroupDefinition
    let peer: FamilyMember?
    @State private var draft = ""
    @FocusState private var composing: Bool

    private var conversationId: String? {
        guard let peer else { return group.groupId }
        return model.chatMemberId.map { ChatBook.privateConversationId($0, peer.memberId) }
    }

    private var messages: [ChatMessage] { conversationId.map { model.chat.conversation($0) } ?? [] }
    private var peerStillHere: Bool { peer.map { p in group.members.contains { $0.memberId == p.memberId } } ?? true }

    var body: some View {
        Screen {
            ScrollViewReader { reader in
                ScrollView {
                    LazyVStack(spacing: Spacing.s) {
                        if messages.isEmpty {
                            Text(peer == nil ? "Messages here go to everyone in \(group.groupName)." :
                                    "Only you and \(peer!.displayName) can read this conversation.")
                                .font(Typeface.body).foregroundStyle(Palette.secondaryText)
                                .multilineTextAlignment(.center).padding(.top, Spacing.xl)
                        }
                        ForEach(messages) { message in
                            MessageBubble(message: message, sender: peer == nil && !message.isOutgoing ? group.findMember(byId: message.senderId) : nil)
                                .id(message.messageId)
                        }
                    }
                    .padding(.vertical, Spacing.m)
                }
                .onChange(of: messages.last?.messageId) { _, id in
                    if let id { withAnimation { reader.scrollTo(id, anchor: .bottom) } }
                    Task { await model.openConversation(conversationId) }
                }
                .onAppear { if let id = messages.last?.messageId { reader.scrollTo(id, anchor: .bottom) } }
            }
        }
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: Spacing.s) {
                TextField(peerStillHere ? "Message" : "\(peer?.displayName ?? "") left the family", text: $draft, axis: .vertical)
                    .lineLimit(1...5)
                    .padding(.vertical, Spacing.s)
                    .modifier(FieldStyle())
                    .focused($composing)
                    .disabled(!peerStillHere)
                Button {
                    let text = draft
                    draft = ""
                    Task { await model.sendChat(text, to: peer?.memberId) }
                } label: {
                    Image(systemName: "arrow.up").font(.system(size: 17, weight: .semibold))
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(Palette.amber))
                        .foregroundStyle(Palette.onAmber)
                }
                .accessibilityLabel("Send")
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !peerStillHere)
            }
            .padding(Spacing.m).background(Palette.background)
        }
        .navigationTitle(peer?.displayName ?? group.groupName)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.openConversation(conversationId) }
        .onDisappear { Task { await model.openConversation(nil) } }
    }
}

private struct MessageBubble: View {
    let message: ChatMessage
    /// Named above incoming messages in the family conversation.
    let sender: FamilyMember?

    var body: some View {
        HStack {
            if message.isOutgoing { Spacer(minLength: Spacing.xxl) }
            VStack(alignment: message.isOutgoing ? .trailing : .leading, spacing: 2) {
                if let sender {
                    Text(sender.displayName)
                        .font(Typeface.label)
                        .foregroundStyle(Palette.personText(PersonPalette.forMember(memberId: sender.memberId, colorHue: sender.colorHue)))
                        .padding(.leading, Spacing.xs)
                }
                Text(text)
                    .font(Typeface.body)
                    .foregroundStyle(message.isOutgoing ? Palette.onAmber : Palette.text)
                    .padding(.horizontal, 12).padding(.vertical, 10)
                    .background(bubble.fill(message.isOutgoing ? Palette.amber : Palette.surface))
                    .textSelection(.enabled)
                Text(footer).font(Typeface.small).foregroundStyle(message.status == .failed ? Palette.dangerText : Palette.secondaryText)
                    .padding(.horizontal, Spacing.xs)
            }
            if !message.isOutgoing { Spacer(minLength: Spacing.xxl) }
        }
        .accessibilityElement(children: .combine)
    }

    /// Chat bubbles keep their own asymmetric shape, a deliberate exception to the radius roles (DESIGN.md).
    private var bubble: UnevenRoundedRectangle {
        UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: message.isOutgoing ? 16 : 4,
                               bottomTrailingRadius: message.isOutgoing ? 4 : 16, topTrailingRadius: 16)
    }

    private var text: String {
        guard message.messageType == .location,
              let c = try? JSONDecoder().decode(ChatLocationContent.self, from: Data(message.content.utf8)) else { return message.content }
        return String(format: "Shared a location: %.5f, %.5f", c.latitude, c.longitude)
    }

    private var footer: String {
        let time = Date(timeIntervalSince1970: Double(message.timestamp) / 1000).formatted(date: .omitted, time: .shortened)
        guard message.isOutgoing else { return time }
        switch message.status {
        case .pending: return "\(time) · Sending…"
        case .sent: return "\(time) · Sent"
        case .delivered: return "\(time) · Delivered"
        case .read: return "\(time) · Read"
        case .failed: return "\(time) · Not sent"
        }
    }
}
