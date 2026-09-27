package dev.omninode.navidrome.client;

import dev.omninode.navidrome.api.Models.Song;
import dev.omninode.navidrome.api.SubsonicClient;
import fr.delthas.javamp3.Sound;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** MP3 -> streaming PCM -> desktop audio device. Never decodes or performs I/O on the game thread. */
public final class MusicPlayer implements AutoCloseable {
    public enum State { STOPPED, BUFFERING, PLAYING, PAUSED }
    public enum Repeat { OFF, ALL, ONE }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "navidrome-audio");
        t.setDaemon(true);
        return t;
    });
    private final List<Song> queue = new ArrayList<>();
    private final Random random = new Random();
    private final Consumer<String> errorSink;
    private SubsonicClient client;
    private Future<?> task;
    private volatile InputStream activeStream;
    private volatile SourceDataLine activeLine;
    private volatile State state = State.STOPPED;
    private volatile int position;
    private volatile int bitrate = 192;
    private volatile float volume = 0.65f;
    private int index = -1;
    private volatile long generation;
    private boolean paused;
    private boolean shuffle;
    private Repeat repeat = Repeat.OFF;

    public MusicPlayer(Consumer<String> errorSink) { this.errorSink = errorSink; }

    public synchronized void setClient(SubsonicClient client) { stop(); this.client = client; queue.clear(); index = -1; }
    public synchronized List<Song> queue() { return List.copyOf(queue); }
    public synchronized int index() { return index; }
    public synchronized Song current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }
    public State state() { return state; }
    public int position() { return position; }
    public int bitrate() { return bitrate; }
    public float volume() { return volume; }
    public synchronized boolean shuffle() { return shuffle; }
    public synchronized Repeat repeat() { return repeat; }

    public void setBitrate(int kbps) {
        if (!List.of(96, 128, 192, 256, 320).contains(kbps)) throw new IllegalArgumentException("Invalid bitrate");
        bitrate = kbps; // affects next stream, not the one already open
    }
    public void setVolume(float level) { volume = Math.clamp(level, 0f, 1f); }
    public synchronized void toggleShuffle() { shuffle = !shuffle; }
    public synchronized void cycleRepeat() { repeat = Repeat.values()[(repeat.ordinal() + 1) % Repeat.values().length]; }

    public synchronized void play(List<Song> songs, int selected) {
        if (client == null || songs.isEmpty() || selected < 0 || selected >= songs.size()) return;
        queue.clear();
        queue.addAll(songs);
        index = selected;
        start(0, true);
    }

    public synchronized void enqueue(Song song) {
        if (client == null || song == null || song.id().isEmpty()) return;
        queue.add(song);
        if (index < 0) { index = 0; start(0, true); }
    }

    public synchronized void playQueueIndex(int selected) {
        if (client == null || selected < 0 || selected >= queue.size()) return;
        index = selected;
        start(0, true);
    }

    public synchronized void clearQueue() {
        stop();
        queue.clear();
        index = -1;
    }

    public synchronized void removeFromQueue(int selected) {
        if (selected < 0 || selected >= queue.size()) return;
        if (selected == index) {
            boolean wasPlaying = state != State.STOPPED;
            stop();
            queue.remove(selected);
            index = queue.isEmpty() ? -1 : Math.min(selected, queue.size() - 1);
            if (wasPlaying && index >= 0) start(0, false);
        } else {
            queue.remove(selected);
            if (selected < index) index--;
        }
    }

    public synchronized void moveUp(int selected) {
        if (selected <= 0 || selected >= queue.size()) return;
        Collections.swap(queue, selected, selected - 1);
        if (index == selected) index--;
        else if (index == selected - 1) index++;
    }

    public synchronized void setStarred(String songId, boolean starred) {
        for (int i = 0; i < queue.size(); i++) {
            Song s = queue.get(i);
            if (s.id().equals(songId)) queue.set(i, new Song(s.id(), s.title(), s.artist(),
                    s.album(), s.albumId(), s.coverArt(), s.duration(), s.track(), starred));
        }
    }

    public synchronized void togglePause() {
        if (state == State.STOPPED) {
            if (current() != null) start(0, true);
            return;
        }
        paused = !paused;
        state = paused ? State.PAUSED : State.PLAYING;
        SourceDataLine line = activeLine;
        if (line != null) {
            if (paused) line.stop(); else line.start();
        }
        notifyAll();
    }

    public synchronized void seek(int seconds) {
        Song song = current();
        if (song == null) return;
        start(Math.clamp(seconds, 0, Math.max(0, song.duration() - 1)), true);
    }

    public synchronized void next() {
        if (queue.isEmpty()) return;
        int next = nextIndex(false);
        if (next >= 0) { index = next; start(0, true); }
    }

    public synchronized void previous() {
        if (queue.isEmpty()) return;
        if (position > 3) seek(0);
        else { index = (index - 1 + queue.size()) % queue.size(); start(0, true); }
    }

    public synchronized void stop() {
        generation++;
        paused = false;
        state = State.STOPPED;
        position = 0;
        if (task != null) task.cancel(true);
        InputStream in = activeStream;
        activeStream = null;
        if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        SourceDataLine line = activeLine;
        activeLine = null;
        if (line != null) { line.stop(); line.flush(); line.close(); }
        notifyAll();
    }

    private int nextIndex(boolean automatic) {
        if (queue.isEmpty()) return -1;
        if (automatic && repeat == Repeat.ONE) return index;
        if (shuffle && queue.size() > 1) return (index + 1 + random.nextInt(queue.size() - 1)) % queue.size();
        if (index + 1 < queue.size()) return index + 1;
        return automatic && repeat == Repeat.OFF ? -1 : 0;
    }

    /** Called with lock. When a worker queues its successor it must not cancel itself. */
    private void start(int offset, boolean cancelPrevious) {
        if (cancelPrevious) stop();
        if (client == null || current() == null) return;
        long token = ++generation;
        Song song = current();
        SubsonicClient server = client;
        position = offset;
        paused = false;
        state = State.BUFFERING;
        task = worker.submit(() -> decodeAndPlay(server, song, offset, token));
    }

    private void decodeAndPlay(SubsonicClient server, Song song, int offset, long token) {
        SourceDataLine line = null;
        boolean completed = false;
        boolean submitted = false;
        int playedSeconds = 0;
        long startedAt;
        // The Subsonic timeOffset parameter is not guaranteed for music or untranscoded MP3s.
        // Decode and discard up to the target instead, so seeking behaves the same on older servers.
        try (InputStream remote = server.stream(song.id(), bitrate, 0); Sound sound = new Sound(remote)) {
            synchronized (this) {
                if (token != generation) return;
                activeStream = remote;
            }
            int bytesPerSecond = (int) sound.getAudioFormat().getFrameRate() * sound.getAudioFormat().getFrameSize();
            long toDiscard = (long) offset * bytesPerSecond;
            byte[] skip = new byte[8192];
            while (toDiscard > 0 && token == generation && !Thread.currentThread().isInterrupted()) {
                int read = sound.read(skip, 0, (int) Math.min(toDiscard, skip.length));
                if (read <= 0) return;
                toDiscard -= read;
            }
            if (token != generation || Thread.currentThread().isInterrupted()) return;
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, sound.getAudioFormat());
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(sound.getAudioFormat(), 32768);
            synchronized (this) {
                if (token != generation) return;
                activeLine = line;
                state = paused ? State.PAUSED : State.PLAYING;
                if (!paused) line.start();
            }
            startedAt = System.currentTimeMillis();
            server.scrobble(song.id(), startedAt, false).exceptionally(error -> null);
            byte[] pcm = new byte[8192];
            long bytesWritten = 0;
            while (token == generation && !Thread.currentThread().isInterrupted()) {
                synchronized (this) { while (paused && token == generation) wait(); }
                if (token != generation) break;
                int len = sound.read(pcm);
                if (len < 0) { completed = true; break; }
                // JavaSound doesn't always support MASTER_GAIN; scale 16-bit little-endian PCM ourselves.
                float gain = volume;
                if (gain < 0.999f) {
                    for (int i = 0; i + 1 < len; i += 2) {
                        int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
                        int adjusted = Math.round(sample * gain);
                        pcm[i] = (byte) adjusted;
                        pcm[i + 1] = (byte) (adjusted >> 8);
                    }
                }
                for (int written = 0; written < len && token == generation; ) {
                    written += line.write(pcm, written, len - written);
                }
                bytesWritten += len;
                playedSeconds = bytesPerSecond == 0 ? 0 : (int) (bytesWritten / bytesPerSecond);
                position = offset + playedSeconds;
                int threshold = song.duration() <= 0 ? 30 : Math.min(240, Math.max(1, song.duration() / 2));
                if (!submitted && playedSeconds >= threshold) {
                    server.scrobble(song.id(), startedAt, true).exceptionally(error -> null);
                    submitted = true;
                }
            }
            if (completed && token == generation) line.drain();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) {
            if (token == generation) errorSink.accept("Playback failed: " + e.getClass().getSimpleName()
                    + (e instanceof javax.sound.sampled.LineUnavailableException ? " (no audio device)" : ""));
        } finally {
            if (line != null) { line.stop(); line.flush(); line.close(); }
            synchronized (this) {
                if (token == generation) {
                    activeStream = null;
                    activeLine = null;
                    state = State.STOPPED;
                    if (completed) {
                        int next = nextIndex(true);
                        if (next >= 0) { index = next; start(0, false); }
                    }
                }
            }
        }
    }

    @Override public synchronized void close() { stop(); worker.shutdownNow(); }
}
