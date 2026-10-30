import FamilySafetyCore
import MapKit
import SwiftUI

/// Where one person has been on one day (B6): their trail in their colour, from the 30 days of
/// positions this phone holds. Nothing here leaves the phone.
struct HistoryView: View {
    @EnvironmentObject private var model: AppModel
    let member: FamilyMember
    @State private var day = Calendar.current.startOfDay(for: Date())
    @State private var fixes: [LocationUpdate] = []
    @State private var camera: MapCameraPosition = .automatic

    private var days: [Date] {
        (0..<7).compactMap { Calendar.current.date(byAdding: .day, value: -$0, to: Calendar.current.startOfDay(for: Date())) }
    }

    private var colour: Color {
        Color(hex: PersonPalette.forMember(memberId: member.memberId, colorHue: member.colorHue).textOnLight)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            Map(position: $camera) {
                if fixes.count > 1 {
                    MapPolyline(coordinates: fixes.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) })
                        .stroke(colour, style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
                }
                if let last = fixes.last {
                    Annotation(member.displayName, coordinate: .init(latitude: last.latitude, longitude: last.longitude), anchor: .center) {
                        PersonAvatar(name: member.displayName, memberId: member.memberId, colorHue: member.colorHue, size: 30)
                            .overlay(Circle().stroke(Palette.surface, lineWidth: 3))
                    }
                }
            }
            .mapStyle(.standard(pointsOfInterest: .excludingAll))
            VStack(alignment: .leading, spacing: Spacing.s) {
                Picker("Day", selection: $day) {
                    ForEach(days, id: \.self) { d in Text(label(d)).tag(d) }
                }
                .pickerStyle(.menu)
                .tint(Palette.text)
                Text(summary).font(Typeface.small).foregroundStyle(Palette.secondaryText)
            }
            .padding(Spacing.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.control))
            .padding(Spacing.m)
        }
        .navigationTitle(member.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: day) {
            fixes = await model.history(of: member.memberId, day: day)
            camera = .automatic
        }
    }

    private func label(_ d: Date) -> String {
        if Calendar.current.isDateInToday(d) { return "Today" }
        if Calendar.current.isDateInYesterday(d) { return "Yesterday" }
        return d.formatted(.dateTime.weekday(.wide).day().month())
    }

    private var summary: String {
        guard let first = fixes.first, let last = fixes.last else {
            return "No positions from \(member.displayName) on this day."
        }
        let time = { (f: LocationUpdate) in Date(timeIntervalSince1970: Double(f.timestamp) / 1000).formatted(date: .omitted, time: .shortened) }
        return fixes.count == 1 ? "One position, at \(time(first))." : "\(fixes.count) positions, \(time(first)) to \(time(last))."
    }
}
