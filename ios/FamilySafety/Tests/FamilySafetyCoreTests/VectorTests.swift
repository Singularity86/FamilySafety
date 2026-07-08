import XCTest
@testable import FamilySafetyCore

/// Ground-truth vectors from IOS_PORT_SPEC.md §12, generated with real libsodium
/// (PyNaCl) via ../../tools/gen_test_vectors.py. Per the spec's phased plan (§14),
/// Phase 0 is "done" when every case in this file passes.
final class VectorTests: XCTestCase {

    let aliceMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let bobMnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    let groupId = "11111111-2222-3333-4444-555555555555"

    // MARK: - BIP-39 wordlist sanity (catches resource-bundling misconfiguration)

    func testWordlistGenerateAndValidateRoundTrip() throws {
        let words = try Bip39.generate12WordMnemonic()
        XCTAssertEqual(words.count, 12)
        XCTAssertTrue(try Bip39.validateMnemonic(words.joined(separator: " ")))
        XCTAssertFalse(try Bip39.validateMnemonic("not a real mnemonic phrase at all here"))
    }

    // MARK: - Identity A ("Alice")

    func testAliceIdentity() throws {
        let seed = Bip39.mnemonicToSeed(mnemonic: aliceMnemonic)
        XCTAssertEqual(
            Hex.encode(seed),
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        )

        let identity = try FamilySafeKeyDerivation.deriveIdentity(seed: seed)
        XCTAssertEqual(Hex.encode(identity.ed25519Seed), "a214b6228010d923e78eb067d734b926fcdcce2833a3da0b671e9dce9a2953c8")
        XCTAssertEqual(identity.ed25519PublicKeyHex, "061cca3d77168020734b44ea483a73797f262b0a2f1bf055f60cc7c3f27dd4f0")
        XCTAssertEqual(Hex.encode(identity.x25519PrivateKey), "2075e909585ee3c89e45c00107c1c2d620453fe0f872ac39fd5b831b9bc3b57c")
        XCTAssertEqual(identity.x25519PublicKeyHex, "edabb2cfdf3bc943d86e63be6f7cc71fabb3a4d287b4c86845d8749697d47a28")
        XCTAssertEqual(identity.memberId, "240dc306e6f9d2af4309c8ec30f0058e")
    }

    // MARK: - Identity B ("Bob")

    func testBobIdentity() throws {
        let seed = Bip39.mnemonicToSeed(mnemonic: bobMnemonic)
        XCTAssertEqual(
            Hex.encode(seed),
            "878386efb78845b3355bd15ea4d39ef97d179cb712b77d5c12b6be415fffeffe5f377ba02bf3f8544ab800b955e51fbff09828f682052a20faa6addbbddfb096"
        )

        let identity = try FamilySafeKeyDerivation.deriveIdentity(seed: seed)
        XCTAssertEqual(Hex.encode(identity.ed25519Seed), "2414440fdec6d167a475fc99cb23734af50aefbcd66687795735bec07efa4d46")
        XCTAssertEqual(identity.ed25519PublicKeyHex, "f93f2139ee8553887745ef5210f10bcd3efb5201fe39522dc3d7289f2826edae")
        XCTAssertEqual(Hex.encode(identity.x25519PrivateKey), "584dbb39684be08e09bc43daf8314a94242dca6ea8a59fa887f92486fff18c67")
        XCTAssertEqual(identity.x25519PublicKeyHex, "bc5d571ecdb705f52f6119bae82be2d5aa92c61a0ed98b979489c9e57d671e69")
        XCTAssertEqual(identity.memberId, "146002794e05db8fea5ac6f48c319e8c")
    }

    // MARK: - E2EE envelope, Alice -> Bob (fixed nonce from the vector)

    func testE2EEEnvelopeAliceToBob() throws {
        let alice = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: aliceMnemonic)
        let bob = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: bobMnemonic)

        let sharedFromAlice = try SodiumRaw.boxBeforenm(recipientPublicKey: bob.x25519PublicKey, senderSecretKey: alice.x25519PrivateKey)
        let sharedFromBob = try SodiumRaw.boxBeforenm(recipientPublicKey: alice.x25519PublicKey, senderSecretKey: bob.x25519PrivateKey)
        XCTAssertEqual(sharedFromAlice, sharedFromBob, "shared secret must be symmetric")
        XCTAssertEqual(Hex.encode(sharedFromAlice), "9c57a8617544ec897c2ed42534ad889f1858dd860bd3eac7480cd06087cbe9d2")

        guard let nonce = Hex.decode("000102030405060708090a0b0c0d0e0f1011121314151617"),
              let ciphertext = Hex.decode("10763b3494d286388ea8a19697f07b9ba88288a51f51014b0d3c69e854226deb"),
              let signature = Hex.decode("a9e6dc38c05b1e2425fc35c6795d52fbcba01032853adcd7acd55207ac76d5fea84af4a2070da75fc58f92a14a7b88d8138fd583a04f384bef769ac77159af09")
        else {
            XCTFail("failed to decode fixture hex")
            return
        }

        XCTAssertTrue(SodiumRaw.signVerifyDetached(signature: signature, message: nonce + ciphertext, publicKey: alice.ed25519PublicKey))

        guard let plaintextBytes = SodiumRaw.secretboxOpenEasy(ciphertext: ciphertext, nonce: nonce, key: sharedFromBob) else {
            XCTFail("secretbox open failed")
            return
        }
        XCTAssertEqual(String(decoding: plaintextBytes, as: UTF8.self), "hello from alice")
    }

    // MARK: - Group state hash + signatures

    func testGroupStateHashAndSignatures() throws {
        let alice = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: aliceMnemonic)
        let bob = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: bobMnemonic)

        let aliceMember = FamilyMember(
            memberId: alice.memberId,
            displayName: "Alice",
            ed25519PublicKey: alice.ed25519PublicKeyHex,
            x25519PublicKey: alice.x25519PublicKeyHex,
            addedAtEpochMs: 1_700_000_000_000
        )
        let bobMember = FamilyMember(
            memberId: bob.memberId,
            displayName: "Bob",
            ed25519PublicKey: bob.ed25519PublicKeyHex,
            x25519PublicKey: bob.x25519PublicKeyHex,
            addedAtEpochMs: 1_700_000_000_000
        )

        let group = GroupDefinition(
            groupId: groupId,
            groupName: "Test Family",
            createdAtEpochMs: 1_700_000_000_000,
            creatorMemberId: alice.memberId,
            members: [aliceMember, bobMember],
            version: 2
        )

        XCTAssertEqual(group.computeStateHash(), "932d4307c8ceb3e574515bb734267d7d94f0ed876ab5844b7a4ef5cf9c4c0cbd")

        let syncPayload = GroupSignaturePayloads.syncSignature(
            groupId: groupId,
            version: 2,
            updaterMemberId: alice.memberId,
            timestamp: 1_700_000_001_000,
            groupDefinition: group
        )
        XCTAssertEqual(
            syncPayload,
            "11111111-2222-3333-4444-555555555555|2|240dc306e6f9d2af4309c8ec30f0058e|1700000001000|932d4307c8ceb3e574515bb734267d7d94f0ed876ab5844b7a4ef5cf9c4c0cbd"
        )
        let syncSignature = try SodiumRaw.signDetached(message: Array(syncPayload.utf8), secretKey: alice.ed25519SecretKey64)
        XCTAssertEqual(
            Hex.encode(syncSignature),
            "7369363820e6719d024dbee66f5935fbe0846ed861183264038d04616beefd66e68afffa32f08e92a20cc7a7f4146b2e2cb0ae4e124b33921aa56ea4e24ca30d"
        )

        let approvalPayload = GroupSignaturePayloads.memberAddApproval(
            groupId: groupId,
            preAddVersion: 1,
            newMemberEd25519PublicKeyHex: bob.ed25519PublicKeyHex
        )
        XCTAssertEqual(
            approvalPayload,
            "ADD:11111111-2222-3333-4444-555555555555:1:f93f2139ee8553887745ef5210f10bcd3efb5201fe39522dc3d7289f2826edae"
        )
        let approvalSignature = try SodiumRaw.signDetached(message: Array(approvalPayload.utf8), secretKey: alice.ed25519SecretKey64)
        XCTAssertEqual(
            Hex.encode(approvalSignature),
            "34d343dff90717fc067e47957c947a83f10d42336d26d3e0130793213f93f5c3bfd66d430dc8a5957dd525c274007530643e2ecaca21bac9e907b3bd34efb90a"
        )
    }

    // MARK: - Shared-file key

    func testSharedFileKey() {
        let key = SharedFileCrypto.deriveFileKey(groupId: groupId)
        XCTAssertEqual(Hex.encode(key), "615d9ec6399937470f89418ab98e10f3107c56d7a09b6bf234263d8ad842becd")
    }

    // MARK: - Full envelope round-trip (not a fixed vector — exercises encrypt + decrypt)

    func testEnvelopeRoundTrip() throws {
        let alice = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: aliceMnemonic)
        let bob = try FamilySafeKeyDerivation.deriveIdentity(mnemonic: bobMnemonic)

        let aliceCrypto = E2EECrypto(
            localMemberId: alice.memberId,
            localEd25519SecretKey: alice.ed25519SecretKey64,
            localX25519SecretKey: alice.x25519PrivateKey
        )
        let bobCrypto = E2EECrypto(
            localMemberId: bob.memberId,
            localEd25519SecretKey: bob.ed25519SecretKey64,
            localX25519SecretKey: bob.x25519PrivateKey
        )

        let envelopeData = try aliceCrypto.encrypt(
            plaintext: "round trip test",
            recipientMemberId: bob.memberId,
            recipientX25519PublicKey: bob.x25519PublicKey
        )

        let decrypted = try bobCrypto.decrypt(
            envelopeJSON: envelopeData,
            senderMemberId: alice.memberId,
            senderX25519PublicKey: alice.x25519PublicKey,
            senderEd25519PublicKey: alice.ed25519PublicKey
        )
        XCTAssertEqual(decrypted, "round trip test")
    }
}
