package dev.securechatupdated;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SecureChatUpdatedProtocol {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final SecureRandom RNG = new SecureRandom();

    private final Map<String, Friend> friends = new LinkedHashMap<>();
    private SecureChatUpdatedCryptoUtil.Identity identity;
    private MqttClient mqttClient;
    private ScheduledExecutorService presenceExecutor;
    private String topicBase;
    private String presenceTopic;
    private String messageTopic;
    private String startupProblem = "";
    private int ignoredSelfPresencePackets;
    private int mqttPacketsReceived;
    private int presencePacketsReceived;
    private int presencePacketsAccepted;
    private int presencePacketsRejected;
    private int securePacketsReceived;
    private int presencePacketsPublished;
    private String lastPresenceRejectReason = "none";
    private String lastPresenceSender = "none";
    private long lastMqttPacketAtMs;
    private long lastPresencePacketAtMs;

    public void start() {
        SecureChatUpdatedConfig.load();
        loadKnownFriends();

        try {
            identity = loadOrCreateIdentity();
            identity.setDisplayName(SecureChatUpdatedConfig.username());
        } catch (Exception e) {
            startupProblem = "Could not load/create identity keys in " + SecureChatUpdatedConfig.configFile() + ": " + e.getMessage();
            return;
        }

        SecureChatUpdatedConfig.ConfigData config = SecureChatUpdatedConfig.config();
        String prefix = stripSlashes(config.topic_prefix);
        String networkId = stripSlashes(config.network_id);
        topicBase = prefix + "/" + networkId;
        presenceTopic = topicBase + "/presence";
        messageTopic = topicBase + "/messages";

        if (SecureChatUpdatedConfig.wasConfigCreated()) {
            startupProblem = "Created " + SecureChatUpdatedConfig.configFile() + " and " + SecureChatUpdatedConfig.keyFile() + ". Add MQTT details. Do not share config files.";
            return;
        }

        if (!SecureChatUpdatedConfig.hasUsableMqttConfig()) {
            startupProblem = "Identity loaded, but MQTT config still has placeholders in " + SecureChatUpdatedConfig.configFile() + ".";
            return;
        }

        connectMqtt();
    }

    public void reload() {
        disconnect();
        startupProblem = "";
        start();
    }

    public void disconnect() {
        publishPresence("offline");
        if (presenceExecutor != null) {
            presenceExecutor.shutdownNow();
            presenceExecutor = null;
        }
        if (mqttClient != null) {
            try {
                mqttClient.disconnect();
            } catch (Exception ignored) {
            }
            try {
                mqttClient.close();
            } catch (Exception ignored) {
            }
            mqttClient = null;
        }
        SecureChatUpdatedConfig.saveState();
    }

    public boolean isReady() {
        return identity != null && mqttClient != null && mqttClient.isConnected();
    }

    public String startupProblem() {
        return startupProblem;
    }

    public void publishPresence() {
        publishPresence("online");
    }

    private void publishPresence(String status) {
        if (identity == null || mqttClient == null || !mqttClient.isConnected()) {
            return;
        }
        try {
            JsonObject packet = new JsonObject();
            packet.addProperty("protocol", SecureChatUpdatedCryptoUtil.PROTOCOL_VERSION);
            packet.addProperty("type", "presence");
            packet.addProperty("status", status);
            packet.addProperty("timestamp_ms", SecureChatUpdatedCryptoUtil.nowMs());
            packet.add("sender_bundle", identity.publicBundle());
            identity.signPacket(packet);
            publish(presenceTopic, packet);
            presencePacketsPublished++;
        } catch (Exception e) {
            debug("presence publish failed: " + e.getMessage());
        }
    }

    public boolean sendSecureMessage(String text) {
        if (!SecureChatUpdatedConfig.isEnabled()) {
            SecureChatUpdatedClient.notice("Encryption is currently off. Use /SecureChatUpdated on to turn it back on.");
            return false;
        }
        if (!isReady()) {
            SecureChatUpdatedClient.notice(startupProblem.isBlank() ? "SecureChat MQTT is not connected yet." : startupProblem);
            return false;
        }

        List<Friend> recipients = resolveRecipients();
        if (recipients.isEmpty()) {
            SecureChatUpdatedClient.notice("No recipients found. Use /SecureChatUpdated list_online_players and wait for presence packets.");
            return false;
        }

        try {
            String messageId = randomHex(16);
            long timestamp = SecureChatUpdatedCryptoUtil.nowMs();
            byte[] messageKey = new byte[SecureChatUpdatedCryptoUtil.KEY_SIZE];
            RNG.nextBytes(messageKey);

            List<String> recipientFps = new ArrayList<>();
            for (Friend recipient : recipients) {
                recipientFps.add(recipient.fingerprint);
            }

            JsonObject header = new JsonObject();
            header.addProperty("protocol", SecureChatUpdatedCryptoUtil.PROTOCOL_VERSION);
            header.addProperty("type", "secure_message");
            header.addProperty("message_id", messageId);
            header.addProperty("timestamp_ms", timestamp);
            header.add("sender_bundle", identity.publicBundle());
            header.add("recipient_fingerprints", SecureChatUpdatedCryptoUtil.sortedStringArray(recipientFps));
            header.addProperty("cipher", "XChaCha20-Poly1305");
            header.addProperty("kdf", "HKDF-SHA-512");
            header.addProperty("kem", "X25519+ML-KEM-1024");
            header.addProperty("signatures", "Ed25519+ML-DSA-87");

            JsonObject plaintext = new JsonObject();
            plaintext.addProperty("from", SecureChatUpdatedConfig.username());
            plaintext.addProperty("text", text);
            plaintext.addProperty("sent_at_ms", timestamp);

            SecureChatUpdatedCryptoUtil.XChaChaBox encrypted = SecureChatUpdatedCryptoUtil.xchachaEncrypt(
                    messageKey,
                    SecureChatUpdatedCryptoUtil.canonicalBytes(plaintext),
                    SecureChatUpdatedCryptoUtil.canonicalBytes(SecureChatUpdatedCryptoUtil.messageAad(header))
            );

            JsonObject packet = header.deepCopy();
            packet.addProperty("nonce", encrypted.nonce());
            packet.addProperty("ciphertext", encrypted.ciphertext());

            JsonObject wrappedKeys = new JsonObject();
            for (Friend recipient : recipients) {
                JsonObject recipientJson = new JsonObject();
                recipientJson.add("bundle", recipient.bundle);
                wrappedKeys.add(recipient.fingerprint, identity.wrapMessageKey(messageKey, recipientJson, messageId));
            }
            packet.add("wrapped_keys", wrappedKeys);
            identity.signPacket(packet);
            publish(messageTopic, packet);

            String names = namesForFriends(recipients);
            SecureChatUpdatedClient.showSecureMessage("You", names, text, true, "Recipients: " + names);
            return true;
        } catch (Exception e) {
            SecureChatUpdatedClient.notice("Encrypt/publish failed: " + e.getMessage());
            return false;
        }
    }

    public List<String> helpLines() {
        return List.of(
                "Commands:",
                "/SecureChatUpdated help                         show help",
                "/SecureChatUpdated list_online_players          list online SecureChat players",
                "/SecureChatUpdated fingerprints                 show known fingerprints",
                "/SecureChatUpdated trust <name or fingerprint>  trust a friend's current public identity",
                "/SecureChatUpdated untrust <name or fp>         remove trust",
                "/SecureChatUpdated to all                       send to everyone currently online",
                "/SecureChatUpdated to B,C                       send only to B and C",
                "/SecureChatUpdated send hello                   send secure message",
                "/SecureChatUpdated on                           turn encrypted sending on",
                "/SecureChatUpdated off                          turn encrypted sending off",
                "/SecureChatUpdated status                       show config/status",
                "/SecureChatUpdated reload_config                reload config files and reconnect MQTT",
                "/SecureChatUpdated connect                      connect/reconnect MQTT",
                "/SecureChatUpdated disconnect                   disconnect MQTT",
                "/SecureChatUpdated export_public_identity       copy public identity bundle"
        );
    }

    public List<String> listFriendsLines() {
        Map<String, Friend> active = activeFriends();
        if (active.isEmpty()) {
            List<String> lines = new ArrayList<>();
            lines.add("No active friends yet. Check MQTT details and wait for presence packets.");
            return lines;
        }
        Set<String> trusted = new HashSet<>(SecureChatUpdatedConfig.state().trusted_fingerprints);
        List<Friend> sorted = new ArrayList<>(active.values());
        sorted.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        List<String> lines = new ArrayList<>();
        long now = SecureChatUpdatedCryptoUtil.nowMs();
        for (Friend friend : sorted) {
            String trust = trusted.contains(friend.fingerprint) ? "trusted" : "untrusted";
            long age = Math.max(0, (now - friend.lastSeenMs) / 1000);
            lines.add("- " + friend.displayName + " | " + friend.fingerprint + " | " + trust + " | seen " + age + "s ago");
        }
        return lines;
    }

    public List<String> fingerprintLines() {
        List<String> lines = new ArrayList<>();
        try {
            lines.add("You: " + SecureChatUpdatedConfig.username() + " | " + (identity == null ? "identity not loaded" : identity.fingerprint()));
        } catch (Exception e) {
            lines.add("You: identity not loaded");
        }
        if (friends.isEmpty()) {
            lines.add("No known friends yet.");
            return lines;
        }
        Set<String> trusted = new HashSet<>(SecureChatUpdatedConfig.state().trusted_fingerprints);
        List<Friend> sorted = new ArrayList<>(friends.values());
        sorted.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        for (Friend friend : sorted) {
            String trust = trusted.contains(friend.fingerprint) ? "trusted" : "untrusted";
            lines.add("- " + friend.displayName + " | " + friend.fingerprint + " | " + trust);
        }
        return lines;
    }

    public String trustFriend(String token) {
        Friend friend = findFriend(token);
        if (friend == null) {
            return "Friend not found. Use /SecureChatUpdated fingerprints or list_online_players first.";
        }
        if (!SecureChatUpdatedConfig.state().trusted_fingerprints.contains(friend.fingerprint)) {
            SecureChatUpdatedConfig.state().trusted_fingerprints.add(friend.fingerprint);
            SecureChatUpdatedConfig.saveState();
        }
        return "Trusted " + friend.displayName + " | " + friend.fingerprint;
    }

    public String untrustFriend(String token) {
        Friend friend = findFriend(token);
        String fp = friend == null ? token.toUpperCase(Locale.ROOT).trim() : friend.fingerprint;
        SecureChatUpdatedConfig.state().trusted_fingerprints.removeIf(existing -> existing.equals(fp));
        SecureChatUpdatedConfig.saveState();
        return "Removed trust for " + fp;
    }

    public String setRecipients(String setting) {
        String trimmed = setting == null ? "" : setting.trim();
        if (trimmed.isEmpty()) {
            return "Current recipients: " + recipientsSetting();
        }

        if ("all".equalsIgnoreCase(trimmed)) {
            SecureChatUpdatedConfig.state().default_recipients = "all";
            SecureChatUpdatedConfig.saveState();
            return "Recipients set to: all Secure Chat online players";
        }

        List<String> recipients = new ArrayList<>();
        boolean ignoredSelf = false;
        for (String token : trimmed.split(",")) {
            String recipient = token.trim();
            if (recipient.isEmpty()) {
                continue;
            }
            if (isSelfRecipient(recipient)) {
                ignoredSelf = true;
                continue;
            }
            recipients.add(recipient);
        }

        if (recipients.isEmpty()) {
            return ignoredSelf
                    ? "You cannot set yourself as a SecureChat recipient."
                    : "Current recipients: " + recipientsSetting();
        }

        String cleaned = String.join(",", recipients);
        SecureChatUpdatedConfig.state().default_recipients = cleaned;
        SecureChatUpdatedConfig.saveState();
        return "Recipients set to: " + cleaned + (ignoredSelf ? " (ignored yourself)" : "");
    }

    public String recipientDisplayLabel() {
        String recipients = recipientsSetting();
        return "all".equalsIgnoreCase(recipients.trim()) ? "all Secure Chat online players" : recipients;
    }

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        SecureChatUpdatedConfig.ConfigData config = SecureChatUpdatedConfig.config();
        lines.add("Name: " + SecureChatUpdatedConfig.username());
        try {
            lines.add("Fingerprint: " + (identity == null ? "identity not loaded" : identity.fingerprint()));
        } catch (Exception e) {
            lines.add("Fingerprint: identity not loaded");
        }
        lines.add("Network ID: " + config.network_id);
        lines.add("Topic base: " + (topicBase == null ? stripSlashes(config.topic_prefix) + "/" + stripSlashes(config.network_id) : topicBase));
        lines.add("Recipients: " + recipientsSetting());
        lines.add("Strict trust: " + config.strict_trust);
        lines.add("Encryption enabled: " + SecureChatUpdatedConfig.isEnabled());
        lines.add("MQTT connected: " + isReady());
        if (!startupProblem.isBlank()) {
            lines.add("Setup: " + startupProblem);
        }
        lines.add("MQTT packets received: " + mqttPacketsReceived + " | last: " + ageLabel(lastMqttPacketAtMs));
        lines.add("Presence published: " + presencePacketsPublished);
        lines.add("Presence received: " + presencePacketsReceived + " | accepted: " + presencePacketsAccepted + " | rejected: " + presencePacketsRejected);
        lines.add("Last presence sender: " + lastPresenceSender + " | last presence: " + ageLabel(lastPresencePacketAtMs));
        lines.add("Last presence reject: " + lastPresenceRejectReason);
        lines.add("Ignored self presence packets: " + ignoredSelfPresencePackets);
        lines.add("Secure packets received: " + securePacketsReceived);
        lines.add("Known friends: " + friends.size());
        lines.add("Active friends: " + activeFriends().size());
        return lines;
    }

    public List<String> chatStatusLines() {
        List<String> lines = new ArrayList<>();
        try {
            lines.add("Fingerprint: " + (identity == null ? "identity not loaded" : identity.fingerprint()));
        } catch (Exception e) {
            lines.add("Fingerprint: identity not loaded");
        }
        lines.add("Recipients: " + recipientsSetting());
        lines.add("Strict trust: " + SecureChatUpdatedConfig.config().strict_trust);
        lines.add("Encryption enabled: " + SecureChatUpdatedConfig.isEnabled());
        lines.add("MQTT connected: " + isReady());
        return lines;
    }

    public List<String> exportBundleLines() {
        if (identity == null) {
            return List.of("Identity not loaded.");
        }
        try {
            return List.of(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(identity.publicBundle()));
        } catch (Exception e) {
            return List.of("Could not export bundle: " + e.getMessage());
        }
    }

    public void toggle() {
        SecureChatUpdatedConfig.setEnabled(!SecureChatUpdatedConfig.isEnabled());
    }

    public List<String> recipientSuggestions() {
        List<String> out = new ArrayList<>();
        out.add("all");
        for (Friend friend : activeFriends().values()) {
            out.add(friend.displayName);
        }
        return out;
    }

    public List<String> friendSuggestions() {
        List<String> out = new ArrayList<>();
        for (Friend friend : friends.values()) {
            out.add(friend.displayName);
            out.add(friend.fingerprint);
        }
        return out;
    }

    public String activeTrustLabelForPlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        Friend friend = findFriend(playerName);
        if (friend == null) {
            return null;
        }

        Friend active = activeFriends().get(friend.fingerprint);
        if (active == null) {
            return null;
        }

        return SecureChatUpdatedConfig.state().trusted_fingerprints.contains(friend.fingerprint)
                ? "Trusted"
                : "Untrusted";
    }

    private SecureChatUpdatedCryptoUtil.Identity loadOrCreateIdentity() throws Exception {
        if (!SecureChatUpdatedConfig.hasIdentity()) {
            SecureChatUpdatedCryptoUtil.Identity created = SecureChatUpdatedCryptoUtil.createIdentity(SecureChatUpdatedConfig.username());
            SecureChatUpdatedConfig.setIdentity(created.privatePlaintext());
            return created;
        }

        return SecureChatUpdatedCryptoUtil.identityFromPlaintext(
                SecureChatUpdatedConfig.username(),
                SecureChatUpdatedConfig.keys().identity
        );
    }

    private void connectMqtt() {
        SecureChatUpdatedConfig.MqttData mqtt = SecureChatUpdatedConfig.config().mqtt;
        String scheme = mqtt.use_tls ? "ssl" : "tcp";
        String serverUri = scheme + "://" + mqtt.host + ":" + mqtt.port;
        String clientId = "securechatupdated-" + SecureChatUpdatedConfig.username() + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            mqttClient = new MqttClient(serverUri, clientId, new MemoryPersistence());
            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    SecureChatUpdatedClient.notice("MQTT disconnected" + (cause == null ? "" : ": " + cause.getMessage()));
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handlePacket(message);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(45);
            options.setUserName(mqtt.username);
            options.setPassword(mqtt.password.toCharArray());
            mqttClient.connect(options);
            mqttClient.subscribe(presenceTopic, 1);
            mqttClient.subscribe(messageTopic, 1);
            publishPresence();

            presenceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "SecureChatUpdated-Presence");
                thread.setDaemon(true);
                return thread;
            });
            presenceExecutor.scheduleAtFixedRate(this::publishPresence,
                    SecureChatUpdatedConfig.config().presence_interval_seconds,
                    SecureChatUpdatedConfig.config().presence_interval_seconds,
                    TimeUnit.SECONDS);
            startupProblem = "";
        } catch (Exception e) {
            startupProblem = "MQTT connection failed: " + e.getMessage();
            SecureChatUpdatedClient.notice(startupProblem);
        }
    }

    private void handlePacket(MqttMessage message) {
        try {
            mqttPacketsReceived++;
            lastMqttPacketAtMs = SecureChatUpdatedCryptoUtil.nowMs();
            JsonObject packet = SecureChatUpdatedCryptoUtil.parseObject(new String(message.getPayload(), StandardCharsets.UTF_8));
            String type = SecureChatUpdatedCryptoUtil.string(packet, "type");
            if ("presence".equals(type)) {
                presencePacketsReceived++;
                lastPresencePacketAtMs = SecureChatUpdatedCryptoUtil.nowMs();
                handlePresence(packet);
            } else if ("secure_message".equals(type)) {
                securePacketsReceived++;
                handleSecureMessage(packet);
            }
        } catch (Exception ignored) {
        }
    }

    private void handlePresence(JsonObject packet) {
        SecureChatUpdatedCryptoUtil.Verification verification = SecureChatUpdatedCryptoUtil.verifySignedPacket(packet);
        if (!verification.ok()) {
            presencePacketsRejected++;
            lastPresenceRejectReason = verification.reason();
            debug("presence rejected: " + verification.reason());
            return;
        }

        JsonObject bundle = verification.bundle();
        lastPresenceSender = SecureChatUpdatedCryptoUtil.string(bundle, "display_name") + " | " + SecureChatUpdatedCryptoUtil.string(bundle, "fingerprint");
        String fp = SecureChatUpdatedCryptoUtil.string(bundle, "fingerprint");
        try {
            if (identity != null && fp.equals(identity.fingerprint())) {
                ignoredSelfPresencePackets++;
                debug("ignored presence with your own fingerprint. If this is another test client, it is sharing your securechat_keys.json.");
                return;
            }
        } catch (Exception ignored) {
        }

        String status = SecureChatUpdatedCryptoUtil.string(packet, "status");
        boolean online = !"offline".equalsIgnoreCase(status);
        Friend friend = new Friend(
                SecureChatUpdatedCryptoUtil.string(bundle, "display_name"),
                fp,
                bundle.deepCopy(),
                SecureChatUpdatedCryptoUtil.nowMs(),
                online
        );
        friends.put(fp, friend);
        presencePacketsAccepted++;
        SecureChatUpdatedConfig.state().known_friends.put(fp,
                new SecureChatUpdatedConfig.FriendRecord(friend.displayName, fp, friend.bundle));
        SecureChatUpdatedConfig.saveState();
    }

    private void handleSecureMessage(JsonObject packet) {
        try {
            JsonObject senderBundle = packet.getAsJsonObject("sender_bundle");
            if (senderBundle != null && identity != null && SecureChatUpdatedCryptoUtil.string(senderBundle, "fingerprint").equals(identity.fingerprint())) {
                return;
            }

            SecureChatUpdatedCryptoUtil.Verification verification = SecureChatUpdatedCryptoUtil.verifySignedPacket(packet);
            if (!verification.ok()) {
                SecureChatUpdatedClient.notice("[rejected secure packet] " + verification.reason());
                return;
            }

            JsonObject bundle = verification.bundle();
            String senderFp = SecureChatUpdatedCryptoUtil.string(bundle, "fingerprint");
            Friend sender = friends.computeIfAbsent(senderFp, ignored -> new Friend(
                    SecureChatUpdatedCryptoUtil.string(bundle, "display_name"),
                    senderFp,
                    bundle.deepCopy(),
                    SecureChatUpdatedCryptoUtil.nowMs(),
                    true
            ));
            sender.displayName = SecureChatUpdatedCryptoUtil.string(bundle, "display_name");
            sender.bundle = bundle.deepCopy();
            sender.lastSeenMs = SecureChatUpdatedCryptoUtil.nowMs();
            sender.online = true;
            SecureChatUpdatedConfig.state().known_friends.put(senderFp,
                    new SecureChatUpdatedConfig.FriendRecord(sender.displayName, senderFp, sender.bundle));

            boolean trusted = SecureChatUpdatedConfig.state().trusted_fingerprints.contains(senderFp);
            if (SecureChatUpdatedConfig.config().strict_trust && !trusted) {
                SecureChatUpdatedClient.notice("[blocked untrusted sender] " + sender.displayName + " " + senderFp);
                return;
            }

            long current = SecureChatUpdatedCryptoUtil.nowMs();
            long sent = SecureChatUpdatedCryptoUtil.longValue(packet, "timestamp_ms");
            long ttlMs = SecureChatUpdatedConfig.config().message_ttl_seconds * 1000L;
            long skewMs = SecureChatUpdatedConfig.config().clock_skew_tolerance_seconds * 1000L;
            if (sent > current + skewMs) {
                SecureChatUpdatedClient.notice("[rejected secure packet] timestamp too far in the future");
                return;
            }
            if (current - sent > ttlMs + skewMs) {
                SecureChatUpdatedClient.notice("[rejected secure packet] expired message");
                return;
            }

            String messageId = SecureChatUpdatedCryptoUtil.string(packet, "message_id");
            if (SecureChatUpdatedConfig.state().seen_message_ids.contains(messageId)) {
                SecureChatUpdatedClient.notice("[rejected secure packet] replayed message");
                return;
            }
            SecureChatUpdatedConfig.state().seen_message_ids.add(messageId);

            String ownFp = identity.fingerprint();
            if (!containsString(packet.getAsJsonArray("recipient_fingerprints"), ownFp)) {
                SecureChatUpdatedConfig.saveState();
                return;
            }

            byte[] messageKey = identity.unwrapMessageKey(packet);
            if (messageKey == null) {
                SecureChatUpdatedClient.notice("[wrong key / cannot decrypt] message was addressed to you but key unwrap failed");
                SecureChatUpdatedConfig.saveState();
                return;
            }

            JsonObject body;
            try {
                byte[] plaintext = SecureChatUpdatedCryptoUtil.xchachaDecrypt(
                        messageKey,
                        SecureChatUpdatedCryptoUtil.string(packet, "nonce"),
                        SecureChatUpdatedCryptoUtil.string(packet, "ciphertext"),
                        SecureChatUpdatedCryptoUtil.canonicalBytes(SecureChatUpdatedCryptoUtil.messageAad(packet))
                );
                body = SecureChatUpdatedCryptoUtil.parseObject(new String(plaintext, StandardCharsets.UTF_8));
            } catch (Exception e) {
                SecureChatUpdatedClient.notice("[wrong key / tampered] message decrypt failed");
                SecureChatUpdatedConfig.saveState();
                return;
            }

            String recipients = namesForFingerprints(packet.getAsJsonArray("recipient_fingerprints"));
            String trustLabel = trusted ? "" : " [UNVERIFIED]";
            SecureChatUpdatedClient.showSecureMessage(sender.displayName + trustLabel, recipients,
                    SecureChatUpdatedCryptoUtil.string(body, "text"), trusted, "Recipients: " + recipients);
            SecureChatUpdatedConfig.saveState();
        } catch (Exception e) {
            debug("secure message handling failed: " + e.getMessage());
        }
    }

    private void publish(String topic, JsonObject packet) throws Exception {
        MqttMessage message = new MqttMessage(GSON.toJson(packet).getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        message.setRetained(false);
        mqttClient.publish(topic, message);
    }

    private void loadKnownFriends() {
        friends.clear();
        for (Map.Entry<String, SecureChatUpdatedConfig.FriendRecord> entry : SecureChatUpdatedConfig.state().known_friends.entrySet()) {
            SecureChatUpdatedConfig.FriendRecord record = entry.getValue();
            if (record == null || record.bundle == null) {
                continue;
            }
            friends.put(entry.getKey(), new Friend(record.display_name, entry.getKey(), record.bundle, 0L, false));
        }
    }

    private Map<String, Friend> activeFriends() {
        long timeoutMs = SecureChatUpdatedConfig.config().active_friend_timeout_seconds * 1000L;
        long now = SecureChatUpdatedCryptoUtil.nowMs();
        Map<String, Friend> active = new HashMap<>();
        for (Map.Entry<String, Friend> entry : friends.entrySet()) {
            Friend friend = entry.getValue();
            if (friend.online && friend.lastSeenMs > 0 && now - friend.lastSeenMs <= timeoutMs) {
                active.put(entry.getKey(), friend);
            }
        }
        return active;
    }

    private String recipientsSetting() {
        String stateSetting = SecureChatUpdatedConfig.state().default_recipients;
        if (stateSetting != null && !stateSetting.isBlank()) {
            return stateSetting;
        }
        return SecureChatUpdatedConfig.config().default_recipients == null ? "all" : SecureChatUpdatedConfig.config().default_recipients;
    }

    private List<Friend> resolveRecipients() {
        String setting = recipientsSetting().trim();
        if (setting.isEmpty() || "all".equalsIgnoreCase(setting)) {
            return new ArrayList<>(activeFriends().values());
        }

        List<Friend> results = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Map<String, Friend> active = activeFriends();
        for (String token : setting.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Friend friend = findFriend(trimmed);
            if (friend == null) {
                missing.add(trimmed);
            } else if (!active.containsKey(friend.fingerprint)) {
                missing.add(trimmed);
            } else {
                results.add(active.get(friend.fingerprint));
            }
        }
        if (!missing.isEmpty()) {
            SecureChatUpdatedClient.notice("Could not find recipients: " + String.join(", ", missing));
        }

        Map<String, Friend> deduped = new LinkedHashMap<>();
        for (Friend friend : results) {
            deduped.put(friend.fingerprint, friend);
        }
        return new ArrayList<>(deduped.values());
    }

    private Friend findFriend(String token) {
        String lower = token.toLowerCase(Locale.ROOT).trim();
        String upper = token.toUpperCase(Locale.ROOT).trim();
        for (Friend friend : friends.values()) {
            if (friend.displayName != null && friend.displayName.toLowerCase(Locale.ROOT).equals(lower)) {
                return friend;
            }
            if (friend.fingerprint.startsWith(upper)) {
                return friend;
            }
        }
        return null;
    }

    private boolean isSelfRecipient(String token) {
        String lower = token.toLowerCase(Locale.ROOT).trim();
        if (SecureChatUpdatedConfig.username().toLowerCase(Locale.ROOT).equals(lower)) {
            return true;
        }
        try {
            return identity != null && identity.fingerprint().startsWith(token.toUpperCase(Locale.ROOT).trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String namesForFingerprints(JsonArray fps) throws Exception {
        List<String> names = new ArrayList<>();
        String ownFp = identity == null ? "" : identity.fingerprint();
        if (fps != null) {
            for (JsonElement element : fps) {
                String fp = element.getAsString();
                if (fp.equals(ownFp)) {
                    names.add(SecureChatUpdatedConfig.username());
                } else if (friends.containsKey(fp)) {
                    names.add(friends.get(fp).displayName);
                } else {
                    names.add(fp.substring(0, Math.min(10, fp.length())));
                }
            }
        }
        return String.join(", ", names);
    }

    private String namesForFriends(List<Friend> friends) {
        List<String> names = new ArrayList<>();
        for (Friend friend : friends) {
            names.add(friend.displayName);
        }
        return String.join(", ", names);
    }

    private static boolean containsString(JsonArray array, String value) {
        if (array == null) {
            return false;
        }
        for (JsonElement element : array) {
            if (value.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String stripSlashes(String input) {
        String out = input == null ? "" : input.trim();
        while (out.startsWith("/")) out = out.substring(1);
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RNG.nextBytes(data);
        return java.util.HexFormat.of().formatHex(data);
    }

    private void debug(String message) {
        if (SecureChatUpdatedConfig.config().debug_rejections) {
            SecureChatUpdatedClient.notice(message);
        }
    }

    private static String ageLabel(long timestampMs) {
        if (timestampMs <= 0) {
            return "never";
        }
        long ageSeconds = Math.max(0, (SecureChatUpdatedCryptoUtil.nowMs() - timestampMs) / 1000);
        return ageSeconds + "s ago";
    }

    private static final class Friend {
        private String displayName;
        private final String fingerprint;
        private JsonObject bundle;
        private long lastSeenMs;
        private boolean online;

        private Friend(String displayName, String fingerprint, JsonObject bundle, long lastSeenMs, boolean online) {
            this.displayName = displayName == null || displayName.isBlank() ? "unknown" : displayName;
            this.fingerprint = fingerprint;
            this.bundle = bundle;
            this.lastSeenMs = lastSeenMs;
            this.online = online;
        }
    }
}
