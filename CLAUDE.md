# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run all unit tests
./gradlew testDebugUnitTest

# Run a single test class
./gradlew testDebugUnitTest --tests "com.example.familysafety.core.ErrorHandlerTest"

# Run a single test method
./gradlew testDebugUnitTest --tests "com.example.familysafety.core.ErrorHandlerTest.withRetry_succeedsAfterRetries"

# Lint
./gradlew lint
```

## Architecture Overview

**FamilySafety** is a privacy-first family location-sharing Android app. All communication is end-to-end encrypted and routed through MQTT. There is no central server that stores plaintext data.

### Key Layers

| Layer | Package | Purpose |
|---|---|---|
| UI | `main/`, `onboarding/`, `chat/` | Compose screens + ViewModels |
| Sync | `sync/` | Group state replication over MQTT |
| Transport | `transport/` | MQTT client, message framing, pending queue |
| Crypto | `crypto/`, `group/` | E2EE (libsodium via Lazysodium), key derivation |
| Storage | `storage/` | Room + SQLCipher encrypted DB |
| Core | `core/` | ErrorHandler, NetworkMonitor, RateLimiter, DataValidator |

### Group Identity & Cryptography

- **`Models.kt`** — Core data types: `FamilyMember`, `GroupDefinition`, `ConnectionState`
- **`Bip39.kt`** — BIP-39 mnemonic generation; must call `Bip39.initialize(wordlist)` before use
- **`Slip10KeyDerivation.kt`** — SLIP-10 key derivation from mnemonic seed; path `m/44'/1984'/0'/keyType'`
- **`LazysodiumCryptoProvider.kt`** — Wraps Lazysodium (libsodium JNA); Ed25519 signing + X25519 encryption
- **`GroupStateManager.kt`** — Single source of truth for current group; emits `GroupStateEvent` flows
- **`GroupStatePersistence.kt`** — DataStore-backed persistence of `GroupDefinition`

### Transport

- **`MqttTransport.kt`** — Singleton MQTT client; exponential reconnect backoff; pending message queue (max 100, 1hr expiry); routes incoming messages by topic type (chat, location, presence, replication)
- **`MessageProtocol.kt`** — JSON encode/decode for `LocationUpdate`, `PresenceUpdate`, `Envelope`
- **`BrokerConfig.kt`** / **`MqttConfig.kt`** — Broker URL and topic namespace constants
- Each member's inbox topic: `inbox/SHA-256(ed25519PubKey)` (computed in `FamilyMember.mqttInboxTopic`)

### Sync

- **`GroupSyncManager.kt`** — Broadcasts signed group state over MQTT; handles incoming sync messages; resolves version conflicts via monotonic version field + hash chain (`previousStateHash`)

### Dependency Injection

Hilt is used throughout. Modules:
- `AppModule`, `CoreModule`, `StorageModule`, `CryptoModule`, `TransportModule`, `LocationModule`, `SyncModule`, `ReplicationModule`, `ChatModule`, `GroupStateModule`

`MqttTransport` has a circular dependency with `ReplicationManager` and `ChatRepository`; these are set via setter injection after construction.

### Navigation

- App starts at `MainActivity`, which decides between `OnboardingNavigation` (no group) and `MainScreen` (has group)
- Onboarding: Welcome → EnterName → GenerateMnemonic → ConfirmMnemonic → CreateFamily (or JoinFamily via QR scan)
- Main: bottom-nav tabs — Map, Members, Chat, Settings

## Testing Patterns

Tests live in `app/src/test/java/com/example/familysafety/`. Framework: JUnit 4 + MockK + `kotlinx-coroutines-test` + Turbine (for Flows).

```kotlin
// Coroutine test pattern
@Test
fun myTest() = runTest {
    // ...
}

// Flow collection with Turbine
myFlow.test {
    assertEquals(expected, awaitItem())
    cancelAndIgnoreRemainingEvents()
}
```

`Bip39` requires initialization before use in tests — it reads the wordlist from an Android resource file; in unit tests you must supply the wordlist manually via `Bip39.initialize(wordlist)`.

## Known Deprecation Warnings (non-blocking)

- `Icons.Filled.ArrowBack` → use `Icons.AutoMirrored.Filled.ArrowBack`
- `@OptIn(ExperimentalCoroutinesApi::class)` needed on coroutine test utilities
