package fr.orbitalstrike.client.mixin;

import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.client.fx.SoundDirector;
import fr.orbitalstrike.client.fx.SourceHandle;
import net.minecraft.client.sound.Channel;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Muffles all game sounds (except mod sounds) during the pre-impact hush. */
@Mixin(SoundSystem.class)
public abstract class SoundSystemMixin {
    @Shadow @Final private Map<SoundInstance, Channel.SourceManager> sources;

    @Inject(method = "getAdjustedVolume(Lnet/minecraft/client/sound/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
    private void orbitalStrike$silence(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        float m = SoundDirector.worldVolumeMultiplier();
        if (m >= 0.999f) return;
        if (OrbitalStrike.MOD_ID.equals(sound.getId().getNamespace())) return;
        cir.setReturnValue(cir.getReturnValueF() * m);
    }

    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V", at = @At("TAIL"))
    private void orbitalStrike$exemptModSound(SoundInstance sound, CallbackInfo ci) {
        if (sound != null && OrbitalStrike.MOD_ID.equals(sound.getId().getNamespace())) {
            Channel.SourceManager manager = this.sources.get(sound);
            if (manager != null) {
                manager.run(source -> ((SourceHandle) source).orbitalStrike$exempt());
            }
        }
    }
}
