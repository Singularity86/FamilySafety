import FamilySafetyCore
import MapKit
import SwiftUI

/// Where everyone is (B2). Apple Maps draws the tiles, so Apple sees which area is being viewed,
/// as with any map app; positions themselves never leave the family encrypted.
struct FamilyMapView: View {
    @EnvironmentObject private var model: AppModel
    let group: GroupDefinition
    @State private var camera: MapCameraPosition = .automatic

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                Map(position: $camera) {
                    ForEach(placed, id: \.member.memberId) { item in
                        Annotation(item.member.displayName, coordinate: item.coordinate, anchor: .center) {
                            PersonAvatar(name: item.member.displayName, memberId: item.member.memberId,
                                         colorHue: item.member.colorHue, size: 36)
                                .overlay(Circle().stroke(Palette.surface, lineWidth: 3))
                                .opacity(item.stale ? 0.55 : 1)
                        }
                    }
                }
                .mapStyle(.standard(pointsOfInterest: .excludingAll))
                if placed.isEmpty {
                    Text(model.sharingPaused
                         ? "Your location sharing is paused. Turn it on in Settings."
                         : "No one's location yet. Positions appear here as family members open the app.")
                        .font(Typeface.small).foregroundStyle(Palette.text)
                        .padding(Spacing.m).background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
                        .padding(Spacing.m)
                } else {
                    MapLegend(items: placed).padding(Spacing.m)
                }
            }
            .navigationTitle("Map")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    struct Placed {
        let member: FamilyMember
        let fix: LocationUpdate
        var coordinate: CLLocationCoordinate2D { .init(latitude: fix.latitude, longitude: fix.longitude) }
        var date: Date { Date(timeIntervalSince1970: Double(fix.timestamp) / 1000) }
        /// Older than 15 minutes: shown faded so an old spot isn't mistaken for now.
        var stale: Bool { Date().timeIntervalSince(date) > 15 * 60 }
    }

    private var placed: [Placed] {
        group.members.compactMap { m in model.locations[m.memberId].map { Placed(member: m, fix: $0) } }
            .sorted { $0.member.displayName.localizedCaseInsensitiveCompare($1.member.displayName) == .orderedAscending }
    }
}

/// Who's on the map and how fresh each position is.
private struct MapLegend: View {
    @EnvironmentObject private var model: AppModel
    let items: [FamilyMapView.Placed]

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            ForEach(items, id: \.member.memberId) { item in
                NavigationLink { HistoryView(member: item.member) } label: {
                HStack(spacing: Spacing.s) {
                    PersonAvatar(name: item.member.displayName, memberId: item.member.memberId, colorHue: item.member.colorHue, size: 20)
                    Text(item.member.memberId == model.chatMemberId ? "\(item.member.displayName) (you)" : item.member.displayName)
                        .font(Typeface.small).foregroundStyle(Palette.text)
                    Spacer()
                    RelativeTime(date: item.date)
                        .font(Typeface.small).foregroundStyle(item.stale ? Palette.warningText : Palette.secondaryText)
                    Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(Palette.secondaryText)
                }
                }
                .accessibilityHint("Shows where they've been")
            }
        }
        .padding(Spacing.m)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// "2 minutes ago", refreshed every 30 s, so it never freezes and never counts seconds.
struct RelativeTime: View {
    let date: Date
    var prefix = ""

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            Text(prefix + date.formatted(.relative(presentation: .named, unitsStyle: .wide)))
        }
    }
}
