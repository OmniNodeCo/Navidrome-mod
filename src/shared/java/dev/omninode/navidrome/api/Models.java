package dev.omninode.navidrome.api;

import java.util.List;

/** OpenSubsonic identifiers are strings (Navidrome uses UUIDs and hashes). */
public final class Models {
    private Models() {}

    public record Song(String id, String title, String artist, String album, String albumId,
                       String coverArt, int duration, int track, boolean starred) {
        @Override public String toString() { return title + " — " + artist; }
    }

    public record Album(String id, String name, String artist, String coverArt,
                        int songCount, boolean starred) {
        @Override public String toString() { return name + " — " + artist; }
    }

    public record Artist(String id, String name, String coverArt, int albumCount,
                         boolean starred) {
        @Override public String toString() { return name; }
    }

    public record Folder(String id, String name) {}
    public record Playlist(String id, String name, int songCount, boolean owned,
                           List<Song> songs) {
        public Playlist { songs = List.copyOf(songs); }
        @Override public String toString() { return name + " (" + songCount + ")"; }
    }

    public record Search(List<Artist> artists, List<Album> albums, List<Song> songs) {
        public Search { artists = List.copyOf(artists); albums = List.copyOf(albums); songs = List.copyOf(songs); }
    }

    public record Starred(List<Artist> artists, List<Album> albums, List<Song> songs) {
        public Starred { artists = List.copyOf(artists); albums = List.copyOf(albums); songs = List.copyOf(songs); }
    }

    public record Lyrics(boolean synced, List<Line> lines) {
        public Lyrics { lines = List.copyOf(lines); }
        public record Line(long startMs, String text) {}
    }

    public record User(String username, boolean admin, boolean download, boolean share) {}
    public record Scan(boolean scanning, int count, String lastScan) {}
}
