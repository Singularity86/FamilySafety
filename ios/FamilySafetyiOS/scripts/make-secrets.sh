#!/bin/sh
# Writes Config/Secrets.xcconfig from the Mac Keychain. The values are hex so no character can
# break the xcconfig. The file is gitignored; never commit it.
#   Debug   = "FamilySafety-MQTT", the separate test login the harnesses already use.
#   Release = "FamilySafety-MQTT-prod", the login the shipped app uses. Without that entry a
#             Release build gets no login at all (the app says so) rather than the test one.
set -eu
here="$(cd "$(dirname "$0")/.." && pwd)"
acct() { security find-generic-password -s "$1" 2>/dev/null | sed -n 's/.*"acct"<blob>="\(.*\)"/\1/p'; }
pass() { security find-generic-password -s "$1" -w 2>/dev/null || true; }
hex() { printf '%s' "$1" | xxd -p | tr -d '\n'; }

user=$(acct FamilySafety-MQTT); pw=$(pass FamilySafety-MQTT)
[ -n "$user" ] && [ -n "$pw" ] || { echo "No FamilySafety-MQTT Keychain entry" >&2; exit 1; }
prod_user=$(acct FamilySafety-MQTT-prod); prod_pw=$(pass FamilySafety-MQTT-prod)
if [ -z "$prod_user" ] || [ -z "$prod_pw" ]; then
    echo "No FamilySafety-MQTT-prod Keychain entry: Release builds will have no broker login" >&2
    prod_user=; prod_pw=
fi

umask 077
{
    printf 'FS_BROKER_USERNAME_HEX = %s\nFS_BROKER_PASSWORD_HEX = %s\n' "$(hex "$user")" "$(hex "$pw")"
    printf 'FS_BROKER_USERNAME_HEX[config=Release] = %s\nFS_BROKER_PASSWORD_HEX[config=Release] = %s\n' \
        "$(hex "$prod_user")" "$(hex "$prod_pw")"
} > "$here/Config/Secrets.xcconfig"
[ -n "$prod_user" ] && release=set || release=none
echo "Wrote $here/Config/Secrets.xcconfig (Release login: $release)"
