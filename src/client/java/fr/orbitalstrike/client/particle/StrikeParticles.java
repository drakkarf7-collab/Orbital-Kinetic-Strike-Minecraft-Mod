package fr.orbitalstrike.client.particle;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;

/**
 * The three mod particles:
 * ember (slowly rises, emissive), ash (slowly falls), debris (ballistic).
 */
public final class StrikeParticles {
    private StrikeParticles() {}

    public static final class Ember extends SpriteBillboardParticle {
        Ember(ClientWorld world, double x, double y, double z, double vx, double vy, double vz, SpriteProvider sprites) {
            super(world, x, y, z, vx, vy, vz);
            this.velocityX = vx;
            this.velocityY = vy;
            this.velocityZ = vz;
            this.gravityStrength = -0.02f;
            this.velocityMultiplier = 0.94f;
            this.maxAge = 30 + random.nextInt(40);
            this.scale = 0.08f + random.nextFloat() * 0.08f;
            this.setColor(0.28f + random.nextFloat() * 0.35f, 0.72f + random.nextFloat() * 0.25f, 1.0f);
            this.setSprite(sprites);
        }

        @Override
        public ParticleTextureSheet getType() {
            return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
        }

        @Override
        public int getBrightness(float tint) {
            return 0xF000F0;
        }

        @Override
        public void tick() {
            super.tick();
            this.alpha = Math.max(0f, 1f - age / (float) maxAge);
        }
    }

    public static final class Ash extends SpriteBillboardParticle {
        Ash(ClientWorld world, double x, double y, double z, double vx, double vy, double vz, SpriteProvider sprites) {
            super(world, x, y, z, vx, vy, vz);
            this.velocityX = vx;
            this.velocityY = vy;
            this.velocityZ = vz;
            this.gravityStrength = 0.015f;
            this.velocityMultiplier = 0.96f;
            this.maxAge = 80 + random.nextInt(80);
            this.scale = 0.15f + random.nextFloat() * 0.2f;
            float g = 0.2f + random.nextFloat() * 0.2f;
            this.setColor(g, g, g);
            this.setSprite(sprites);
        }

        @Override
        public ParticleTextureSheet getType() {
            return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
        }

        @Override
        public void tick() {
            super.tick();
            this.alpha = Math.min(1f, (maxAge - age) / 30f);
        }
    }

    public static final class Debris extends SpriteBillboardParticle {
        Debris(ClientWorld world, double x, double y, double z, double vx, double vy, double vz, SpriteProvider sprites) {
            super(world, x, y, z, vx, vy, vz);
            this.velocityX = vx;
            this.velocityY = vy;
            this.velocityZ = vz;
            this.gravityStrength = 0.9f;
            this.velocityMultiplier = 0.98f;
            this.collidesWithWorld = true;
            this.maxAge = 40 + random.nextInt(40);
            this.scale = 0.12f + random.nextFloat() * 0.15f;
            float g = 0.3f + random.nextFloat() * 0.25f;
            this.setColor(g, g * 0.95f, g * 0.9f);
            this.setSprite(sprites);
        }

        @Override
        public ParticleTextureSheet getType() {
            return ParticleTextureSheet.PARTICLE_SHEET_OPAQUE;
        }
    }

    public record EmberFactory(SpriteProvider sprites) implements ParticleFactory<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientWorld world, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new Ember(world, x, y, z, vx, vy, vz, sprites);
        }
    }

    public record AshFactory(SpriteProvider sprites) implements ParticleFactory<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientWorld world, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new Ash(world, x, y, z, vx, vy, vz, sprites);
        }
    }

    public record DebrisFactory(SpriteProvider sprites) implements ParticleFactory<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientWorld world, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new Debris(world, x, y, z, vx, vy, vz, sprites);
        }
    }
}
