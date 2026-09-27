# Navidrome for Minecraft (client mod)

An in-game **client for an existing Navidrome / OpenSubsonic server**. The music library, users, indexing, playlists and transcoding remain on your Navidrome installation; this mod does not run a music server inside Minecraft and does not need to be installed on the Minecraft server.

> **Status: source preview, not a tested release.** Four targets are configured, but there is no verified downloadable JAR yet. The sandbox used to write this code has no Java installation and cannot reach the Gradle/Maven download hosts, so the builds and game launch could **not** be run here. Please build and test on a machine with network access before installing; please don't distribute these sources as verified binaries.

## Target matrix

| Minecraft | Loader | Gradle project | Intended JAR name |
|---|---|---|---|
| 26.2 | Fabric | `:fabric-26.2` | `navidrome-fabric-26.2-0.1.0.jar` |
| 26.3 | Fabric | `:fabric-26.3` | `navidrome-fabric-26.3-0.1.0.jar` |
| 26.2 | NeoForge | `:neoforge-26.2` | `navidrome-neoforge-26.2-0.1.0.jar` |
| 26.3 | NeoForge | `:neoforge-26.3` | `navidrome-neoforge-26.3-0.1.0.jar` |

The source uses Java 25. Each build compiles against its exact Minecraft version; 26.2 and 26.3 have separate keybinding entrypoints because 26.3 switched from GLFW key symbols to SDL keyboard codes. Do not use a 26.2 JAR on 26.3 or vice versa. Fabric needs Fabric Loader 0.19.5+ and its matching Fabric API; NeoForge needs the version in that target's `gradle.properties` or newer **for the same Minecraft version**.

## Building

Install a JDK 25 and use the included Gradle wrapper (9.7.1). From the repository root:

```sh
./gradlew --no-daemon :protocol-tests:test
./gradlew --no-daemon build
# or build only one target:
./gradlew --no-daemon :fabric-26.3:build
```

On Windows, use `gradlew.bat`. Select a mod JAR from `<target>/build/libs/`, not a `-sources` or `-dev` JAR. **Only install the JAR corresponding to your loader and exact game version.** The Fabric build nests the MP3 decoder using Loom `include`; the NeoForge build uses Jar-in-Jar.

### GitHub Actions and releases

- [build.yml](.github/workflows/build.yml) runs the protocol/preference tests and builds the four exact loader/version combinations on branch pushes, pull requests and manual runs. It uploads **only the installable JARs** as separate artifacts; a failed test blocks all builds.
- [release.yml](.github/workflows/release.yml) reruns those tests and builds for a `v0.1.0` tag (or a manual run with a tag matching the version in `build.gradle`), checks that all four JARs exist, writes `SHA256SUMS`, and creates a **prerelease** with those five assets. It cannot publish when any test or target build fails. Manual dispatch may require the workflow to be on the repository's default branch first; publishing also requires GitHub Actions `contents: write` permission.

The CI checks compilation and protocol behavior, **not** an in-game launch with a real Navidrome server. Test the actual client before treating a prerelease as stable.

## Connecting and controls

1. Install the target-specific JAR as a client mod. Install Fabric API too when using Fabric.
2. In Minecraft, press **N** (rebind in Controls → Navidrome Music).
3. Enter the full URL of your own Navidrome server (including any reverse-proxy path), your username and password, and click **Connect**. HTTPS is required for non-loopback URLs by default. Only opt into HTTP for a trusted LAN server.
4. Navigate with the six tabs. Browse libraries, sort albums, open artists/albums/playlists, search, manage starred items, and view or reorder the playback queue. **Mix** plays random songs from the selected library. Use `Q` to queue a track, `L` to add it to a playlist, `+`/`*` to star/unstar, and the player's compact controls for previous/next, ±10-second seek, play/pause, stop, shuffle, repeat, volume, 0–5 rating and lyrics. A track in a playlist has a `-` remove button; a queued track has `-` (remove) and `^` (move up).
5. The Settings page offers the Navidrome web UI link, a bitrate selector, scan requests (when allowed by your account), account/scan info and Disconnect.

**Server prerequisite:** configure Navidrome and its FFmpeg transcoder to provide MP3 streams. This mod decodes Navidrome's MP3 stream and plays it through the operating system's JavaSound audio device. No audio device, missing transcoding or an unstable connection can prevent playback. Seeking decodes from the start to the requested position for compatibility with servers that ignore Subsonic's optional music `timeOffset`; it can be slow on long tracks or slow connections. Scrobbling sends a “now playing” update and a play submission after sufficient playback.

### Authentication and privacy

The password or API key is *not saved*. Only the server URL, optional username, bitrate and volume are kept in `config/navidrome-client.properties` (the file is Git-ignored). Password login uses salted Subsonic token authentication, and commands are sent as POST requests. Streaming and cover art require authenticated GET URLs as defined by Subsonic; these URLs can be logged by your server/reverse proxy, so use HTTPS and protect access logs. Redirects are disabled to avoid sending credentials to another host. Disconnecting or closing Minecraft discards the in-memory credentials.

A newer server implementing the OpenSubsonic `apiKeyAuthentication` extension can instead use the **API key** toggle. This sends `apiKey` **without** a conflicting username parameter. Older Navidrome installations may not support API keys yet. If you want your profile displayed on the Settings page in API-key mode, fill in the username field too (the username is not sent for authentication).

## Feature boundaries

Implemented in source: password/API-key connection; music folders; artists/albums/tracks; server-side paginated album browsing and multi-type search; random mixes; favorite artists/albums/tracks; play/seek/queue/shuffle/repeat/volume; cover artwork; scrobbling; star/unstar; ratings; lyrics (structured and legacy fallback); playlist create/rename/delete/add/remove; scan requests and status.

**Not full Navidrome feature parity:** There is no in-game user administration, transcoding-rule editor, share manager, offline download/sync, podcast or internet-radio browser, smart playlist editor, or complete server configuration. Those remain in the existing Navidrome web UI (Settings → Open server UI). Don't treat the web-UI link as a claim that these operations have an in-game implementation. Additional parity work and actual cross-loader game testing are required before promising a complete replacement for Navidrome's UI.

## Source layout

- `src/shared/java/dev/omninode/navidrome/api`: OpenSubsonic HTTP client and models, with no Minecraft dependency.
- `src/shared/java/dev/omninode/navidrome/client`: shared screen, audio playback, artwork and local preferences.
- `fabric-26.{2,3}` and `neoforge-26.{2,3}`: loader/version-specific metadata, dependencies and entrypoints.
- `protocol-tests`: loopback mock server tests for authentication, browsing, playlists, media, lyrics and error handling, plus preference-file tests.

MIT licensed; see [LICENSE](LICENSE). Audio decoding uses [JavaMP3](https://github.com/delthas/JavaMP3) by delthas (also MIT), bundled in each mod JAR.
