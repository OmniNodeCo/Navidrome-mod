package dev.omninode.navidrome.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionStoreTest {
    @TempDir Path config;

    @Test void storesPreferencesWithoutCredentials() throws Exception {
        ConnectionStore store = new ConnectionStore(config);
        assertEquals(192, store.read().bitrate());
        store.write(new ConnectionStore.Settings("https://music.example.com", "listener", 256, .4f));
        assertEquals(256, store.read().bitrate());
        assertEquals(.4f, store.read().volume());
        String saved = Files.readString(config.resolve("navidrome-client.properties"));
        assertTrue(saved.contains("music.example.com"));
        var properties = new java.util.Properties();
        try (var in = Files.newInputStream(config.resolve("navidrome-client.properties"))) {
            properties.load(in);
        }
        assertEquals(java.util.Set.of("server", "username", "bitrate", "volume"),
                properties.stringPropertyNames());
    }

    @Test void corruptSettingsAreClampedInsteadOfBreakingStartup() throws Exception {
        Files.writeString(config.resolve("navidrome-client.properties"),
                "bitrate=-5\nvolume=NaN\nusername=listener\n");
        ConnectionStore.Settings settings = new ConnectionStore(config).read();
        assertEquals(192, settings.bitrate());
        assertEquals(.65f, settings.volume());
        assertEquals("listener", settings.username());
    }
}
