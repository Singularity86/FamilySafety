#!/bin/sh
# Writes Config/Secrets.xcconfig from the Mac Keychain entry the harnesses already use
# (service "FamilySafety-MQTT"). The values are hex so no character can break the xcconfig.
# The file is gitignored; never commit it.
set -eu
here="$(cd "$(dirname "$0")/.." && pwd)"
user=$(security find-generic-password -s FamilySafety-MQTT | sed -n 's/.*"acct"<blob>="\(.*\)"/\1/p')
pass=$(security find-generic-password -s FamilySafety-MQTT -w)
[ -n "$user" ] && [ -n "$pass" ] || { echo "No FamilySafety-MQTT Keychain entry" >&2; exit 1; }
hex() { printf '%s' "$1" | xxd -p | tr -d '\n'; }
umask 077
printf 'FS_BROKER_USERNAME_HEX = %s\nFS_BROKER_PASSWORD_HEX = %s\n' "$(hex "$user")" "$(hex "$pass")" > "$here/Config/Secrets.xcconfig"
echo "Wrote $here/Config/Secrets.xcconfig"
