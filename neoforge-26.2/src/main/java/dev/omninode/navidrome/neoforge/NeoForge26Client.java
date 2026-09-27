package dev.omninode.navidrome.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import dev.omninode.navidrome.client.ClientApp;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only; the mod is not required on the Minecraft server. */
@Mod(value = "navidrome", dist = Dist.CLIENT)
public final class NeoForge26Client {
    private KeyMapping key;
    private final KeyMapping.Category category = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath("navidrome", "music"));

    public NeoForge26Client(IEventBus modBus) {
        ClientApp.initialize(FMLPaths.CONFIGDIR.get());
        modBus.addListener(this::registerKeys);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(category);
        key = new KeyMapping("key.navidrome.open", InputConstants.Type.KEYSYM, InputConstants.KEY_N, category);
        event.register(key);
    }

    private void tick(ClientTickEvent.Post event) {
        if (key != null) while (key.consumeClick()) {
            if (Minecraft.getInstance().gui.screen() == null) ClientApp.get().open();
        }
    }
}
