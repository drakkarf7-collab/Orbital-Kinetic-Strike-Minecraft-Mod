package fr.orbitalstrike.client.mixin;

import fr.orbitalstrike.client.fx.LowPassFilter;
import fr.orbitalstrike.client.fx.SourceHandle;
import net.minecraft.client.sound.Source;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks the game's OpenAL sound sources and lets mod sounds be exempted. */
@Mixin(Source.class)
public abstract class SourceMixin implements SourceHandle {
    @Shadow @Final private int pointer;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void orbitalStrike$track(int pointer, CallbackInfo ci) {
        LowPassFilter.track(pointer);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void orbitalStrike$untrack(CallbackInfo ci) {
        LowPassFilter.untrack(this.pointer);
    }

    @Override
    public void orbitalStrike$exempt() {
        LowPassFilter.exempt(this.pointer);
    }
}
