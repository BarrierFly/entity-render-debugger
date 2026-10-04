package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.random.Random;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * {@code ParticleSpriteManager$SimpleSpriteProvider#sprites} stays null when the
 * particle's texture list json (assets/&lt;ns&gt;/particles/&lt;id&gt;.json) is missing or failed
 * to load, and every factory call then dies with an NPE. All getters fall back to
 * the atlas "missingno" sprite instead.
 */
@Mixin(targets = "net.minecraft.client.particle.ParticleSpriteManager$SimpleSpriteProvider")
public abstract class ParticleSpriteManagerSimpleSpriteProviderMixin {
	@Shadow
	private @Nullable List<Sprite> sprites;

	@Inject(method = "getSprite(II)Lnet/minecraft/client/texture/Sprite;", at = @At("HEAD"), cancellable = true)
	private void erd$guardGetSprite(int age, int maxAge, CallbackInfoReturnable<Sprite> cir) {
		if (this.sprites == null || this.sprites.isEmpty()) {
			IncidentCenter.onSpriteSetMissing();
			Sprite missing = IncidentCenter.missingSpriteOrNull();
			if (missing != null) {
				cir.setReturnValue(missing);
			}
			return;
		}
		if (maxAge <= 0) {
			IncidentCenter.onSpriteSetBadAge(maxAge);
			cir.setReturnValue(this.sprites.getFirst());
		}
	}

	@Inject(method = "getSprite(Lnet/minecraft/util/math/random/Random;)Lnet/minecraft/client/texture/Sprite;", at = @At("HEAD"), cancellable = true)
	private void erd$guardGetRandomSprite(Random random, CallbackInfoReturnable<Sprite> cir) {
		if (this.sprites == null || this.sprites.isEmpty()) {
			IncidentCenter.onSpriteSetMissing();
			Sprite missing = IncidentCenter.missingSpriteOrNull();
			if (missing != null) {
				cir.setReturnValue(missing);
			}
		}
	}

	@Inject(method = "getFirst()Lnet/minecraft/client/texture/Sprite;", at = @At("HEAD"), cancellable = true)
	private void erd$guardGetFirst(CallbackInfoReturnable<Sprite> cir) {
		if (this.sprites == null || this.sprites.isEmpty()) {
			IncidentCenter.onSpriteSetMissing();
			Sprite missing = IncidentCenter.missingSpriteOrNull();
			if (missing != null) {
				cir.setReturnValue(missing);
			}
		}
	}
}
