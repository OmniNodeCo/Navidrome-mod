package dev.omninode.navidrome.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Stores harmless preferences only. Passwords and API keys never touch disk. */
public final class ConnectionStore {
    public record Settings(String url, String username, int bitrate, float volume) {}
    private final Path file;

    public ConnectionStore(Path configDir) { file = configDir.resolve("navidrome-client.properties"); }

    public Settings read() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        catch (IOException ignored) { return new Settings("", "", 192, 0.65f); }
        int bitrate;
        float volume;
        try { bitrate = Integer.parseInt(p.getProperty("bitrate", "192")); }
        catch (NumberFormatException e) { bitrate = 192; }
        try { volume = Float.parseFloat(p.getProperty("volume", "0.65")); }
        catch (NumberFormatException e) { volume = 0.65f; }
        if (bitrate != 96 && bitrate != 128 && bitrate != 192 && bitrate != 256 && bitrate != 320) bitrate = 192;
        if (!Float.isFinite(volume)) volume = 0.65f;
        return new Settings(p.getProperty("server", ""), p.getProperty("username", ""),
                bitrate, Math.clamp(volume, 0f, 1f));
    }

    public void write(Settings s) throws IOException {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        p.setProperty("server", s.url());
        p.setProperty("username", s.username());
        p.setProperty("bitrate", Integer.toString(s.bitrate()));
        p.setProperty("volume", Float.toString(s.volume()));
        Path temp = Files.createTempFile(file.getParent(), "navidrome-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) { p.store(out, "No password or API key is stored here"); }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }
}
