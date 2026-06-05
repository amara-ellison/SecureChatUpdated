An updated version of [SecureChat](https://modrinth.com/mod/securechat) for Minecraft 26.1.x.

SecureChatUpdated adds friend-to-friend encrypted chat that is relayed over MQTT instead of normal Minecraft server chat. It works across singleplayer worlds, LAN worlds, and multiplayer servers as long as both players are using the mod, are in a world, and are connected to the same configured MQTT network.

## What It Does

- Sends Secure Chat messages over MQTT topics instead of the Minecraft chat pipeline.
- Uses your Minecraft username as your Secure Chat display name.
- Creates a persistent local Secure Chat identity with a fingerprint.
- Shows online Secure Chat players, known fingerprints, and status in GUI screens.
- Lets you trust or untrust friends by name or fingerprint.
- Lets you choose recipients with `/scu to all` or `/scu to Name,OtherName`.
- Shows `Send to: ...` above the chat input while Secure Chat sending is enabled.
- Adds a `[SCU] ON/OFF` button to the chat screen.
- Adds `[Secure Chat] [Trusted]` or `[Secure Chat] [Untrusted]` labels to the tab player list for active Secure Chat players.

## Crypto And Transport

The current protocol is based on:

- MQTT relay topics: `securechatupdated/<network_id>/presence` and `securechatupdated/<network_id>/messages`
- XChaCha20-Poly1305 message encryption
- X25519 + ML-KEM-1024 per-recipient message-key wrapping
- Ed25519 + ML-DSA-87 packet signatures
- HKDF-SHA-512 key derivation
- Signed presence packets
- Message expiry, clock-skew checks, and replay protection

MQTT brokers relay packets but cannot read Secure Chat message contents. They can still see metadata such as connection times, topic names, packet sizes, and how much traffic is used.

## Setup

1. Install the mod in a Fabric Minecraft 26.1.x client.
2. Launch Minecraft once so the config files are created.
3. Close Minecraft.
4. Open the generated Fabric config folder and edit `securechatupdated.json`.
5. Fill in the MQTT settings:

```json
"mqtt": {
  "host": "YOUR_CLUSTER.s1.eu.hivemq.cloud",
  "port": 8883,
  "username": "player_a",
  "password": "CHANGE_ME",
  "use_tls": true
}
```

6. Make sure friends who should talk together use the same `network_id` and MQTT broker details.
7. Start Minecraft and load into a singleplayer or multiplayer world. Secure Chat only appears online after you are in a world.

## Config Files

The mod creates these files in the Fabric config folder:

- `securechatupdated.json`: MQTT settings, network ID, defaults, and feature settings.
- `securechat_keys.json`: your private Secure Chat identity keys.
- `securechat_state.json`: trusted fingerprints, known friends, seen message IDs, and saved recipient selection.

Do not share these files. `securechatupdated.json` can contain your MQTT password, and `securechat_keys.json` contains your private identity keys. If two clients copy the same `securechat_keys.json`, they will share the same fingerprint and may ignore each other as self-presence.

## Online Presence

Secure Chat connects to MQTT when you load into a world and disconnects when you leave the world. This means a friend sitting at the Minecraft main menu is not treated as an online Secure Chat recipient.

Presence is published every `presence_interval_seconds` seconds. By default this is `15`, and friends are considered active for `active_friend_timeout_seconds`, which defaults to `45`.

## Commands

Commands are available under `/SecureChatUpdated` and the short alias `/scu`.

```text
/scu help                         open the help GUI
/scu list_online_players          show online Secure Chat players
/scu fingerprints                 show known fingerprints
/scu trust <name or fingerprint>  trust a friend's current public identity
/scu untrust <name or fp>         remove trust
/scu to all                       send to all Secure Chat online players
/scu to Name,OtherName            send only to selected online players
/scu send hello                   send a Secure Chat message
/scu on                           turn encrypted sending on
/scu off                          turn encrypted sending off
/scu status                       show Secure Chat status
/scu reload_config                reload config files and reconnect MQTT
/scu connect                      connect or reconnect MQTT
/scu disconnect                   disconnect MQTT
/scu export_public_identity       copy your public identity bundle to clipboard
```

Top-level shortcuts are also available for connection management:

```text
/connect
/reload_config
/disconnect
```

`/scu help`, `/scu list_online_players`, `/scu fingerprints`, and `/scu status` open GUI screens. `/scu status` also logs the full detailed status to the Minecraft client console.

## Trust

Each player has a fingerprint derived from their public identity keys. Use `/scu fingerprints` to view known fingerprints and `/scu trust <name or fingerprint>` after verifying that a fingerprint belongs to your friend.

If `strict_trust` is enabled in the config, messages from untrusted senders are blocked. If it is disabled, untrusted messages can still appear but are marked as unverified/untrusted.

## Sending Messages

When Secure Chat is enabled, normal chat messages are intercepted and sent through Secure Chat instead of Minecraft server chat. Use `/scu off` or the `[SCU]` chat button to turn this off temporarily.

The current recipient selection appears above the chat input as `Send to: ...`. `/scu to all` means all currently online Secure Chat players on the same MQTT network, not every Minecraft player on the server.

You cannot select yourself as a recipient. Known friends who are not currently active in a world are not included when sending.

## Notes For Publishing

- Friend groups can share one MQTT broker/network, but everyone should keep their own Secure Chat identity keys.
- HiveMQ Cloud Free and similar brokers may have connection or traffic limits. Presence traffic increases as more players are online.
- For larger groups, consider increasing `presence_interval_seconds` and `active_friend_timeout_seconds`.
- This mod protects Secure Chat message contents from the Minecraft server and MQTT broker, but it does not hide that MQTT traffic is happening.
