package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleRenderer;
import net.minecraft.client.particle.ParticleSpriteManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.Submittable;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.random.Random;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Particle creation and batch building guards (particle-render-monitor).
 */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
	@Shadow
	protected @Nullable ClientWorld world;

	@Shadow
	@Final
	private ParticleSpriteManager spriteManager;

	@Shadow
	@Final
	private Random random;

	/**
	 * Reimplemented from vanilla: the factory call is now guarded, and a failing factory
	 * ("Unable to create particle" style crashes) just skips the particle.
	 */
	@Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
	private <T extends ParticleEffect> void erd$createParticle(
			T parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ,
			CallbackInfoReturnable<Particle> cir
	) {
		Particle particle = null;
		try {
			@SuppressWarnings("unchecked")
			ParticleFactory<T> factory = (ParticleFactory<T>) this.spriteManager.getParticleFactories()
					.get(Registries.PARTICLE_TYPE.getRawId(parameters.getType()));
			if (this.world != null && factory != null) {
				particle = factory.createParticle(parameters, this.world, x, y, z, velocityX, velocityY, velocityZ, this.random);
				if (particle != null) {
					IncidentCenter.recordType(particle, Registries.PARTICLE_TYPE.getId(parameters.getType()));
				}
			}
		} catch (Throwable throwable) {
			particle = null;
			IncidentCenter.onCreateFailure(parameters, throwable);
		}
		cir.setReturnValue(particle);
	}

	/**
	 * Final safety net around every sheet renderer (also covers the item pickup and
	 * elder guardian renderers): a failing renderer is replaced by an empty batch
	 * instead of crashing the frame.
	 */
	@Redirect(
			method = "addToBatch",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/particle/ParticleRenderer;render(Lnet/minecraft/client/render/Frustum;Lnet/minecraft/client/render/Camera;F)Lnet/minecraft/client/render/Submittable;"
			)
	)
	private Submittable erd$guardRendererRender(ParticleRenderer<?> renderer, Frustum frustum, Camera camera, float tickProgress) {
		try {
			return renderer.render(frustum, camera, tickProgress);
		} catch (Throwable throwable) {
			IncidentCenter.onRendererFailure(renderer, throwable);
			return IncidentCenter.emptySubmittable();
		}
	}
}
