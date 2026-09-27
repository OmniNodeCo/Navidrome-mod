package dev.omninode.navidrome.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded, on-demand cover art; decode on a worker, upload/release on the client thread. */
public final class CoverArtCache {
    private static final int MAX_ENTRIES = 24;
    private record Art(Identifier location, int size) {}
    private final Map<String, Art> images = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<String> pending = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private int generation;
    private int sequence;

    /** May start a download; returns null while art is loading or unavailable. Client thread only. */
    public Identifier get(String id, ClientApp app) {
        if (id == null || id.isBlank() || !app.connected()) return null;
        Art art = images.get(id);
        if (art != null) return art.location();
        if (!failed.contains(id) && pending.add(id)) {
            int expected = generation;
            app.api().coverArt(id).thenApply(CoverArtCache::decode).whenComplete((image, error) ->
                    Minecraft.getInstance().execute(() -> {
                        pending.remove(id);
                        if (expected != generation || !app.connected() || image == null || error != null) {
                            if (image != null) image.close();
                            if (expected == generation) failed.add(id);
                            return;
                        }
                        Identifier location = Identifier.fromNamespaceAndPath("navidrome", "cover_" + sequence++);
                        try {
                            Minecraft.getInstance().getTextureManager().register(location,
                                    new DynamicTexture(location::toString, image));
                            images.put(id, new Art(location, 96));
                            while (images.size() > MAX_ENTRIES) {
                                String oldest = images.keySet().iterator().next();
                                Minecraft.getInstance().getTextureManager().release(images.remove(oldest).location());
                            }
                        } catch (RuntimeException ex) {
                            image.close();
                            failed.add(id);
                        }
                    }));
        }
        return null;
    }

    public void clear() {
        generation++;
        for (Art art : images.values()) Minecraft.getInstance().getTextureManager().release(art.location());
        images.clear();
        pending.clear();
        failed.clear();
    }

    /** Rejects decompression bombs before ImageIO allocates their full pixel array. */
    private static NativeImage decode(byte[] bytes) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            BufferedImage input;
            try {
                reader.setInput(stream);
                if (reader.getWidth(0) > 2048 || reader.getHeight(0) > 2048) return null;
                input = reader.read(0);
            } finally { reader.dispose(); }
            if (input == null) return null;
            BufferedImage scaled = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            try {
                g.setColor(new Color(25, 25, 31));
                g.fillRect(0, 0, 96, 96);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double ratio = Math.min(96.0 / input.getWidth(), 96.0 / input.getHeight());
                int w = Math.max(1, (int) (input.getWidth() * ratio));
                int h = Math.max(1, (int) (input.getHeight() * ratio));
                g.drawImage(input, (96 - w) / 2, (96 - h) / 2, w, h, null);
            } finally { g.dispose(); }
            NativeImage nativeImage = new NativeImage(96, 96, false);
            for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) {
                // NativeImage.setPixel expects ARGB in modern Minecraft, just like BufferedImage.getRGB.
                nativeImage.setPixel(x, y, scaled.getRGB(x, y));
            }
            return nativeImage;
        } catch (IOException | RuntimeException ignored) { return null; }
    }
}
