package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.BillboardParticleRenderer;
import net.minecraft.client.particle.BillboardParticleSubmittable;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla wraps every failure of a single particle's render into a
 * {@code CrashException} ("Rendering Particle") that closes the client.
 * The redirect skips only the offending particle and reports it to the chat instead.
 */
@Mixin(BillboardParticleRenderer.class)
public abstract class BillboardParticleRendererMixin {
	@Redirect(
			method = "render",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/particle/BillboardParticle;render(Lnet/minecraft/client/particle/BillboardParticleSubmittable;Lnet/minecraft/client/render/Camera;F)V"
			)
	)
	private void erd$guardParticleRender(BillboardParticle particle, BillboardParticleSubmittable submittable, Camera camera, float tickProgress) {
		try {
			particle.render(submittable, camera, tickProgress);
		} catch (Throwable throwable) {
			particle.markDead();
			IncidentCenter.onBillboardRenderFailure(particle, ((BillboardParticleRendererAccess) this).erd$getTextureSheet(), throwable);
		}
	}
}
