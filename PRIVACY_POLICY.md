# Jibaro Family Safety — Privacy Policy

_Last updated: September 19, 2026_

Jibaro Family Safety is a private, family-only location-sharing app. It was built on a
simple principle: **your family's location and messages belong to your family,
not to us.** We operate no accounts, no analytics, and no server that can read
your data.

## What the app collects, and who can see it

| Data | Purpose | Who can see it |
|---|---|---|
| Precise location (including in the background) | Real-time location sharing, 30-day location history, place and speed alerts, and crash detection for your family group | Only members of your family group |
| Chat messages and shared files | Family messaging | Only members of your family group |
| Display name, avatar photo, color choice | Identifying you inside your group | Only members of your family group |
| Phone motion sensor (crash detection, only while you are driving) | Detecting a likely crash; if you do not respond within 60 seconds an alert is sent to your family group | Processed on your device; only the alert is shared, with your family group |
| Motion/activity state (moving vs. still) | Adjusting GPS frequency to save battery | Processed on your device only |
| Online/offline status | Showing whether a family member's device is connected | Only members of your family group |

The app does **not** collect contacts, advertising identifiers, or usage
analytics, and contains no third-party advertising or tracking SDKs.

## End-to-end encryption

Locations, messages, files, and group membership data are end-to-end encrypted
on your device using libsodium (X25519 key agreement, XSalsa20-Poly1305
encryption, Ed25519 signatures). Encryption keys are derived from a recovery
phrase generated on your device and are stored in your device's
hardware-backed keystore. **We never possess your keys and cannot decrypt your
data.** There is no password reset and no backdoor: if you lose your recovery
phrase and your devices, your data is unrecoverable — by design.

## Message relay (the one server involved)

Encrypted messages travel through an MQTT relay server, which functions like a
post office for sealed envelopes: it forwards ciphertext but cannot read it.
The relay necessarily sees some network metadata: connection times, pseudonymous
member identifiers, and how often each device sends something. Because location
updates are sent when a device moves, the relay can infer *that* someone is
moving, though never where. It never sees locations, messages, names, photos,
file names, or online/offline status in readable form.

Messages are padded to fixed sizes, so their length reveals nothing about their
contents.

## Where your data lives

Your family's data is stored on **your family's own devices**, in an encrypted
database (SQLCipher), and is replicated between family members' devices as an
encrypted backup. Location history is kept for 30 days and chat messages for
90 days, then deleted automatically. There is no cloud copy we hold.

## Outside services the app contacts

We do not sell or rent your data, and we do not use advertising or analytics
services. Two features contact outside services directly, **not** through the
encrypted family channel:

- **Map tiles (OpenStreetMap).** When you view the map, the app downloads map
  images from openstreetmap.org. That service sees your IP address and which part
  of the map you are viewing.
- **Drive-time estimates (OSRM).** Only when you tap a drive-time estimate for a
  family member, the app sends your location and that member's most recent
  location to the public routing service at router.project-osrm.org, which also
  sees your IP address. Nothing is sent unless you ask for an estimate.

Apart from these and the message relay described above, there are no other third
parties.

## Children

Jibaro Family Safety is intended to be set up and administered by adults. A parent or
guardian may choose to install it on a child's device as part of their family
group; the child's location is then visible only to that family group, under
the same encryption described above.

## Your controls

- **Pause location sharing** at any time (Settings → Share my location). While
  paused, your phone sends no locations, and place, speed and crash alerts from
  your phone are off. Chat still works.
- **Leave a family** at any time (Settings → Leave Family): your device wipes
  its group data and tells the group to stop sharing with you.
- **Remove a member** (group creator): the removed device stops sharing and is
  removed from every member's app.
- **Delete conversations and history** from within the app; automatic
  retention limits apply regardless.
- **Uninstalling the app** removes all app data from the device.

Because data is replicated among family devices as an encrypted backup, copies
of past shared data may remain on your family members' devices until their
retention windows expire.

## Permissions the app requests

- **Location (all the time)** — the app's core function: sharing your location
  with your family, including when the app is closed or not in use.
- **Alarms and full-screen alerts** — precise alarms keep location sharing running
  when the phone is idle; a full-screen alert asks whether you are okay after a
  likely crash.
- **Notifications** — safety alerts, place arrivals, crash alerts, messages.
- **Camera** — scanning family invite QR codes only; no images are captured.
- **Physical activity** — detecting driving/walking to tune GPS battery use.
- **Nearby Wi-Fi devices** — direct device-to-device sharing on home Wi-Fi.
- **Battery optimization exemption** — keeps location sharing alive in the
  background; without it some phones stop the app during long idle periods.

## Changes and contact

If this policy changes, the updated version will be posted at this address
with a new date. Questions: **coloredhatsociety@gmail.com**
