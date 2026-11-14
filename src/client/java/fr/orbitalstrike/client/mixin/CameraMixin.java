package fr.orbitalstrike.client.mixin;

import fr.orbitalstrike.client.cinematic.WorldCamera;
import fr.orbitalstrike.client.fx.CameraShake;
import fr.orbitalstrike.client.render.world.WorldFx;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Steers the camera toward the sky streak (for the launcher) and applies shake after standard camera calculation. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private float yaw;
    @Shadow private float pitch;

    @Shadow private boolean thirdPerson;

    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Shadow protected abstract void setPos(double x, double y, double z);

    @Inject(method = "update", at = @At("TAIL"))
    private void orbitalStrike$shake(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
                                     float tickDelta, CallbackInfo ci) {
        // On launch: cinematic camera detached from the player (arc, climb, liftoff).
        double[] cinema = WorldCamera.compute(tickDelta);
        if (cinema != null) {
            setPos(cinema[0], cinema[1], cinema[2]);
            setRotation((float) cinema[3], (float) cinema[4]);
            this.thirdPerson = true;
        }
        // Shot 10: launcher's camera turns toward the sky streak, then the impact.
        float[] look = cinema == null ? WorldFx.cameraLook(tickDelta, this.yaw, this.pitch) : null;
        if (look != null) setRotation(look[0], look[1]);
        if (!CameraShake.isActive()) return;
        float[] off = CameraShake.offset(tickDelta);
        setRotation(this.yaw + off[0], this.pitch + off[1]);
    }
}
