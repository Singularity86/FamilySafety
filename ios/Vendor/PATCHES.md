# Vendored dependency patches

## CocoaMQTT 2.4.1 (`Vendor/CocoaMQTT`)

Upstream: https://github.com/emqx/CocoaMQTT at 4c5a9a68 (tag 2.4.1). Example/, Tools/, the Xcode
project and the podspec were dropped; everything else is unmodified except:

1. `Source/CocoaMQTT.swift`: added `public internal(set) var sessionPresent: Bool`, assigned from
   the CONNACK in `didReceive(_:connack:)` before `didConnectAck` is dispatched. Needed so
   `MqttTransport` can skip SUBSCRIBE when the broker kept the session (IOS_PORT_SPEC.md §4).
   Only the MQTT 3.1.1 client (`CocoaMQTT`) is patched; `CocoaMQTT5` is not used.

To drop the vendor copy: get the flag upstreamed, point Package.swift back at the URL, delete
this directory.
