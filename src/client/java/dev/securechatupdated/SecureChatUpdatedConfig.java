package dev.securechatupdated;

import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class SecureChatUpdatedConfig {

    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("securechatupdated.properties");

    private static char[] passphrase = "changeme".toCharArray();
    private static boolean enabled   = true;

    private SecureChatUpdatedConfig() {}

    public static char[] getPassphrase()          { return passphrase; }
    public static boolean isEnabled()             { return enabled; }
    public static void setEnabled(boolean value)  { enabled = value; save(); }

    public static void setPassphrase(String raw) {
        passphrase = raw.toCharArray();
        save();
    }

    public static void load() {
        if (!Files.exists(CONFIG_FILE)) { save(); return; }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
            props.load(in);
            String p = props.getProperty("passphrase");
            if (p != null && !p.isBlank()) passphrase = p.toCharArray();
            String e = props.getProperty("enabled");
            if (e != null) enabled = Boolean.parseBoolean(e);
        } catch (IOException ignored) {}
    }

    private static void save() {
        Properties props = new Properties();
        props.setProperty("passphrase", new String(passphrase));
        props.setProperty("enabled",    String.valueOf(enabled));
        try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
            props.store(out, "Secure Chat Updated config, keep this file private!");
        } catch (IOException ignored) {}
    }
}
