import FamilySafetyCore
import MapKit
import SwiftUI

/// The places this phone watches, and the speed alert (B6). Local to this phone.
struct PlacesView: View {
    @EnvironmentObject private var model: AppModel
    let group: GroupDefinition
    @State private var speedOn = false
    @State private var threshold = 90

    var body: some View {
        Screen {
            List {
                Section {
                    ForEach(model.places.places) { place in
                        NavigationLink { PlaceEditor(group: group, place: place) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(place.name).font(Typeface.title)
                                Text(detail(place)).font(Typeface.small).foregroundStyle(Palette.secondaryText)
                            }
                        }
                    }
                    NavigationLink { PlaceEditor(group: group, place: nil) } label: {
                        Label("Add a place", systemImage: "plus").font(Typeface.body)
                    }
                } header: { Text("Places").font(Typeface.label) } footer: {
                    Text("You're told when someone arrives at or leaves a place. Places are kept on this phone only; the family isn't told about them.")
                        .font(Typeface.small)
                }
                .listRowBackground(Palette.surface)
                Section {
                    Toggle("Speed alerts", isOn: $speedOn).font(Typeface.body)
                    if speedOn {
                        Stepper("Over \(threshold) mph", value: $threshold, in: 20...150, step: 5).font(Typeface.body)
                    }
                } footer: {
                    Text("Tells you when someone in the family is going faster than this.").font(Typeface.small)
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .padding(.horizontal, -Spacing.m)
        }
        .navigationTitle("Places & alerts")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { speedOn = model.places.speedAlertsOn; threshold = model.places.speedThresholdMph }
        .onChange(of: speedOn) { _, on in Task { await model.setSpeedAlerts(on: on, thresholdMph: threshold) } }
        .onChange(of: threshold) { _, mph in Task { await model.setSpeedAlerts(on: speedOn, thresholdMph: mph) } }
    }

    private func detail(_ p: Place) -> String {
        let who = p.watchedMemberIds.isEmpty ? "Everyone"
            : group.members.filter { p.watchedMemberIds.contains($0.memberId) }.map(\.displayName).joined(separator: ", ")
        let what = [p.alertOnEnter ? "arrivals" : nil, p.alertOnExit ? "departures" : nil].compactMap { $0 }.joined(separator: " and ")
        return p.isEnabled ? "\(Int(p.radiusMeters)) m · \(who) · \(what.isEmpty ? "no alerts" : what)" : "Off"
    }
}

/// Move the map so the pin sits on the place, then name it and choose who to watch.
struct PlaceEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let group: GroupDefinition
    let place: Place?
    @State private var name = ""
    @State private var radius = 150.0
    @State private var center = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
    @State private var camera: MapCameraPosition = .automatic
    @State private var watched: Set<String> = []
    @State private var onEnter = true
    @State private var onExit = true
    @State private var enabled = true

    private var others: [FamilyMember] { group.members.filter { $0.memberId != model.chatMemberId } }

    var body: some View {
        Screen {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.m) {
                    Map(position: $camera) {
                        MapCircle(center: center, radius: radius).foregroundStyle(Palette.amber.opacity(0.18)).stroke(Palette.amber, lineWidth: 2)
                    }
                    .onMapCameraChange(frequency: .continuous) { context in center = context.region.center }
                    .overlay(Image(systemName: "mappin").font(.system(size: 28)).foregroundStyle(Palette.dangerText).offset(y: -14))
                    .frame(height: 260)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.control))
                    TextField("Name, like Home or School", text: $name).modifier(FieldStyle())
                    VStack(alignment: .leading) {
                        Text("Size: \(Int(radius)) m").font(Typeface.label)
                        Slider(value: $radius, in: 50...2000, step: 25).tint(Palette.amber)
                    }
                    Text("Who to watch").font(Typeface.label)
                    Toggle("Everyone", isOn: Binding(get: { watched.isEmpty }, set: { if $0 { watched = [] } })).font(Typeface.body)
                    ForEach(others, id: \.memberId) { m in
                        Toggle(m.displayName, isOn: Binding(get: { watched.contains(m.memberId) },
                                                            set: { if $0 { watched.insert(m.memberId) } else { watched.remove(m.memberId) } }))
                            .font(Typeface.body)
                    }
                    Toggle("Tell me when they arrive", isOn: $onEnter).font(Typeface.body)
                    Toggle("Tell me when they leave", isOn: $onExit).font(Typeface.body)
                    if place != nil { Toggle("On", isOn: $enabled).font(Typeface.body) }
                    Button("Save") { save() }.buttonStyle(PrimaryButtonStyle())
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    if let place {
                        Button("Delete place", role: .destructive) {
                            Task { await model.removePlace(place.id); dismiss() }
                        }.buttonStyle(SecondaryButtonStyle())
                    }
                }
                .padding(.vertical, Spacing.m)
            }
        }
        .navigationTitle(place == nil ? "New place" : place!.name)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: load)
    }

    private func load() {
        if let p = place {
            name = p.name; radius = p.radiusMeters; watched = p.watchedMemberIds
            onEnter = p.alertOnEnter; onExit = p.alertOnExit; enabled = p.isEnabled
            center = .init(latitude: p.latitude, longitude: p.longitude)
        } else if let me = model.chatMemberId, let fix = model.locations[me] {
            center = .init(latitude: fix.latitude, longitude: fix.longitude)
        }
        camera = .region(MKCoordinateRegion(center: center, latitudinalMeters: 1500, longitudinalMeters: 1500))
    }

    private func save() {
        let p = Place(id: place?.id ?? UUID().uuidString.lowercased(), name: name.trimmingCharacters(in: .whitespaces),
                      latitude: center.latitude, longitude: center.longitude, radiusMeters: radius, watchedMemberIds: watched,
                      alertOnEnter: onEnter, alertOnExit: onExit, isEnabled: enabled)
        Task { await model.savePlace(p); dismiss() }
    }
}
