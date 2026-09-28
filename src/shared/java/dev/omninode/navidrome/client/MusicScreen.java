package dev.omninode.navidrome.client;

import dev.omninode.navidrome.api.ApiException;
import dev.omninode.navidrome.api.Models.*;
import dev.omninode.navidrome.api.SubsonicClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Style;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** One in-game screen for browsing, search, playlists, lyrics, and playback. */
public final class MusicScreen extends Screen {
    private enum Page { ALBUMS, ARTISTS, SEARCH, LISTS, STARS, QUEUE, ALBUM, ARTIST, PLAYLIST,
                        PICK_LIST, EDIT_LIST, DELETE_LIST, LYRICS, SETTINGS }
    private record Row(Object item, String label, int songIndex) {}

    private static final class View {
        Page page = Page.ALBUMS;
        Page returnTo = Page.ALBUMS;
        Page albumReturnTo = Page.ALBUMS, artistReturnTo = Page.ARTISTS;
        List<Folder> folders;
        int folderIndex = -1; // -1 means all accessible music libraries
        String sort = "newest";
        String query = "";
        int remotePage, localPage, lyricsScroll, rating;
        Album album;
        Artist artist;
        Playlist playlist;
        Song pendingSong;
        Lyrics lyrics;
        User user;
        Scan scan;
    }

    private final ClientApp app;
    private final Screen parent;
    private final View view;
    private List<Row> rows = List.of();
    private List<Song> songs = List.of();
    private boolean moreRemote;
    private boolean loading;
    private Button previousPage;
    private Button nextPage;
    private Button folderButton;
    private Button backButton;
    private Button pauseButton;
    private Button skipButton;
    private EditBox address, username, secret, searchBox, playlistName;
    private SubsonicClient.Auth auth = SubsonicClient.Auth.PASSWORD;
    private boolean allowHttp;
    private int rowWidth;

    public MusicScreen(ClientApp app, Screen parent) { this(app, parent, new View()); }
    private MusicScreen(ClientApp app, Screen parent, View view) {
        super(Component.literal("Navidrome"));
        this.app = app;
        this.parent = parent;
        this.view = view;
    }

    @Override protected void init() {
        if (!app.connected()) { login(); return; }
        if (view.folders == null) {
            app.api().folders().thenAccept(result -> Minecraft.getInstance().execute(() -> {
                view.folders = result;
                if (this.minecraft.gui.screen() == this && folderButton != null) folderButton.setMessage(label(folderLabel()));
            })).exceptionally(e -> { app.setStatus(problem(e)); return null; });
        }
        int tabWidth = (width - 16) / 6;
        String[] names = { "Albums", "Artists", "Search", "Playlists", "Stars", "Queue" };
        Page[] pages = { Page.ALBUMS, Page.ARTISTS, Page.SEARCH, Page.LISTS, Page.STARS, Page.QUEUE };
        for (int i = 0; i < names.length; i++) {
            final Page page = pages[i];
            button(8 + i * tabWidth, 24, tabWidth - 1, names[i], () -> go(page));
        }
        header();
        previousPage = button(8, height - 101, 42, "< Page", () -> turn(-1));
        nextPage = button(width - 50, height - 101, 42, "Page >", () -> turn(1));
        previousPage.active = false;
        nextPage.active = false;
        boolean paged = switch (view.page) {
            case ALBUMS, ARTISTS, SEARCH, LISTS, PICK_LIST, STARS, QUEUE, ALBUM, ARTIST, PLAYLIST -> true;
            default -> false;
        };
        previousPage.visible = paged;
        nextPage.visible = paged;
        playerControls();
        loadPage();
    }

    private void login() {
        int x = width / 2 - Math.min(160, (width - 20) / 2);
        int w = Math.min(320, width - 20);
        int y = height / 2 - 66;
        address = input(x, y, w, "Server URL", app.settings().url(), 2048);
        username = input(x, y + 25, w, "Username", app.settings().username(), 128);
        secret = input(x, y + 50, w, "Password or API key (not saved)", "", 512);
        // EditBox stores the real value but never draws it on screen.
        secret.addFormatter((text, pos) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
        button(x, y + 77, w / 2 - 2,
                auth == SubsonicClient.Auth.PASSWORD ? "Password" : "API key", () -> {
            auth = auth == SubsonicClient.Auth.PASSWORD ? SubsonicClient.Auth.API_KEY : SubsonicClient.Auth.PASSWORD;
            goLogin();
        });
        button(x + w / 2, y + 77, w / 2, allowHttp ? "HTTP allowed (unsafe)" : "HTTPS only", () -> {
            allowHttp = !allowHttp;
            goLogin();
        });
        button(x, y + 102, w, "Connect to Navidrome", this::connect);
    }

    /** Redraw the login toggles without discarding what has been typed. */
    private void goLogin() {
        String url = address.getValue(), user = username.getValue(), password = secret.getValue();
        clearWidgets();
        login();
        address.setValue(url);
        username.setValue(user);
        secret.setValue(password);
    }

    private void connect() {
        char[] chars = secret.getValue().toCharArray();
        SubsonicClient candidate;
        try { candidate = new SubsonicClient(address.getValue(), username.getValue(), chars, auth, allowHttp); }
        catch (ApiException e) { app.setStatus(e.getMessage()); return; }
        finally { Arrays.fill(chars, '\0'); }
        app.setStatus("Connecting…");
        candidate.ping().whenComplete((unused, error) -> Minecraft.getInstance().execute(() -> {
            if (this.minecraft.gui.screen() != this) { candidate.close(); return; }
            if (error != null) { candidate.close(); app.setStatus(problem(error)); return; }
            secret.setValue("");
            app.connect(candidate);
            // Never reuse folder IDs, favorites or metadata from a previous server.
            view.folders = null;
            view.folderIndex = -1;
            view.artist = null;
            view.album = null;
            view.playlist = null;
            view.pendingSong = null;
            view.user = null;
            view.scan = null;
            view.lyrics = null;
            view.query = "";
            go(Page.ALBUMS);
        }));
    }

    private void header() {
        if (view.page == Page.EDIT_LIST) {
            button(8, 46, 45, "Back", () -> go(view.playlist == null ? Page.LISTS : Page.PLAYLIST));
            playlistName = input(58, 46, Math.max(80, width - 130), "Playlist name",
                    view.playlist == null ? "" : view.playlist.name(), 128);
            button(width - 66, 46, 58, "Save", this::savePlaylist);
            return;
        }
        if (view.page == Page.DELETE_LIST) {
            button(8, 46, 50, "Cancel", () -> go(Page.PLAYLIST));
            button(65, 46, 108, "Delete playlist", this::deletePlaylist);
            return;
        }
        if (view.page == Page.SEARCH) {
            searchBox = input(8, 46, Math.max(80, width - 150), "Search music", view.query, 150);
            button(width - 135, 46, 55, "Find", () -> {
                view.query = searchBox.getValue().trim(); view.remotePage = view.localPage = 0; reopen();
            });
            folderButton = button(width - 76, 46, 68, folderLabel(), this::cycleFolder);
            return;
        }
        if (view.page == Page.ALBUMS || view.page == Page.ARTISTS || view.page == Page.STARS) {
            folderButton = button(8, 46, 84, folderLabel(), this::cycleFolder);
            if (view.page == Page.ALBUMS) {
                button(97, 46, 98, "Sort: " + sortLabel(), this::cycleSort);
                button(199, 46, 43, "Mix", this::randomMix);
            }
            button(width - 74, 46, 66, "Settings", () -> go(Page.SETTINGS));
            return;
        }
        if (view.page == Page.ALBUM || view.page == Page.ARTIST || view.page == Page.PLAYLIST) {
            button(8, 46, 50, "Back", () -> go(view.page == Page.PLAYLIST ? Page.LISTS
                    : view.page == Page.ARTIST ? view.artistReturnTo : view.albumReturnTo));
            if (view.page == Page.ALBUM) {
                button(63, 46, 64, "Play all", () -> playAll());
                button(132, 46, 62, "Star", () -> star("albumId", view.album.id(), !view.album.starred()));
            } else if (view.page == Page.ARTIST) {
                button(63, 46, 64, "Star", () -> star("artistId", view.artist.id(), !view.artist.starred()));
            } else {
                button(63, 46, 52, "Play", this::playAll);
                button(119, 46, 50, "Add", this::addCurrentToPlaylist);
                button(173, 46, 54, "Rename", () -> go(Page.EDIT_LIST));
                button(231, 46, 56, "Delete", () -> go(Page.DELETE_LIST));
            }
            return;
        }
        if (view.page == Page.LISTS) {
            button(8, 46, 107, "New playlist", () -> { view.playlist = null; go(Page.EDIT_LIST); });
            button(width - 74, 46, 66, "Settings", () -> go(Page.SETTINGS));
        } else if (view.page == Page.PICK_LIST) {
            button(8, 46, 50, "Cancel", () -> go(view.returnTo));
        } else if (view.page == Page.LYRICS) {
            button(8, 46, 50, "Back", () -> go(view.returnTo));
            button(63, 46, 50, "Up", () -> { view.lyricsScroll = Math.max(0, view.lyricsScroll - 4); });
            button(118, 46, 50, "Down", () -> { view.lyricsScroll += 4; });
        } else if (view.page == Page.SETTINGS) {
            button(8, 46, 50, "Back", () -> go(Page.ALBUMS));
            button(width - 84, 46, 76, "Disconnect", () -> { app.disconnect(); reopen(); });
        } else if (view.page == Page.QUEUE) {
            button(8, 46, 66, "Play all", () -> app.player().playQueueIndex(0));
            button(80, 46, 65, "Clear", () -> { app.player().clearQueue(); reopen(); });
        }
    }

    private void playerControls() {
        MusicPlayer player = app.player();
        // Give the transport controls their own full-width row so they are usable at 320px.
        int transportStep = (width - 16) / 3;
        backButton = button(8, height - 50, transportStep - 1, "Back", () -> {
            player.previous(); refreshTransportButtons();
        });
        pauseButton = button(8 + transportStep, height - 50, transportStep - 1, "Play", () -> {
            player.togglePause(); refreshTransportButtons();
        });
        skipButton = button(8 + 2 * transportStep, height - 50, transportStep - 1, "Skip", () -> {
            player.next(); refreshTransportButtons();
        });
        refreshTransportButtons();

        // Keep seek, stop, shuffle, repeat, volume, rating and lyrics on a compact second row.
        String[] labels = { "-10", "+10", "Stop", "Shf", "Rpt", "V-", "V+", "Rate", "Ly" };
        int step = (width - 16) / labels.length;
        for (int i = 0; i < labels.length; i++) {
            final int action = i;
            button(8 + i * step, height - 28, step - 1, labels[i], () -> {
                switch (action) {
                    case 0 -> player.seek(player.position() - 10);
                    case 1 -> player.seek(player.position() + 10);
                    case 2 -> { player.stop(); refreshTransportButtons(); }
                    case 3 -> player.toggleShuffle();
                    case 4 -> player.cycleRepeat();
                    case 5 -> app.rememberVolume(player.volume() - .1f);
                    case 6 -> app.rememberVolume(player.volume() + .1f);
                    case 7 -> {
                        Song current = player.current();
                        if (current != null) {
                            view.rating = (view.rating + 1) % 6;
                            change(app.api().rate(current.id(), view.rating), "Rated " + view.rating + "/5");
                        }
                    }
                    case 8 -> {
                        if (view.page != Page.LYRICS) {
                            view.returnTo = view.page;
                            go(Page.LYRICS);
                        }
                    }
                    default -> throw new IllegalStateException();
                }
            });
        }
    }

    private void refreshTransportButtons() {
        MusicPlayer player = app.player();
        boolean hasTrack = player.current() != null;
        backButton.active = hasTrack;
        pauseButton.active = hasTrack;
        skipButton.active = hasTrack;
        String text = player.state() == MusicPlayer.State.PAUSED || player.state() == MusicPlayer.State.STOPPED
                ? "Play" : "Pause";
        if (!pauseButton.getMessage().getString().equals(text)) pauseButton.setMessage(label(text));
    }

    private void loadPage() {
        SubsonicClient api = app.api();
        moreRemote = false;
        switch (view.page) {
            case ALBUMS -> load(api.albums(folder(), view.sort, view.remotePage * 20, 20), albums -> {
                moreRemote = albums.size() == 20;
                show(albums.stream().map(a -> new Row(a, a.toString(), -1)).toList(), List.of());
            });
            case ARTISTS -> load(api.artists(folder()), artists -> show(
                    artists.stream().map(a -> new Row(a, a.name() + " (" + a.albumCount() + ")", -1)).toList(), List.of()));
            case SEARCH -> {
                if (view.query.isBlank()) { app.setStatus("Type an artist, album or song, then click Find."); return; }
                load(api.search(view.query, folder(), view.remotePage), result -> {
                    List<Row> found = new ArrayList<>();
                    for (Artist a : result.artists()) found.add(new Row(a, "Artist: " + a.name(), -1));
                    for (Album a : result.albums()) found.add(new Row(a, "Album: " + a.name(), -1));
                    for (int i = 0; i < result.songs().size(); i++) {
                        Song s = result.songs().get(i);
                        found.add(new Row(s, s.toString(), i));
                    }
                    moreRemote = result.songs().size() == 40 || result.albums().size() == 20
                            || result.artists().size() == 15;
                    show(found, result.songs());
                });
            }
            case LISTS, PICK_LIST -> load(api.playlists(), lists -> show(lists.stream()
                    .map(p -> new Row(p, p.toString(), -1)).toList(), List.of()));
            case STARS -> load(api.starred(folder()), result -> {
                List<Row> starred = new ArrayList<>();
                for (Artist a : result.artists()) starred.add(new Row(a, "Artist: " + a.name(), -1));
                for (Album a : result.albums()) starred.add(new Row(a, "Album: " + a.name(), -1));
                starred.addAll(songRows(result.songs()));
                show(starred, result.songs());
            });
            case QUEUE -> {
                List<Song> queued = app.player().queue();
                show(songRows(queued), queued);
            }
            case ALBUM -> load(api.albumSongs(view.album.id()), found -> show(songRows(found), found));
            case ARTIST -> load(api.artistAlbums(view.artist.id()), albums -> show(albums.stream()
                    .map(a -> new Row(a, a.toString(), -1)).toList(), List.of()));
            case PLAYLIST -> load(api.playlist(view.playlist.id()), p -> {
                view.playlist = p;
                show(songRows(p.songs()), p.songs());
            });
            case LYRICS -> {
                Song song = app.player().current();
                view.lyrics = null;
                if (song == null) { app.setStatus("Play a track to view its lyrics."); return; }
                load(api.lyrics(song), result -> { view.lyrics = result; app.setStatus(result.lines().isEmpty() ? "No lyrics for this track." : "Lyrics loaded."); });
            }
            case SETTINGS -> settingsPage();
            case EDIT_LIST, DELETE_LIST -> {} // edit controls in header
        }
    }

    private static List<Row> songRows(List<Song> songs) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < songs.size(); i++) out.add(new Row(songs.get(i), songs.get(i).toString(), i));
        return out;
    }

    private <T> void load(CompletableFuture<T> future, Consumer<T> onSuccess) {
        loading = true;
        future.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            if (this.minecraft.gui.screen() != this) return;
            loading = false;
            if (error != null) { app.setStatus(problem(error)); return; }
            onSuccess.accept(result);
        }));
    }

    private void show(List<Row> loaded, List<Song> playable) {
        rows = loaded;
        songs = playable;
        int perPage = rowsPerPage();
        if (view.localPage * perPage >= rows.size()) view.localPage = 0;
        int start = view.localPage * perPage;
        int end = Math.min(rows.size(), start + perPage);
        boolean art = artId() != null && width >= 400;
        rowWidth = width - (art ? 126 : 16);
        for (int i = start; i < end; i++) {
            Row row = rows.get(i);
            int y = 78 + (i - start) * 22;
            int buttons = row.item() instanceof Song ? 3 : 0;
            int titleWidth = rowWidth - (buttons == 0 ? 0 : 78);
            button(8, y, titleWidth, shorten(row.label(), titleWidth - 12), () -> openRow(row));
            if (row.item() instanceof Song song) {
                int bx = 8 + titleWidth + 2;
                if (view.page == Page.QUEUE) {
                    button(bx, y, 23, "-", () -> {
                        app.player().removeFromQueue(row.songIndex());
                        reopen();
                    });
                    button(bx + 25, y, 23, "^", () -> {
                        app.player().moveUp(row.songIndex());
                        reopen();
                    });
                } else {
                    button(bx, y, 23, "Q", () -> { app.player().enqueue(song); app.setStatus("Added to queue."); });
                    if (view.page == Page.PLAYLIST) {
                        button(bx + 25, y, 23, "-", () -> change(api().removeFromPlaylist(view.playlist.id(), row.songIndex()),
                                "Removed from playlist.", true));
                    } else {
                        button(bx + 25, y, 23, "L", () -> {
                            view.pendingSong = song; view.returnTo = view.page; go(Page.PICK_LIST);
                        });
                    }
                }
                button(bx + 50, y, 23, song.starred() ? "*" : "+", () -> star("id", song.id(), !song.starred()));
            }
        }
        previousPage.active = view.localPage > 0 || view.remotePage > 0;
        nextPage.active = end < rows.size() || moreRemote;
        if (rows.isEmpty() && view.page != Page.QUEUE) app.setStatus("No results in this view.");
    }

    private void openRow(Row row) {
        switch (row.item()) {
            case Artist a -> { view.artist = a; view.artistReturnTo = view.page; go(Page.ARTIST); }
            case Album a -> { view.album = a; view.albumReturnTo = view.page; go(Page.ALBUM); }
            case Playlist p -> {
                if (view.page == Page.PICK_LIST && view.pendingSong != null) {
                    change(api().addToPlaylist(p.id(), view.pendingSong.id()), "Added to " + p.name() + ".", view.returnTo);
                } else { view.playlist = p; go(Page.PLAYLIST); }
            }
            case Song s -> {
                if (view.page == Page.QUEUE) app.player().playQueueIndex(row.songIndex());
                else app.player().play(songs, row.songIndex());
                app.setStatus("Playing " + s.title());
            }
            default -> {} // unreachable
        }
    }

    private void playAll() {
        if (!songs.isEmpty()) { app.player().play(songs, 0); app.setStatus("Playing " + songs.size() + " tracks."); }
    }

    private void randomMix() {
        load(api().randomSongs(folder()), random -> {
            if (random.isEmpty()) { app.setStatus("No tracks in this library."); return; }
            app.player().play(random, 0);
            app.setStatus("Playing a random mix of " + random.size() + " tracks.");
            go(Page.QUEUE);
        });
    }

    private void addCurrentToPlaylist() {
        Song now = app.player().current();
        if (now == null || view.playlist == null) { app.setStatus("Play a song first."); return; }
        change(api().addToPlaylist(view.playlist.id(), now.id()), "Added song to playlist.", true);
    }

    private void savePlaylist() {
        String name = playlistName.getValue().strip();
        if (name.isBlank()) { app.setStatus("Enter a playlist name."); return; }
        if (view.playlist == null) {
            load(api().createPlaylist(name), p -> { app.setStatus("Playlist created."); go(Page.LISTS); });
        } else {
            change(api().renamePlaylist(view.playlist.id(), name), "Playlist renamed.", Page.LISTS);
        }
    }

    private void deletePlaylist() {
        change(api().deletePlaylist(view.playlist.id()), "Playlist deleted.", Page.LISTS);
    }

    private void settingsPage() {
        button(8, 80, 118, "Open server UI", this::openBrowser);
        button(8, 104, 118, "Bitrate: " + app.player().bitrate() + "k", () -> {
            int[] rates = {96, 128, 192, 256, 320};
            int value = app.player().bitrate();
            int next = 0;
            for (int i = 0; i < rates.length; i++) if (rates[i] == value) next = (i + 1) % rates.length;
            app.rememberBitrate(rates[next]);
            reopen();
        });
        button(8, 124, 118, "Scan library", () -> change(api().startScan(false), "Scan requested.", true));
        button(132, 124, 118, "Full scan", () -> change(api().startScan(true), "Full scan requested.", true));
        if (!api().username().isBlank()) load(api().user(), result -> view.user = result);
        api().scanStatus().thenAccept(result -> Minecraft.getInstance().execute(() -> view.scan = result))
                .exceptionally(error -> null);
    }

    private void openBrowser() {
        URI uri = api().baseUri(); // URL never includes credentials
        Thread t = new Thread(() -> {
            try {
                if (!Desktop.isDesktopSupported()) throw new UnsupportedOperationException();
                Desktop.getDesktop().browse(uri);
            } catch (Exception e) { app.setStatus("Cannot open a browser here. Use " + uri.getHost()); }
        }, "navidrome-browser");
        t.setDaemon(true);
        t.start();
    }

    private void star(String kind, String id, boolean yes) {
        api().star(kind, id, yes).whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
            if (this.minecraft.gui.screen() != this) return;
            if (error != null) { app.setStatus(problem(error)); return; }
            if ("albumId".equals(kind) && view.album != null && view.album.id().equals(id)) {
                Album a = view.album;
                view.album = new Album(a.id(), a.name(), a.artist(), a.coverArt(), a.songCount(), yes);
            } else if ("artistId".equals(kind) && view.artist != null && view.artist.id().equals(id)) {
                Artist a = view.artist;
                view.artist = new Artist(a.id(), a.name(), a.coverArt(), a.albumCount(), yes);
            } else if ("id".equals(kind)) app.player().setStarred(id, yes);
            app.setStatus(yes ? "Starred." : "Unstarred.");
            reopen();
        }));
    }
    private SubsonicClient api() { return app.api(); }

    private void change(CompletableFuture<Void> operation, String success) { change(operation, success, false); }
    private void change(CompletableFuture<Void> operation, String success, boolean refresh) {
        operation.whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
            if (this.minecraft.gui.screen() != this) return;
            app.setStatus(error == null ? success : problem(error));
            if (error == null && refresh) reopen();
        }));
    }
    private void change(CompletableFuture<Void> operation, String success, Page destination) {
        operation.whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
            if (this.minecraft.gui.screen() != this) return;
            app.setStatus(error == null ? success : problem(error));
            if (error == null) go(destination);
        }));
    }

    private void turn(int direction) {
        int per = rowsPerPage();
        if (direction > 0 && (view.localPage + 1) * per < rows.size()) view.localPage++;
        else if (direction < 0 && view.localPage > 0) view.localPage--;
        else if (direction > 0 && moreRemote) { view.remotePage++; view.localPage = 0; }
        else if (direction < 0 && view.remotePage > 0) { view.remotePage--; view.localPage = 0; }
        reopen();
    }

    // Leave room for pagination, status, now-playing and both rows of playback controls.
    private int rowsPerPage() { return Math.max(1, (height - 179) / 22); }
    private String folder() {
        if (view.folders == null || view.folderIndex < 0 || view.folderIndex >= view.folders.size()) return null;
        return view.folders.get(view.folderIndex).id();
    }
    private String folderLabel() {
        if (view.folders == null) return "Library…";
        if (view.folderIndex < 0 || view.folderIndex >= view.folders.size()) return "All libraries";
        return shorten(view.folders.get(view.folderIndex).name(), 73);
    }
    private void cycleFolder() {
        if (view.folders == null || view.folders.isEmpty()) return;
        view.folderIndex++; // -1 (all libraries) -> 0 (first library)
        if (view.folderIndex >= view.folders.size()) view.folderIndex = -1;
        view.localPage = view.remotePage = 0;
        go(view.page);
    }
    private String sortLabel() {
        return switch (view.sort) {
            case "alphabeticalByName" -> "A–Z";
            case "newest" -> "New";
            case "recent" -> "Recent";
            case "frequent" -> "Popular";
            case "random" -> "Random";
            default -> "Starred";
        };
    }
    private void cycleSort() {
        String[] sorts = { "newest", "alphabeticalByName", "recent", "frequent", "random", "starred" };
        int i = Arrays.asList(sorts).indexOf(view.sort);
        view.sort = sorts[(i + 1) % sorts.length];
        view.localPage = view.remotePage = 0;
        reopen();
    }
    private void go(Page page) {
        view.page = page;
        view.localPage = view.remotePage = 0;
        if (page == Page.ALBUMS) view.artist = null;
        reopen();
    }
    private void reopen() { this.minecraft.gui.setScreen(new MusicScreen(app, parent, view)); }

    private Button button(int x, int y, int w, String text, Runnable callback) {
        return addRenderableWidget(Button.builder(label(text), b -> callback.run()).bounds(x, y, Math.max(20, w), 18).build());
    }
    private EditBox input(int x, int y, int w, String hint, String value, int max) {
        EditBox box = new EditBox(font, x, y, Math.max(w, 40), 18, label(hint));
        box.setMaxLength(max);
        box.setHint(label(hint));
        box.setValue(value);
        return addRenderableWidget(box);
    }
    private static Component label(String text) { return Component.literal(text); }
    private String shorten(String text, int pixels) {
        if (text == null) return "";
        while (text.length() > 1 && font.width(text) > pixels) text = text.substring(0, text.length() - 2) + "…";
        return text;
    }

    private String artId() {
        if (view.page == Page.ALBUM && view.album != null) return view.album.coverArt();
        if (view.page == Page.ARTIST && view.artist != null) return view.artist.coverArt();
        Song now = app.player().current();
        return now == null ? null : now.coverArt();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Playback can change in the background (buffering, end of a track, or queue changes).
        if (pauseButton != null) refreshTransportButtons();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(font, "Navidrome  •  " + view.page.name().toLowerCase(), 8, 8, 0xffb0e6dc, true);
        if (app.connected() && !loading && rows.isEmpty() && view.page == Page.QUEUE)
            graphics.text(font, "Queue empty. Add songs with Q on a track.", 8, 79, 0xffaaaaaa, false);
        if (loading) graphics.text(font, "Loading music…", 8, 68, 0xffb0e6dc, false);
        if (view.page == Page.LYRICS && view.lyrics != null) {
            List<Lyrics.Line> lines = view.lyrics.lines();
            int visible = Math.max(1, (height - 175) / 12);
            int active = 0;
            if (view.lyrics.synced()) {
                for (int i = 0; i < lines.size(); i++) if (lines.get(i).startMs() <= app.player().position() * 1000L) active = i;
            }
            int start = view.lyrics.synced() ? Math.max(0, active - 2) : Math.min(view.lyricsScroll, Math.max(0, lines.size() - visible));
            for (int i = start; i < Math.min(start + visible, lines.size()); i++) {
                graphics.text(font, shorten(lines.get(i).text(), width - 20), 8, 78 + (i - start) * 12,
                        view.lyrics.synced() && i == active ? 0xfff2df9f : 0xffeeeeee, false);
            }
        }
        if (app.connected() && width >= 400 && artId() != null) {
            Identifier texture = app.covers().get(artId(), app);
            graphics.fill(width - 110, 76, width - 8, 178, 0xff1a2630);
            if (texture != null) graphics.blit(RenderPipelines.GUI_TEXTURED, texture,
                    width - 107, 79, 0, 0, 96, 96, 96, 96);
        }
        if (view.page == Page.SETTINGS) {
            if (view.user != null) graphics.text(font, shorten("Signed in: " + view.user.username()
                    + (view.user.admin() ? " (admin)" : ""), width - 16), 8, 68, 0xffdddddd, false);
            if (view.scan != null) graphics.text(font, "Scanning: " + view.scan.scanning()
                    + "  Files: " + view.scan.count(), 8, 144, 0xffdddddd, false);
            graphics.text(font, shorten("Users & transcoding: manage in Navidrome web UI.", width - 16),
                    8, 154, 0xffaaaaaa, false);
        }
        if (app.connected()) {
            int per = rowsPerPage();
            if (!rows.isEmpty()) {
                int first = view.localPage * per + 1;
                int last = Math.min(rows.size(), (view.localPage + 1) * per);
                String range = view.page == Page.SEARCH
                        ? "Search " + (view.remotePage + 1) + ": " + first + "–" + last
                        : (view.remotePage * 20 + first) + "–" + (view.remotePage * 20 + last)
                            + " / " + (view.remotePage * 20 + rows.size()) + (moreRemote ? "+" : "");
                graphics.text(font, range, 58, height - 76, 0xffcccccc, false);
            }
            MusicPlayer player = app.player();
            Song now = player.current();
            String info = now == null ? "Nothing playing" : now.title() + " — " + now.artist()
                    + "   " + clock(player.position()) + "/" + clock(now.duration())
                    + "   " + player.state() + "   " + (player.shuffle() ? "Shuffle " : "")
                    + player.repeat() + "   Vol " + (int) (player.volume() * 100) + "%";
            graphics.text(font, shorten(info, width - 16), 8, height - 62, 0xfff3eedc, false);
        } else {
            graphics.text(font, "Connect to your existing Navidrome server. Password stays in memory.",
                    8, height - 37, 0xffb0e6dc, false);
        }
        if (!app.status().isBlank()) {
            int statusWidth = app.connected() && width >= 400 && artId() != null ? width - 126 : width - 16;
            graphics.text(font, shorten(app.status(), statusWidth), 8,
                    height - (app.connected() ? 74 : 61), 0xffe6c98d, false);
        }
    }

    private static String clock(int seconds) { return (seconds / 60) + ":" + String.format("%02d", seconds % 60); }

    private static String problem(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error instanceof ApiException ? error.getMessage() : "Request failed (" + error.getClass().getSimpleName() + ").";
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
