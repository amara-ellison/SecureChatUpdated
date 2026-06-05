package dev.securechatupdated;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SecureChatUpdatedConfig {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("securechatupdated.json");
    private static final Path KEY_FILE = CONFIG_DIR.resolve("securechat_keys.json");
    private static final Path STATE_FILE = CONFIG_DIR.resolve("securechat_state.json");

    private static ConfigData config = ConfigData.defaults();
    private static KeyData keys = KeyData.defaults();
    private static StateData state = StateData.defaults();
    private static boolean configCreated;
    private static String username = "unknown";

    private SecureChatUpdatedConfig() {}

    public static void load() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException ignored) {
        }

        configCreated = !Files.exists(CONFIG_FILE);
        if (configCreated) {
            config = ConfigData.defaults();
            saveConfig();
        } else {
            try {
                ConfigData loaded = GSON.fromJson(Files.readString(CONFIG_FILE, StandardCharsets.UTF_8), ConfigData.class);
                config = loaded == null ? ConfigData.defaults() : loaded.withDefaults();
            } catch (Exception ignored) {
                config = ConfigData.defaults();
            }
        }

        loadKeys();
        if (keys.identity == null && config.identity != null) {
            keys.identity = config.identity;
            saveKeys();
            config.identity = null;
        }
        saveConfig();

        if (!Files.exists(STATE_FILE)) {
            state = StateData.defaults();
            saveState();
        } else {
            try {
                StateData loaded = GSON.fromJson(Files.readString(STATE_FILE, StandardCharsets.UTF_8), StateData.class);
                state = loaded == null ? StateData.defaults() : loaded.withDefaults();
            } catch (Exception ignored) {
                state = StateData.defaults();
            }
        }
    }

    public static boolean wasConfigCreated() {
        return configCreated;
    }

    public static ConfigData config() {
        return config;
    }

    public static StateData state() {
        return state;
    }

    public static KeyData keys() {
        return keys;
    }

    public static Path configFile() {
        return CONFIG_FILE;
    }

    public static Path keyFile() {
        return KEY_FILE;
    }

    public static Path stateFile() {
        return STATE_FILE;
    }

    public static String username() {
        return username;
    }

    public static void setUsername(String value) {
        if (value != null && !value.isBlank()) {
            username = value;
        }
    }

    public static boolean isEnabled() {
        return config.encryption_enabled;
    }

    public static void setEnabled(boolean value) {
        config.encryption_enabled = value;
        saveConfig();
    }

    public static boolean hasIdentity() {
        return keys.identity != null
                && keys.identity.has("ed25519_private")
                && keys.identity.has("x25519_private")
                && keys.identity.has("ml_kem_1024_public")
                && keys.identity.has("ml_kem_1024_private")
                && keys.identity.has("ml_dsa_87_public")
                && keys.identity.has("ml_dsa_87_private");
    }

    public static void setIdentity(JsonObject identity) {
        keys.identity = identity;
        saveKeys();
    }

    public static boolean hasUsableMqttConfig() {
        return config.mqtt != null
                && config.mqtt.host != null
                && !config.mqtt.host.isBlank()
                && !config.mqtt.host.startsWith("YOUR_CLUSTER")
                && config.mqtt.username != null
                && !config.mqtt.username.isBlank()
                && config.mqtt.password != null
                && !config.mqtt.password.isBlank()
                && !"CHANGE_ME".equals(config.mqtt.password);
    }

    private static void loadKeys() {
        if (!Files.exists(KEY_FILE)) {
            keys = KeyData.defaults();
            saveKeys();
            return;
        }

        try {
            KeyData loaded = GSON.fromJson(Files.readString(KEY_FILE, StandardCharsets.UTF_8), KeyData.class);
            keys = loaded == null ? KeyData.defaults() : loaded.withDefaults();
            saveKeys();
        } catch (Exception ignored) {
            keys = KeyData.defaults();
            saveKeys();
        }
    }

    public static void saveConfig() {
        try {
            Files.writeString(CONFIG_FILE, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static void saveKeys() {
        try {
            Files.writeString(KEY_FILE, GSON.toJson(keys), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static void saveState() {
        if (state.seen_message_ids.size() > 2000) {
            state.seen_message_ids = new ArrayList<>(state.seen_message_ids.subList(state.seen_message_ids.size() - 2000, state.seen_message_ids.size()));
        }
        try {
            Files.writeString(STATE_FILE, GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static final class ConfigData {
        public String _warning;
        public String network_id;
        public MqttData mqtt;
        public String topic_prefix;
        public String default_recipients;
        public boolean strict_trust;
        public int message_ttl_seconds;
        public int clock_skew_tolerance_seconds;
        public int presence_interval_seconds;
        public int active_friend_timeout_seconds;
        public boolean save_seen_messages;
        public boolean debug_rejections;
        public boolean encryption_enabled;
        @Deprecated
        public JsonObject identity;

        static ConfigData defaults() {
            ConfigData data = new ConfigData();
            data._warning = "Do not share any SecureChatUpdated config files with anyone. This file contains your MQTT password; securechat_keys.json contains your private identity keys.";
            data.network_id = "1";
            data.mqtt = MqttData.defaults();
            data.topic_prefix = "securechatupdated";
            data.default_recipients = "all";
            data.strict_trust = false;
            data.message_ttl_seconds = 60;
            data.clock_skew_tolerance_seconds = 60;
            data.presence_interval_seconds = 15;
            data.active_friend_timeout_seconds = 45;
            data.save_seen_messages = true;
            data.debug_rejections = false;
            data.encryption_enabled = true;
            data.identity = null;
            return data;
        }

        ConfigData withDefaults() {
            ConfigData defaults = defaults();
            if (_warning == null) _warning = defaults._warning;
            if (network_id == null) network_id = defaults.network_id;
            if (mqtt == null) mqtt = defaults.mqtt;
            mqtt = mqtt.withDefaults();
            if (topic_prefix == null) topic_prefix = defaults.topic_prefix;
            if (default_recipients == null) default_recipients = defaults.default_recipients;
            if (message_ttl_seconds <= 0) message_ttl_seconds = defaults.message_ttl_seconds;
            if (clock_skew_tolerance_seconds <= 0) clock_skew_tolerance_seconds = defaults.clock_skew_tolerance_seconds;
            if (presence_interval_seconds <= 0) presence_interval_seconds = defaults.presence_interval_seconds;
            if (active_friend_timeout_seconds <= 0) active_friend_timeout_seconds = defaults.active_friend_timeout_seconds;
            return this;
        }
    }

    public static final class KeyData {
        public String _warning;
        public JsonObject identity;

        static KeyData defaults() {
            KeyData data = new KeyData();
            data._warning = "Do not share this file with anyone. It contains your private SecureChat identity keys. If two clients use the same file, they will have the same fingerprint and ignore each other as self.";
            data.identity = null;
            return data;
        }

        KeyData withDefaults() {
            if (_warning == null) _warning = defaults()._warning;
            return this;
        }
    }

    public static final class MqttData {
        public String host;
        public int port;
        public String username;
        public String password;
        public boolean use_tls;

        static MqttData defaults() {
            MqttData data = new MqttData();
            data.host = "YOUR_CLUSTER.s1.eu.hivemq.cloud";
            data.port = 8883;
            data.username = "player_a";
            data.password = "CHANGE_ME";
            data.use_tls = true;
            return data;
        }

        MqttData withDefaults() {
            MqttData defaults = defaults();
            if (host == null) host = defaults.host;
            if (port <= 0) port = defaults.port;
            if (username == null) username = defaults.username;
            if (password == null) password = defaults.password;
            return this;
        }
    }

    public static final class StateData {
        public List<String> trusted_fingerprints;
        public Map<String, FriendRecord> known_friends;
        public List<String> seen_message_ids;
        public String default_recipients;

        static StateData defaults() {
            StateData data = new StateData();
            data.trusted_fingerprints = new ArrayList<>();
            data.known_friends = new LinkedHashMap<>();
            data.seen_message_ids = new ArrayList<>();
            data.default_recipients = null;
            return data;
        }

        StateData withDefaults() {
            if (trusted_fingerprints == null) trusted_fingerprints = new ArrayList<>();
            if (known_friends == null) known_friends = new LinkedHashMap<>();
            if (seen_message_ids == null) seen_message_ids = new ArrayList<>();
            return this;
        }
    }

    public static final class FriendRecord {
        public String display_name;
        public String fingerprint;
        public JsonObject bundle;

        public FriendRecord() {}

        public FriendRecord(String displayName, String fingerprint, JsonObject bundle) {
            this.display_name = displayName;
            this.fingerprint = fingerprint;
            this.bundle = bundle;
        }
    }
}
