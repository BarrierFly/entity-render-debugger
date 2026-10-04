package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla rethrows any failure of a particle tick as a {@code CrashException}
 * ("Ticking Particle"). Reimplemented to skip the offending particle instead.
 */
@Mixin(ParticleRenderer.class)
public abstract class ParticleRendererMixin {
	@Inject(method = "tickParticle", at = @At("HEAD"), cancellable = true)
	private void erd$tickParticle(Particle particle, CallbackInfo ci) {
		try {
			particle.tick();
		} catch (Throwable throwable) {
			particle.markDead();
			IncidentCenter.onTickFailure(particle, throwable);
		}
		ci.cancel();
	}
}
