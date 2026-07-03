"""Generate cross-platform test vectors for the FamilySafety iOS port.

Uses real libsodium (PyNaCl) for all curve operations, so vectors are
ground truth identical to Lazysodium on Android. BIP-39/SLIP-10 use
hashlib/hmac and are self-checked against the published BIP-39 vector.
Mirrors: Bip39.kt, Slip10KeyDerivation.kt, LazysodiumCryptoProvider.kt,
E2EEManager.kt, Models.kt (computeStateHash), GroupSyncManager.kt.
"""
import hashlib, hmac, json
import nacl.bindings as sod
from nacl.signing import SigningKey

# ---------------- BIP-39 ----------------
def bip39_seed(mnemonic: str, passphrase: str = "") -> bytes:
    norm = " ".join(mnemonic.strip().lower().split())
    return hashlib.pbkdf2_hmac("sha512", norm.encode(), ("mnemonic" + passphrase).encode(), 2048, 64)

# self-check against published BIP-39 test vector (empty passphrase)
_v = bip39_seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
assert _v.hex() == ("5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1"
                    "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"), "BIP39 self-check failed"

# ---------------- SLIP-10 (ed25519 curve) ----------------
HARDENED = 0x80000000

def slip10_master(seed: bytes):
    h = hmac.new(b"ed25519 seed", seed, hashlib.sha512).digest()
    return h[:32], h[32:]

def slip10_ckd(k: bytes, c: bytes, index: int):
    i = index | HARDENED
    data = b"\x00" + k + i.to_bytes(4, "big")
    h = hmac.new(c, data, hashlib.sha512).digest()
    return h[:32], h[32:]

def slip10_derive(seed: bytes, path):
    k, c = slip10_master(seed)
    for i in path:
        k, c = slip10_ckd(k, c, i)
    return k

def clamp_x25519(k: bytes) -> bytes:
    b = bytearray(k)
    b[0] &= 0xF8
    b[31] = (b[31] & 0x7F) | 0x40
    return bytes(b)

def derive_identity(mnemonic: str):
    seed = bip39_seed(mnemonic)
    ed_seed = slip10_derive(seed, [44, 1984, 0, 0])
    ed_pub, ed_sk64 = sod.crypto_sign_seed_keypair(ed_seed)
    x_priv = clamp_x25519(slip10_derive(seed, [44, 1984, 0, 1]))
    x_pub = sod.crypto_scalarmult_base(x_priv)
    member_id = hashlib.sha256(ed_pub).digest()[:16].hex()
    return dict(mnemonic=mnemonic, seed=seed.hex(), ed_seed=ed_seed.hex(),
                ed_pub=ed_pub.hex(), x_priv=x_priv.hex(), x_pub=x_pub.hex(),
                member_id=member_id, ed_sk64=ed_sk64)

alice = derive_identity("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
bob   = derive_identity("legal winner thank year wave sausage worth useful legal winner thank yellow")

def show(name, ident):
    print(f"### {name}")
    print(f"mnemonic:            {ident['mnemonic']}")
    print(f"bip39_seed:          {ident['seed']}")
    print(f"ed25519_seed (m/44'/1984'/0'/0'): {ident['ed_seed']}")
    print(f"ed25519_pub:         {ident['ed_pub']}")
    print(f"x25519_priv (m/44'/1984'/0'/1', clamped): {ident['x_priv']}")
    print(f"x25519_pub:          {ident['x_pub']}")
    print(f"memberId:            {ident['member_id']}")
    print()

show("Alice", alice)
show("Bob", bob)

# ---------------- E2EE envelope: Alice -> Bob ----------------
shared_ab = sod.crypto_box_beforenm(bytes.fromhex(bob["x_pub"]), bytes.fromhex(alice["x_priv"]))
shared_ba = sod.crypto_box_beforenm(bytes.fromhex(alice["x_pub"]), bytes.fromhex(bob["x_priv"]))
assert shared_ab == shared_ba
print("### E2EE envelope (Alice -> Bob)")
print(f"shared_secret (crypto_box_beforenm): {shared_ab.hex()}")

nonce = bytes(range(24))  # fixed nonce 000102...17 for the vector
plaintext = b"hello from alice"
ct = sod.crypto_secretbox(plaintext, nonce, shared_ab)  # MAC(16) || ct
sig = SigningKey(bytes.fromhex(alice["ed_seed"])).sign(nonce + ct).signature
envelope = {
    "senderMemberId": alice["member_id"],
    "nonce": nonce.hex(),
    "ciphertext": ct.hex(),
    "signature": sig.hex(),
}
print(f"plaintext (utf8):    {plaintext.decode()}")
print(f"nonce:               {nonce.hex()}")
print(f"ciphertext:          {ct.hex()}")
print(f"signature:           {sig.hex()}")
print("envelope JSON:")
print(json.dumps(envelope, separators=(",", ":")))
print()

# ---------------- GroupDefinition.computeStateHash ----------------
group = dict(
    groupId="11111111-2222-3333-4444-555555555555",
    groupName="Test Family",
    createdAtEpochMs=1700000000000,
    creatorMemberId=alice["member_id"],
    version=2,
)
members = sorted([alice, bob], key=lambda m: m["member_id"])
canonical = (f"{group['groupId']}|{group['groupName']}|{group['createdAtEpochMs']}|"
             f"{group['creatorMemberId']}|{group['version']}|")
for m in members:
    canonical += f"{m['member_id']},{m['ed_pub']},{m['x_pub']};"
state_hash = hashlib.sha256(canonical.encode()).hexdigest()
print("### GroupDefinition.computeStateHash")
print(f"canonical string:    {canonical}")
print(f"state_hash:          {state_hash}")
print()

# ---------------- Group sync signature ----------------
ts = 1700000001000
sync_payload = f"{group['groupId']}|{group['version']}|{alice['member_id']}|{ts}|{state_hash}"
sync_sig = SigningKey(bytes.fromhex(alice["ed_seed"])).sign(sync_payload.encode()).signature
print("### GroupSyncMessage signature (signed by Alice)")
print(f"payload string:      {sync_payload}")
print(f"signature:           {sync_sig.hex()}")
print()

# ---------------- Member-add approval signature ----------------
approval = f"ADD:{group['groupId']}:1:{bob['ed_pub']}"
approval_sig = SigningKey(bytes.fromhex(alice["ed_seed"])).sign(approval.encode()).signature
print("### Member-add approval signature (Alice approves Bob, pre-add version=1)")
print(f"payload string:      {approval}")
print(f"signature:           {approval_sig.hex()}")
print()

# ---------------- Group file key ----------------
file_key = hashlib.sha256((group["groupId"] + "familysafety-files-v1").encode()).hexdigest()
print("### Shared-file AES-256-GCM key")
print(f"groupId:             {group['groupId']}")
print(f"file_key:            {file_key}")
