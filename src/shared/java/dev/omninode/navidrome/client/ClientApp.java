package dev.omninode.navidrome.client;

import dev.omninode.navidrome.api.SubsonicClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.nio.file.Path;

/** One local music session per Minecraft process; never sends credentials to a Minecraft server. */
public final class ClientApp implements AutoCloseable {
    private static ClientApp instance;
    private final ConnectionStore store;
    private final MusicPlayer player;
    private final CoverArtCache covers = new CoverArtCache();
    private ConnectionStore.Settings settings;
    private SubsonicClient api;
    private volatile String status = "";

    private ClientApp(Path configDir) {
        store = new ConnectionStore(configDir);
        settings = store.read();
        player = new MusicPlayer(this::setStatus);
        player.setBitrate(settings.bitrate());
        player.setVolume(settings.volume());
    }

    public static void initialize(Path configDir) {
        if (instance != null) return;
        instance = new ClientApp(configDir);
        Runtime.getRuntime().addShutdownHook(new Thread(instance::close, "navidrome-shutdown"));
    }

    public static ClientApp get() {
        if (instance == null) throw new IllegalStateException("ClientApp not initialized");
        return instance;
    }

    public void open() {
        Minecraft game = Minecraft.getInstance();
        Screen parent = game.gui.screen();
        if (!(parent instanceof MusicScreen)) game.gui.setScreen(new MusicScreen(this, parent));
    }

    public boolean connected() { return api != null; }
    public SubsonicClient api() { return api; }
    public MusicPlayer player() { return player; }
    public CoverArtCache covers() { return covers; }
    public ConnectionStore.Settings settings() { return settings; }
    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }

    /** Called on the client thread only after a successful ping. */
    public void connect(SubsonicClient validated) {
        disconnect();
        api = validated;
        player.setClient(validated);
        settings = new ConnectionStore.Settings(validated.baseUri().toString(), validated.username(),
                player.bitrate(), player.volume());
        saveSettings();
        status = "Connected to " + validated.baseUri().getHost();
    }

    public void rememberVolume(float value) { player.setVolume(value); saveSettings(); }
    public void rememberBitrate(int value) { player.setBitrate(value); saveSettings(); }

    private void saveSettings() {
        if (api != null) settings = new ConnectionStore.Settings(api.baseUri().toString(),
                api.username(), player.bitrate(), player.volume());
        try { store.write(settings); }
        catch (IOException ignored) { status = "Connected, but preferences could not be saved."; }
    }

    public void disconnect() {
        player.setClient(null);
        covers.clear();
        if (api != null) { api.close(); api = null; }
        status = "Disconnected.";
    }

    @Override public void close() {
        player.close();
        if (api != null) { api.close(); api = null; }
        // Don't call GPU texture cleanup from the JVM shutdown hook.
    }
}
