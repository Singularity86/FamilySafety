"""Mints one grant code for one family — "give this family the paid features free," with an
optional expiry. Run entirely offline; never send the private key anywhere.

The resulting code only works for the exact groupId given here. A family's Settings screen
shows their own groupId for them to send you (the same way an invite code is shared) — ask
for that, don't guess it.

Usage:
    python tools/gen_grant_code.py <groupId>                    # never expires
    python tools/gen_grant_code.py <groupId> --days 365         # expires in 1 year
    python tools/gen_grant_code.py <groupId> --key path/to/key  # non-default key location

Needs: pip install pynacl
"""
import argparse
import base64
import json
import os
import time
from nacl.signing import SigningKey

DEFAULT_KEY_PATH = os.path.join(os.path.dirname(__file__), "..", "keystore", "grant-signing-key")


def signing_message(group_id: str, expiry_epoch_ms: int) -> bytes:
    # Must match GrantCode.signingMessage in the Kotlin app exactly, byte for byte.
    return f"GRANT:{group_id}:{expiry_epoch_ms}".encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("group_id", help="The family's groupId, exactly as shown in their app")
    parser.add_argument("--days", type=int, default=0, help="Expires this many days from now (0 = never expires)")
    parser.add_argument("--key", default=DEFAULT_KEY_PATH, help="Path to the private signing key")
    args = parser.parse_args()

    if not os.path.exists(args.key):
        raise SystemExit(
            f"No signing key at {args.key}. Run tools/gen_grant_signing_key.py once first."
        )
    with open(args.key) as f:
        signing_key = SigningKey(bytes.fromhex(f.read().strip()))

    expiry_epoch_ms = 0
    if args.days > 0:
        expiry_epoch_ms = int(time.time() * 1000) + args.days * 24 * 60 * 60 * 1000

    message = signing_message(args.group_id, expiry_epoch_ms)
    signature_hex = signing_key.sign(message).signature.hex()

    payload = json.dumps({"expiryEpochMs": expiry_epoch_ms, "signatureHex": signature_hex})
    code = base64.b64encode(payload.encode("utf-8")).decode("ascii")

    print(f"Group:  {args.group_id}")
    print(f"Expiry: {'never' if expiry_epoch_ms == 0 else f'{args.days} days from now'}")
    print()
    print(code)


if __name__ == "__main__":
    main()
