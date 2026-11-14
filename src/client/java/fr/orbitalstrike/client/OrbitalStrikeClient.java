package fr.orbitalstrike.client;

import fr.orbitalstrike.client.cinematic.CinematicDirector;
import fr.orbitalstrike.client.debug.CraterSyncTest;
import fr.orbitalstrike.client.debug.SelfTest;
import fr.orbitalstrike.client.fx.FxController;
import fr.orbitalstrike.client.fx.PostEffects;
import fr.orbitalstrike.client.cinematic.WorldCamera;
import fr.orbitalstrike.client.gui.DetonatorScreen;
import fr.orbitalstrike.client.particle.StrikeParticles;
import fr.orbitalstrike.client.render.world.WorldFx;
import fr.orbitalstrike.client.targeting.TargetingController;
import fr.orbitalstrike.network.Payloads;
import fr.orbitalstrike.registry.ModParticles;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** Client entry point: keybindings, networking, particles, HUD, effects. */
public final class OrbitalStrikeClient implements ClientModInitializer {
    public static KeyBinding menuKey;

    @Override
    public void onInitializeClient() {
        ClientConfig.load();

        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.orbital_strike.menu",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.category.orbital_strike"));

        ClientPlayNetworking.registerGlobalReceiver(Payloads.Phase.ID, (p, ctx) -> FxController.onPhase(p));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Impact.ID, (p, ctx) -> FxController.onImpact(p));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.SkyStreak.ID, (p, ctx) -> FxController.onSkyStreak(p));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Shockwave.ID, (p, ctx) -> FxController.onShockwave(p));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.CooldownSync.ID, (p, ctx) -> ClientState.onSync(p));

        net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlock(
                fr.orbitalstrike.registry.ModBlocks.IONIZED_CRYSTAL,
                net.minecraft.client.render.RenderLayer.getCutout());

        ParticleFactoryRegistry.getInstance().register(ModParticles.EMBER, StrikeParticles.EmberFactory::new);
        ParticleFactoryRegistry.getInstance().register(ModParticles.ASH, StrikeParticles.AshFactory::new);
        ParticleFactoryRegistry.getInstance().register(ModParticles.DEBRIS, StrikeParticles.DebrisFactory::new);

        ClientTickEvents.END_CLIENT_TICK.register(OrbitalStrikeClient::tick);

        // Right-click while holding the Detonator: strike the aimed target.
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            net.minecraft.item.ItemStack stack = player.getStackInHand(hand);
            if (world.isClient && stack.isOf(fr.orbitalstrike.registry.ModItems.ORBITAL_DETONATOR)) {
                TargetingController.fire(MinecraftClient.getInstance());
                return net.minecraft.util.TypedActionResult.success(stack);
            }
            return net.minecraft.util.TypedActionResult.pass(stack);
        });
        if (SelfTest.ENABLED) SelfTest.register();
        if (CraterSyncTest.ENABLED) CraterSyncTest.register();

        // World-space rendered effects: sky streak, impact column, dust ring.
        WorldRenderEvents.AFTER_TRANSLUCENT.register(WorldFx::render);

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            if (!SelfTest.ENABLED && MinecraftClient.getInstance().world != null) {
                CinematicDirector.warmUpIfNeeded();
            }
            float delta = tickCounter.getTickDelta(false);
            CinematicDirector.render(ctx, delta);
            FxController.renderHud(ctx, delta);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            CinematicDirector.stop();
            WorldCamera.stop();
            FxController.reset();
            ClientState.reset();
        });

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return Identifier.of("orbital_strike", "post_effects");
            }

            @Override
            public void reload(ResourceManager manager) {
                PostEffects.invalidate();
                CinematicDirector.invalidate3D();
            }
        });
    }

    private static void tick(MinecraftClient client) {
        if (!client.isPaused()) ClientState.tick();

        while (menuKey.wasPressed()) {
            if (client.player == null) continue;
            if (TargetingController.hasDetonator(client)) {
                client.setScreen(new DetonatorScreen());
            } else {
                client.player.sendMessage(Text.translatable("message.orbital_strike.no_item"), true);
            }
        }
        CinematicDirector.tick(client);
        WorldCamera.tick(client);
        TargetingController.tick(client);
        FxController.tick(client);
    }
}
