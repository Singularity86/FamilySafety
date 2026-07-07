# Family Safety

**Private, end-to-end encrypted family location sharing for Android.**

Family Safety lets a family see each other on a live map, chat, and get safety
alerts — with a design goal most location apps don't attempt: **nobody outside
the family can see any of it. Including us.**

🌐 [Website](https://singularity86.github.io/FamilySafety/) ·
🔒 [Privacy policy](https://singularity86.github.io/FamilySafety/privacy.html)

## How it stays private

- **No accounts.** No email, phone number, or password. Each device generates a
  BIP-39 recovery phrase; identity keys (Ed25519 + X25519) are derived from it
  via SLIP-10 and stored in the Android hardware-backed keystore.
- **End-to-end encryption.** Locations, messages, files, and group membership
  are encrypted per-recipient with libsodium (X25519 key agreement,
  XSalsa20-Poly1305, Ed25519 signatures). The MQTT relay that forwards messages
  between devices carries only ciphertext it cannot read.
- **No cloud storage.** History lives in an encrypted database (SQLCipher) on
  the family's own devices and is replicated between them as an encrypted
  backup. Location history is kept 30 days, chat 90 days.
- **Signed group membership.** Joining a family requires scanning a QR invite
  in person; membership changes are signed and validated by every device
  against an authorization policy (creator-or-self removals, no key rotation
  under an existing identity, hash-chained state versions).

## Features

- Live family map with location history and drive-time estimates (OSRM)
- Family group chat and 1-to-1 messages with delivery/read receipts
- Geofence place alerts (arrivals and departures)
- Optional vehicle crash detection with family alerts
- Direct device-to-device communication on shared Wi-Fi (no internet needed)
- Battery-aware tracking (motion-based GPS intervals)

## Architecture

| Layer | Package | What it does |
|---|---|---|
| UI | `main/`, `onboarding/`, `chat/` | Jetpack Compose screens + ViewModels |
| Sync | `sync/` | Signed group-state replication over MQTT |
| Transport | `transport/` | MQTT client, LAN-first routing, message framing |
| Crypto | `crypto/`, `group/` | libsodium E2EE, SLIP-10 key derivation |
| Storage | `storage/` | Room + SQLCipher encrypted database |
| Replication | `replication/` | Encrypted history backup between family devices |

Messages travel through per-recipient MQTT inbox topics
(`familysafe/{memberId}/…`); senders are identified by the encrypted
envelope's signature, not by the topic. Presence uses the MQTT last-will
mechanism and is the one plaintext signal (the broker publishes it on the
device's behalf, so it cannot be encrypted per-recipient) — see the
[privacy policy](https://singularity86.github.io/FamilySafety/privacy.html)
for exactly what the relay can and cannot see.

## Building

```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests
./gradlew bundleRelease        # Play Store bundle (requires keystore/, not in git)
```

Requires JDK 17 and the Android SDK (compile/target 35, min 26). The MQTT
broker URL lives in `app/src/main/java/.../transport/BrokerConfig.kt`; broker
credentials, if your broker requires them, go in `keystore/mqtt.properties`
(gitignored):

```
username=...
password=...
```

## Honest limitations

- The pairwise encryption uses static keys — no forward secrecy (a ratchet is
  a possible future upgrade). Message *content* is protected regardless.
- The relay sees traffic metadata (connection times, sizes, pseudonymous IDs)
  and plaintext online/offline status.
- All family devices must run compatible versions; wire formats occasionally
  change between releases.

## Contact

Questions, issues, or security reports: **omar@courageonpurpose.org** or open
an issue.
