An updated version of [SecureChat](https://modrinth.com/mod/securechat) for latest versions 26.1.x.

## SecureChatUpdated MQTT Mode

The mod now uses the following protocols:

- MQTT relay topics: `securechatupdated/<network_id>/presence` and `securechatupdated/<network_id>/messages`
- XChaCha20-Poly1305 message encryption
- X25519 + ML-KEM-1024 per-recipient message-key wrapping
- Ed25519 + ML-DSA-87 packet signatures
- Plain JSON local identity key storage in `securechat_keys.json`

On first launch, edit the generated Fabric config files:

- `securechatupdated.json`
- `securechat_keys.json`
- `securechat_state.json`

Do not share any SecureChatUpdated config files with anyone. `securechatupdated.json` contains your MQTT password, and `securechat_keys.json` contains your private SecureChat identity keys. The mod uses your Minecraft username as the display name.

Minecraft commands are under `/SecureChatUpdated`, with `/scu` as the short alias.

Examples:

```text
/SecureChatUpdated help
/SecureChatUpdated list_online_players
/SecureChatUpdated trust Steve
/SecureChatUpdated to Alex,Steve
/SecureChatUpdated send hello
/SecureChatUpdated on
/SecureChatUpdated off
/SecureChatUpdated reload_config
/connect
```
