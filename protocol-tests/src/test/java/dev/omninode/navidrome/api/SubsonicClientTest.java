package dev.omninode.navidrome.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.omninode.navidrome.api.Models.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class SubsonicClientTest {
    private HttpServer server;
    private SubsonicClient client;
    private final List<String> calls = new ArrayList<>();
    private final List<String> verbs = new ArrayList<>();
    private final List<String> rawQueries = new ArrayList<>();
    private final List<Map<String, String>> forms = new ArrayList<>();
    private boolean legacyLyricsOnly;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/music/rest/", this::handle);
        server.start();
        client = new SubsonicClient("http://127.0.0.1:" + server.getAddress().getPort() + "/music/",
                "Bj örk", "p@ss word".toCharArray(), SubsonicClient.Auth.PASSWORD, false);
    }

    @AfterEach void stop() { client.close(); server.stop(0); }

    private static Map<String, String> decode(String data) {
        Map<String, String> values = new HashMap<>();
        for (String piece : data.split("&")) {
            String[] kv = piece.split("=", 2);
            if (kv.length == 2) values.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private void handle(HttpExchange request) throws IOException {
        String method = request.getRequestURI().getPath().replace("/music/rest/", "").replace(".view", "");
        String body = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = decode(request.getRequestURI().getRawQuery() == null
                ? body : request.getRequestURI().getRawQuery());
        synchronized (calls) {
            calls.add(method);
            forms.add(values);
            verbs.add(request.getRequestMethod());
            rawQueries.add(request.getRequestURI().getRawQuery());
        }
        String data = switch (method) {
            case "ping", "star", "unstar", "updatePlaylist", "deletePlaylist", "setRating",
                    "scrobble", "startScan" -> "{}";
            case "getMusicFolders" -> "{\"musicFolders\":{\"musicFolder\":["
                    + "{\"id\":\"family-uuid\",\"name\":\"Family\"},{\"id\":\"work\",\"name\":\"Work\"}]}}";
            case "getAlbumList2" -> "{\"albumList2\":{\"album\":[{\"id\":\"album-a\",\"name\":\"One\","
                    + "\"artist\":\"Björk\",\"songCount\":2}]}}";
            case "getArtists" -> "{\"artists\":{\"index\":[{\"name\":\"B\",\"artist\":["
                    + "{\"id\":\"uuid-artist\",\"name\":\"Björk\"}]}]}}";
            case "getArtist" -> "{\"artist\":{\"id\":\"uuid-artist\",\"album\":[{\"id\":\"album-a\",\"name\":\"One\"}]}}";
            case "getAlbum" -> "{\"album\":{\"id\":\"album-a\",\"song\":[{\"id\":\"128-uuid\","
                    + "\"title\":\"Jóga\",\"artist\":\"Björk\",\"albumId\":\"album-a\","
                    + "\"duration\":200,\"starred\":\"2026-01-01T00:00:00Z\"}]}}";
            case "search3" -> "{\"searchResult3\":{\"artist\":[],\"album\":[],"
                    + "\"song\":[{\"id\":\"128-uuid\",\"title\":\"Jóga\"}]}}";
            case "getRandomSongs" -> "{\"randomSongs\":{\"song\":[]}}";
            case "getStarred2" -> "{\"starred2\":{\"artist\":{\"id\":\"uuid-artist\",\"name\":\"Björk\"},"
                    + "\"album\":{\"id\":\"album-a\",\"name\":\"One\"},"
                    + "\"song\":{\"id\":\"128-uuid\",\"title\":\"Jóga\"}}}";
            case "getPlaylists" -> "{\"playlists\":{\"playlist\":[{\"id\":\"list-uuid\",\"name\":\"Favorites\","
                    + "\"owner\":\"Bj örk\",\"songCount\":1}]}}";
            case "getPlaylist", "createPlaylist" -> "{\"playlist\":{\"id\":\"list-uuid\",\"name\":\"Favorites\","
                    + "\"owner\":\"Bj örk\",\"songCount\":1,\"entry\":[{\"id\":\"128-uuid\",\"title\":\"Jóga\"}]}}";
            case "getUser" -> "{\"user\":{\"username\":\"Bj örk\",\"adminRole\":true,\"downloadRole\":true}}";
            case "getScanStatus" -> "{\"scanStatus\":{\"scanning\":false,\"count\":100}}";
            case "getLyricsBySongId" -> "{\"lyricsList\":{\"structuredLyrics\":[{\"synced\":true,\"line\":[{\"start\":1000,\"value\":\"hello\"}]}]}}";
            case "getLyrics" -> "{\"lyrics\":{\"value\":\"hello\\nworld\"}}";
            case "stream" -> null;
            case "getCoverArt" -> null;
            default -> "{\"error\":{\"code\":0,\"message\":\"not found\"}}";
        };
        String fields = data == null ? "" : data.substring(1, data.length() - 1);
        String json = "unauthorized".equals(values.get("u"))
                ? "{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":40,\"message\":\"Invalid credentials\"}}}"
                : method.equals("getLyricsBySongId") && legacyLyricsOnly
                    ? "{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":0,\"message\":\"unsupported\"}}}"
                    : "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\""
                      + (fields.isEmpty() ? "" : "," + fields) + "}}";
        byte[] bytes = method.equals("stream") ? new byte[] {0x01, 0x02, 0x03}
                : method.equals("getCoverArt") && "oversize".equals(values.get("id"))
                        ? new byte[2 * 1024 * 1024 + 1]
                : method.equals("getCoverArt") ? new byte[] {0x10, 0x20}
                : json.getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", data == null ? "audio/mpeg" : "application/json");
        request.sendResponseHeaders(200, bytes.length);
        try (var output = request.getResponseBody()) { output.write(bytes); }
    }

    private Map<String, String> last() { synchronized (calls) { return forms.getLast(); } }
    private List<String> called() { synchronized (calls) { return List.copyOf(calls); } }

    @Test void postTokenIsSaltedAndNotInUrl() throws Exception {
        client.ping().join();
        var values = last();
        assertEquals("Bj örk", values.get("u"));
        assertEquals("POST", verbs.getLast());
        assertNull(rawQueries.getLast(), "JSON commands must not leak credentials in the URL");
        assertTrue(values.containsKey("t"));
        assertEquals("1.16.1", values.get("v"));
        assertEquals("json", values.get("f"));
        assertEquals(24, values.get("s").length());
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(("p@ss word" + values.get("s")).getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(digest), values.get("t"));
        assertFalse(values.toString().contains("p@ss word"));
        client.ping().join();
        assertNotEquals(values.get("s"), last().get("s"), "Every request must use a fresh salt");
    }

    @Test void apiKeyMustNotSendUsernameOrToken() {
        try (var api = new SubsonicClient("http://127.0.0.1:" + server.getAddress().getPort() + "/music",
                "even-if-provided", "a-private-key".toCharArray(), SubsonicClient.Auth.API_KEY, false)) {
            api.ping().join();
            assertEquals("a-private-key", last().get("apiKey"));
            assertFalse(last().containsKey("u"));
            assertFalse(last().containsKey("t"));
            assertFalse(last().containsKey("s"));
        }
    }

    @Test void browsingAndMultiLibraryKeepStringIds() {
        assertEquals("family-uuid", client.folders().join().getFirst().id());
        assertEquals("uuid-artist", client.artists("family-uuid").join().getFirst().id());
        assertEquals("family-uuid", last().get("musicFolderId"));
        assertEquals("album-a", client.albums("work", "newest", 0, 20).join().getFirst().id());
        assertEquals("work", last().get("musicFolderId"));
        assertEquals("album-a", client.artistAlbums("uuid-artist").join().getFirst().id());
        Song song = client.albumSongs("album-a").join().getFirst();
        assertEquals("128-uuid", song.id());
        assertTrue(song.starred());
        assertEquals("128-uuid", client.search("Jóga", "work", 0).join().songs().getFirst().id());
        assertEquals("Jóga", last().get("query"));
        client.search("Jóga", "work", 1).join();
        assertEquals("15", last().get("artistOffset"));
        assertEquals("20", last().get("albumOffset"));
        assertEquals("40", last().get("songOffset"));
        assertEquals("128-uuid", client.favorites().join().getFirst().id());
        Starred stars = client.starred("work").join();
        assertEquals("work", last().get("musicFolderId"));
        assertEquals("album-a", stars.albums().getFirst().id());
        assertEquals("uuid-artist", stars.artists().getFirst().id());
        assertTrue(client.randomSongs(null).join().isEmpty());
    }

    @Test void playlistsAnnotationsLyricsAndScanUseDocumentedEndpoints() {
        assertTrue(client.playlists().join().getFirst().owned());
        assertEquals("128-uuid", client.playlist("list-uuid").join().songs().getFirst().id());
        assertEquals("list-uuid", client.createPlaylist("Favorites").join().id());
        client.addToPlaylist("list-uuid", "128-uuid").join();
        assertEquals("128-uuid", last().get("songIdToAdd"));
        client.removeFromPlaylist("list-uuid", 0).join();
        assertEquals("0", last().get("songIndexToRemove"));
        client.renamePlaylist("list-uuid", "Other").join();
        client.deletePlaylist("list-uuid").join();
        client.star("albumId", "album-a", true).join();
        assertEquals("album-a", last().get("albumId"));
        client.rate("128-uuid", 4).join();
        assertEquals("4", last().get("rating"));
        client.scrobble("128-uuid", 1234567, true).join();
        assertEquals("true", last().get("submission"));
        var song = new Song("128-uuid", "Jóga", "Björk", "", "", "", 200, 1, false);
        assertTrue(client.lyrics(song).join().synced());
        assertEquals(1000, client.lyrics(song).join().lines().getFirst().startMs());
        assertTrue(client.user().join().admin());
        assertEquals(100, client.scanStatus().join().count());
        client.startScan(true).join();
        assertEquals("true", last().get("fullScan"));
        assertTrue(called().contains("getLyricsBySongId"));
    }

    @Test void rejectsUnsafeUrlsAndAuthenticatesMediaGets() throws Exception {
        assertThrows(ApiException.class, () -> new SubsonicClient("file:///etc/passwd", "x", new char[]{'x'},
                SubsonicClient.Auth.PASSWORD, false));
        assertThrows(ApiException.class, () -> new SubsonicClient("http://example.com", "x", new char[]{'x'},
                SubsonicClient.Auth.PASSWORD, false));
        assertThrows(ApiException.class, () -> new SubsonicClient("http://x:secret@example.com", "x", new char[]{'x'},
                SubsonicClient.Auth.PASSWORD, true));
        try (var input = client.stream("128-uuid", 192, 15)) { assertEquals(1, input.read()); }
        assertEquals("GET", verbs.getLast());
        assertNotNull(rawQueries.getLast(), "Subsonic streaming requires GET authentication");
        assertEquals("15", last().get("timeOffset"));
        assertEquals("mp3", last().get("format"));
        assertArrayEquals(new byte[]{0x10, 0x20}, client.coverArt("album-a").join());
        assertEquals("128", last().get("size"));
    }

    @Test void failuresAreReportedAndCoverArtIsBounded() {
        try (var wrong = new SubsonicClient("http://127.0.0.1:" + server.getAddress().getPort() + "/music",
                "unauthorized", "bad".toCharArray(), SubsonicClient.Auth.PASSWORD, false)) {
            CompletionException error = assertThrows(CompletionException.class, () -> wrong.ping().join());
            assertInstanceOf(ApiException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("Invalid credentials"));
            assertFalse(error.getCause().getMessage().contains("bad"));
            var song = new Song("128-uuid", "Jóga", "Björk", "", "", "", 200, 1, false);
            assertThrows(CompletionException.class, () -> wrong.lyrics(song).join());
            assertFalse(called().contains("getLyrics"), "Authentication errors must not trigger a legacy retry");
        }
        CompletionException large = assertThrows(CompletionException.class, () -> client.coverArt("oversize").join());
        assertInstanceOf(ApiException.class, large.getCause());
        assertTrue(large.getCause().getMessage().contains("size limit"));
    }

    @Test void fallsBackToLegacyLyricsWhenExtensionUnsupported() {
        // Simulate a legacy server without the OpenSubsonic structured-lyrics extension.
        legacyLyricsOnly = true;
        var song = new Song("128-uuid", "Jóga", "Björk", "", "", "", 200, 1, false);
        Lyrics lyrics = client.lyrics(song).join();
        assertFalse(lyrics.synced());
        assertEquals(List.of("hello", "world"), lyrics.lines().stream().map(Lyrics.Line::text).toList());
        assertTrue(called().contains("getLyrics"));
    }
}
