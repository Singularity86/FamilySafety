"""One-time setup: generates the keypair that signs grant codes ("give this family the
paid features free" — see app/src/main/java/com/example/familysafety/billing/GrantCode.kt).

Run this once. It writes the PRIVATE key to keystore/grant-signing-key, which is already
gitignored (see .gitignore: "keystore/") — never commit it, never move it out of that
folder, and back it up the same way the Play upload keystore already is: off this machine,
somewhere durable. Losing it just means you can't mint new codes going forward; every code
already issued keeps working. Leaking it means anyone who has it can forge a grant for any
family whose groupId they know, forever (or until whatever expiry that code carries) — so
guard it like the app-signing key, not like a password.

The PUBLIC key it prints is the opposite: paste it into BillingConfig.GRANT_PUBLIC_KEY_HEX
in the app and commit that. It can only verify a code, never create one.

Usage: python tools/gen_grant_signing_key.py
Needs: pip install pynacl (same dependency ios/tools/gen_test_vectors.py already uses)
"""
import os
from nacl.signing import SigningKey

KEY_PATH = os.path.join(os.path.dirname(__file__), "..", "keystore", "grant-signing-key")

def main():
    if os.path.exists(KEY_PATH):
        raise SystemExit(
            f"{KEY_PATH} already exists — refusing to overwrite it. A new key invalidates "
            "every code already issued under the old one, since the public key baked into "
            "the app would no longer match. Delete it yourself first if that's really what "
            "you want."
        )

    signing_key = SigningKey.generate()
    private_seed_hex = signing_key.encode().hex()
    public_key_hex = signing_key.verify_key.encode().hex()

    os.makedirs(os.path.dirname(KEY_PATH), exist_ok=True)
    with open(KEY_PATH, "w") as f:
        f.write(private_seed_hex + "\n")
    os.chmod(KEY_PATH, 0o600)

    print(f"Private key written to {KEY_PATH} (keep this off git, back it up somewhere safe).")
    print()
    print("Paste this into BillingConfig.GRANT_PUBLIC_KEY_HEX and commit that change:")
    print()
    print(f"    const val GRANT_PUBLIC_KEY_HEX = \"{public_key_hex}\"")

if __name__ == "__main__":
    main()
