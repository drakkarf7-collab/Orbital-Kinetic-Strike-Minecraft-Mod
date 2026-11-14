package fr.orbitalstrike.client.mixin;

import fr.orbitalstrike.client.fx.PostEffects;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies mod post-processing effects right after world rendering, before the HUD. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/WorldRenderer;drawEntityOutlinesFramebuffer()V", shift = At.Shift.AFTER))
    private void orbitalStrike$postEffects(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        PostEffects.render(tickCounter.getLastFrameDuration());
    }
}
