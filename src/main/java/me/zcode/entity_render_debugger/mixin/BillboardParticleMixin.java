package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.texture.Sprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A billboard particle without a sprite (never reloaded, resource pack removed it,
 * or a buggy factory) crashes with an NPE on the first UV access. The sprite is
 * swapped for the atlas "missingno" sprite before vanilla reads it.
 */
@Mixin(BillboardParticle.class)
public abstract class BillboardParticleMixin {
	@Shadow
	protected Sprite sprite;

	@Inject(method = {"getMinU", "getMaxU", "getMinV", "getMaxV"}, at = @At("HEAD"))
	private void erd$ensureSprite(CallbackInfoReturnable<Float> cir) {
		if (this.sprite == null) {
			this.sprite = IncidentCenter.missingSpriteOrNull();
			IncidentCenter.onNullSprite((BillboardParticle) (Object) this);
		}
	}

	@Inject(method = "setSprite", at = @At("HEAD"), cancellable = true)
	private void erd$fixNullSetSprite(Sprite sprite, CallbackInfo ci) {
		if (sprite == null) {
			this.sprite = IncidentCenter.missingSpriteOrNull();
			IncidentCenter.onNullSprite((BillboardParticle) (Object) this);
			ci.cancel();
		}
	}
}
