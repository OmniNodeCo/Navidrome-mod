package dev.omninode.navidrome.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.omninode.navidrome.client.ClientApp;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Fabric / Minecraft 26.2: GLFW-era key symbol mapping. */
public final class Fabric26Client implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientApp.initialize(FabricLoader.getInstance().getConfigDir());
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("navidrome", "music"));
        KeyMapping key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.navidrome.open", InputConstants.Type.KEYSYM, InputConstants.KEY_N, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (key.consumeClick()) if (client.gui.screen() == null) ClientApp.get().open();
        });
    }
}
