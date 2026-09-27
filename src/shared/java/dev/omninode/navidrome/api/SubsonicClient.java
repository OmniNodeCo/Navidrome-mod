package dev.omninode.navidrome.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.omninode.navidrome.api.Models.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small, intentionally non-Minecraft OpenSubsonic v1.16.1 client. JSON commands use
 * POST so credentials are not exposed in URLs; media GET requests require the API's
 * query authentication. Only connect to servers you trust, preferably over HTTPS.
 */
public final class SubsonicClient implements AutoCloseable {
    public enum Auth { PASSWORD, API_KEY }

    private static final String CLIENT_NAME = "navidrome-minecraft";
    private static final int MAX_JSON = 4 * 1024 * 1024;
    private static final int MAX_COVER = 2 * 1024 * 1024;
    private final URI base;
    private final String username;
    private final char[] secret;
    private final Auth auth;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "navidrome-api");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(7))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private volatile boolean closed;

    public SubsonicClient(String url, String username, char[] secret, Auth auth, boolean allowHttp) {
        if (url == null || url.isBlank() || url.length() > 2048) throw new ApiException("Enter a server URL.");
        URI uri;
        try { uri = URI.create(url.trim()).normalize(); }
        catch (IllegalArgumentException e) { throw new ApiException("Invalid server URL."); }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ApiException("Use a http(s) URL without credentials, query or fragment.");
        }
        if (scheme.equalsIgnoreCase("http") && !allowHttp && !isLoopback(uri.getHost())) {
            throw new ApiException("HTTPS required. Enable HTTP only on a trusted local network.");
        }
        String path = uri.toString().replaceAll("/+$", "");
        if (path.endsWith("/rest")) path = path.substring(0, path.length() - 5);
        this.base = URI.create(path);
        this.username = username == null ? "" : username.trim();
        this.auth = auth;
        if (auth == null || (auth == Auth.PASSWORD && this.username.isBlank())
                || secret == null || secret.length == 0) {
            executor.shutdownNow();
            throw new ApiException("Enter a username and password, or select API key.");
        }
        this.secret = secret.clone();
    }

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
                || host.equals("::1");
    }

    public URI baseUri() { return base; }
    public String username() { return username; }

    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private Map<String, List<String>> authParams() {
        if (closed) throw new ApiException("Disconnected.");
        Map<String, List<String>> p = new LinkedHashMap<>();
        if (auth == Auth.API_KEY) {
            // OpenSubsonic API keys must never be combined with u (error 43).
            add(p, "apiKey", new String(secret));
        } else {
            add(p, "u", username);
            byte[] salt = new byte[12];
            random.nextBytes(salt);
            String hexSalt = HexFormat.of().formatHex(salt);
            try {
                MessageDigest md5 = MessageDigest.getInstance("MD5"); // Subsonic token specification
                ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
                byte[] passwordBytes = new byte[encoded.remaining()];
                encoded.get(passwordBytes);
                md5.update(passwordBytes);
                Arrays.fill(passwordBytes, (byte) 0);
                if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
                add(p, "t", HexFormat.of().formatHex(md5.digest(hexSalt.getBytes(StandardCharsets.US_ASCII))));
                add(p, "s", hexSalt);
            } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("MD5 is unavailable", e); }
        }
        add(p, "v", "1.16.1");
        add(p, "c", CLIENT_NAME);
        add(p, "f", "json");
        return p;
    }

    private static void add(Map<String, List<String>> params, String key, String value) {
        if (value != null && !value.isEmpty()) params.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
    }

    private static Map<String, List<String>> args(String... pairs) {
        Map<String, List<String>> p = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) add(p, pairs[i], pairs[i + 1]);
        return p;
    }

    private static String form(Map<String, List<String>> params) {
        StringBuilder s = new StringBuilder();
        params.forEach((k, values) -> values.forEach(v -> {
            if (!s.isEmpty()) s.append('&');
            s.append(encode(k)).append('=').append(encode(v));
        }));
        return s.toString();
    }

    private Map<String, List<String>> parameters(Map<String, List<String>> args) {
        Map<String, List<String>> p = authParams();
        args.forEach((k, values) -> values.forEach(v -> add(p, k, v)));
        return p;
    }

    private URI endpoint(String name) {
        // Every endpoint is a hard-coded method name, never arbitrary user input.
        if (!name.matches("[A-Za-z0-9]+")) throw new IllegalArgumentException("Invalid endpoint");
        return URI.create(base + "/rest/" + name + ".view");
    }

    private static byte[] bounded(InputStream in, int max) throws IOException {
        byte[] bytes = in.readNBytes(max + 1);
        if (bytes.length > max) throw new ApiException("Server response exceeds size limit.");
        return bytes;
    }

    private JsonObject request(String method, Map<String, List<String>> args) {
        try {
            String body = form(parameters(args));
            HttpRequest request = HttpRequest.newBuilder(endpoint(method))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) throw new ApiException("Server returned HTTP " + response.statusCode());
                JsonElement parsed = JsonParser.parseString(new String(bounded(in, MAX_JSON), StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) throw new ApiException("Unexpected server response.");
                JsonObject root = object(parsed.getAsJsonObject(), "subsonic-response");
                if (!"ok".equals(string(root, "status"))) {
                    JsonObject error = object(root, "error");
                    String message = string(error, "message").replaceAll("[\\p{Cntrl}]", " ");
                    if (message.length() > 160) message = message.substring(0, 160);
                    throw new ApiException("Server error " + string(error, "code") + ": " + message);
                }
                return root;
            }
        } catch (ApiException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException("Request interrupted."); }
        catch (IOException e) { throw new ApiException("Cannot reach the music server. Check its URL and network."); }
        catch (RuntimeException e) { throw new ApiException("Invalid JSON from music server."); }
    }

    private CompletableFuture<JsonObject> call(String method, Map<String, List<String>> params) {
        return CompletableFuture.supplyAsync(() -> request(method, params), executor);
    }

    public CompletableFuture<Void> ping() { return call("ping", args()).thenAccept(ignored -> {}); }

    public CompletableFuture<List<Folder>> folders() {
        return call("getMusicFolders", args()).thenApply(root -> {
            List<Folder> result = new ArrayList<>();
            for (JsonObject obj : objects(object(root, "musicFolders"), "musicFolder")) {
                result.add(new Folder(string(obj, "id"), string(obj, "name")));
            }
            return result;
        });
    }

    public CompletableFuture<List<Album>> albums(String folder, String sort, int offset, int count) {
        if (!List.of("newest", "alphabeticalByName", "frequent", "recent", "random", "starred").contains(sort))
            throw new IllegalArgumentException("Unsupported sort order");
        return call("getAlbumList2", args("type", sort, "size", Integer.toString(Math.clamp(count, 1, 50)),
                "offset", Integer.toString(Math.max(0, offset)), "musicFolderId", folder))
                .thenApply(root -> objects(object(root, "albumList2"), "album").stream().map(SubsonicClient::album).toList());
    }

    public CompletableFuture<List<Artist>> artists(String folder) {
        return call("getArtists", args("musicFolderId", folder)).thenApply(root -> {
            List<Artist> result = new ArrayList<>();
            for (JsonObject index : objects(object(root, "artists"), "index")) {
                for (JsonObject artist : objects(index, "artist")) result.add(artist(artist));
            }
            return result;
        });
    }

    public CompletableFuture<List<Album>> artistAlbums(String id) {
        return call("getArtist", args("id", id)).thenApply(root ->
                objects(object(root, "artist"), "album").stream().map(SubsonicClient::album).toList());
    }

    public CompletableFuture<List<Song>> albumSongs(String id) {
        return call("getAlbum", args("id", id)).thenApply(root ->
                objects(object(root, "album"), "song").stream().map(SubsonicClient::song).toList());
    }

    /** Each search result type is paged independently at its own page size. */
    public CompletableFuture<Search> search(String query, String folder, int page) {
        int index = Math.max(0, page);
        return call("search3", args("query", query, "musicFolderId", folder,
                "artistCount", "15", "albumCount", "20", "songCount", "40",
                "artistOffset", Integer.toString(index * 15),
                "albumOffset", Integer.toString(index * 20),
                "songOffset", Integer.toString(index * 40)))
                .thenApply(root -> {
                    JsonObject result = object(root, "searchResult3");
                    return new Search(objects(result, "artist").stream().map(SubsonicClient::artist).toList(),
                            objects(result, "album").stream().map(SubsonicClient::album).toList(),
                            objects(result, "song").stream().map(SubsonicClient::song).toList());
                });
    }

    public CompletableFuture<List<Song>> randomSongs(String folder) {
        return call("getRandomSongs", args("size", "40", "musicFolderId", folder)).thenApply(root ->
                objects(object(root, "randomSongs"), "song").stream().map(SubsonicClient::song).toList());
    }

    public CompletableFuture<Starred> starred(String folder) {
        return call("getStarred2", args("musicFolderId", folder)).thenApply(root -> {
            JsonObject result = object(root, "starred2");
            return new Starred(objects(result, "artist").stream().map(SubsonicClient::artist).toList(),
                    objects(result, "album").stream().map(SubsonicClient::album).toList(),
                    objects(result, "song").stream().map(SubsonicClient::song).toList());
        });
    }

    public CompletableFuture<List<Song>> favorites() { return starred((String) null).thenApply(Starred::songs); }

    public CompletableFuture<List<Playlist>> playlists() {
        return call("getPlaylists", args()).thenApply(root ->
                objects(object(root, "playlists"), "playlist").stream().map(this::playlist).toList());
    }

    public CompletableFuture<Playlist> playlist(String id) {
        return call("getPlaylist", args("id", id)).thenApply(root -> playlist(object(root, "playlist")));
    }

    public CompletableFuture<Playlist> createPlaylist(String name) {
        if (name == null || name.isBlank() || name.length() > 128) throw new ApiException("Playlist name must be 1–128 characters.");
        return call("createPlaylist", args("name", name.strip())).thenApply(root -> playlist(object(root, "playlist")));
    }

    public CompletableFuture<Void> renamePlaylist(String id, String name) {
        if (name == null || name.isBlank() || name.length() > 128) throw new ApiException("Playlist name must be 1–128 characters.");
        return call("updatePlaylist", args("playlistId", id, "name", name.strip())).thenAccept(ignored -> {});
    }

    public CompletableFuture<Void> addToPlaylist(String playlistId, String songId) {
        return call("updatePlaylist", args("playlistId", playlistId, "songIdToAdd", songId)).thenAccept(ignored -> {});
    }

    public CompletableFuture<Void> removeFromPlaylist(String playlistId, int index) {
        return call("updatePlaylist", args("playlistId", playlistId,
                "songIndexToRemove", Integer.toString(index))).thenAccept(ignored -> {});
    }

    public CompletableFuture<Void> deletePlaylist(String id) {
        return call("deletePlaylist", args("id", id)).thenAccept(ignored -> {});
    }

    /** type is id (song), albumId or artistId. */
    public CompletableFuture<Void> star(String type, String id, boolean yes) {
        if (!List.of("id", "albumId", "artistId").contains(type)) throw new IllegalArgumentException("Invalid media type");
        return call(yes ? "star" : "unstar", args(type, id)).thenAccept(ignored -> {});
    }

    public CompletableFuture<Void> rate(String id, int rating) {
        if (rating < 0 || rating > 5) throw new IllegalArgumentException("Rating must be 0–5");
        return call("setRating", args("id", id, "rating", Integer.toString(rating))).thenAccept(ignored -> {});
    }

    /** This is necessary: Navidrome doesn't mark a song played just because it was streamed. */
    public CompletableFuture<Void> scrobble(String id, long startedAt, boolean submission) {
        return call("scrobble", args("id", id, "time", Long.toString(startedAt),
                "submission", Boolean.toString(submission))).thenAccept(ignored -> {});
    }

    public CompletableFuture<Lyrics> lyrics(Song s) {
        return call("getLyricsBySongId", args("id", s.id()))
                .thenApply(root -> {
                    List<JsonObject> variants = objects(object(root, "lyricsList"), "structuredLyrics");
                    if (variants.isEmpty()) return new Lyrics(false, List.of());
                    JsonObject selected = variants.stream().filter(o -> bool(o, "synced"))
                            .findFirst().orElse(variants.getFirst());
                    return new Lyrics(bool(selected, "synced"), objects(selected, "line").stream()
                            .map(o -> new Lyrics.Line(number(o, "start"), string(o, "value"))).toList());
                })
                .exceptionallyCompose(error -> {
                    Throwable cause = error;
                    while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                    String message = cause.getMessage() == null ? "" : cause.getMessage();
                    // Do not silently retry authentication failures or network errors as legacy lyrics.
                    if (!(cause instanceof ApiException) || !(message.startsWith("Server error 0:")
                            || message.contains("HTTP 404") || message.contains("HTTP 501"))) {
                        return CompletableFuture.failedFuture(cause);
                    }
                    return call("getLyrics", args("artist", s.artist(), "title", s.title()))
                            .thenApply(root -> {
                                String plain = string(object(root, "lyrics"), "value");
                                if (plain.isEmpty()) {
                                    JsonElement el = object(root, "lyrics").get("lyrics");
                                    if (el != null && el.isJsonPrimitive()) plain = el.getAsString();
                                }
                                return new Lyrics(false, plain.lines().map(line -> new Lyrics.Line(0, line)).toList());
                            });
                });
    }

    public CompletableFuture<User> user() {
        return call("getUser", args("username", username)).thenApply(root -> {
            JsonObject obj = object(root, "user");
            return new User(string(obj, "username"), bool(obj, "adminRole"),
                    bool(obj, "downloadRole"), bool(obj, "shareRole"));
        });
    }

    public CompletableFuture<Scan> scanStatus() {
        return call("getScanStatus", args()).thenApply(root -> {
            JsonObject obj = object(root, "scanStatus");
            return new Scan(bool(obj, "scanning"), number(obj, "count"), string(obj, "lastScan"));
        });
    }

    public CompletableFuture<Void> startScan(boolean full) {
        return call("startScan", args("fullScan", Boolean.toString(full))).thenAccept(ignored -> {});
    }

    /** Always asks Navidrome to transcode to MP3; JavaMP3 decodes incrementally. */
    public InputStream stream(String songId, int maxBitRate, int seconds) {
        Map<String, List<String>> p = parameters(args("id", songId, "format", "mp3",
                "maxBitRate", Integer.toString(maxBitRate), "timeOffset", Integer.toString(Math.max(0, seconds))));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint("stream") + "?" + form(p)))
                .timeout(Duration.ofSeconds(30)).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream in = response.body();
            if (response.statusCode() != 200) {
                in.close();
                throw new ApiException("Stream returned HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (contentType.contains("json") || contentType.contains("xml") || contentType.contains("html")) {
                in.close();
                throw new ApiException("Server could not provide an MP3 stream. Check transcoding/FFmpeg.");
            }
            return in;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException("Stream interrupted."); }
        catch (IOException e) { throw new ApiException("Music stream failed. Check network/transcoding."); }
    }

    public CompletableFuture<byte[]> coverArt(String id) {
        if (id == null || id.isBlank()) return CompletableFuture.completedFuture(new byte[0]);
        return CompletableFuture.supplyAsync(() -> {
            Map<String, List<String>> p = parameters(args("id", id, "size", "128"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint("getCoverArt") + "?" + form(p)))
                    .timeout(Duration.ofSeconds(18)).GET().build();
            try {
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body()) {
                    if (response.statusCode() != 200) throw new ApiException("Cover art returned HTTP " + response.statusCode());
                    return bounded(in, MAX_COVER);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException("Cover art interrupted."); }
            catch (IOException e) { throw new ApiException("Cover art unavailable."); }
        }, executor);
    }

    @Override public void close() {
        closed = true;
        Arrays.fill(secret, '\0');
        executor.shutdownNow();
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static List<JsonObject> objects(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull()) return List.of();
        List<JsonObject> result = new ArrayList<>();
        if (element.isJsonObject()) result.add(element.getAsJsonObject());
        else if (element.isJsonArray()) {
            for (JsonElement item : (JsonArray) element) if (item.isJsonObject()) result.add(item.getAsJsonObject());
        }
        return result;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    private static int number(JsonObject o, String key) {
        try { return Integer.parseInt(string(o, key)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static boolean bool(JsonObject o, String key) { return "true".equalsIgnoreCase(string(o, key)); }

    private static boolean starred(JsonObject o) { return !string(o, "starred").isEmpty(); }

    private static Song song(JsonObject o) {
        return new Song(string(o, "id"), string(o, "title"), string(o, "artist"),
                string(o, "album"), string(o, "albumId"), string(o, "coverArt"),
                number(o, "duration"), number(o, "track"), starred(o));
    }

    private static Album album(JsonObject o) {
        return new Album(string(o, "id"), string(o, "name"), string(o, "artist"),
                string(o, "coverArt"), number(o, "songCount"), starred(o));
    }

    private static Artist artist(JsonObject o) {
        return new Artist(string(o, "id"), string(o, "name"), string(o, "coverArt"),
                number(o, "albumCount"), starred(o));
    }

    private Playlist playlist(JsonObject o) {
        return new Playlist(string(o, "id"), string(o, "name"), number(o, "songCount"),
                !username.isEmpty() && username.equalsIgnoreCase(string(o, "owner")),
                objects(o, "entry").stream().map(SubsonicClient::song).toList());
    }
}
