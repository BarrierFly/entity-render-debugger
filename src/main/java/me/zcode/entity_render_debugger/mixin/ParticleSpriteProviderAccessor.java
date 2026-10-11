package me.zcode.entity_render_debugger.mixin;

import net.minecraft.client.texture.Sprite;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Read access to the sprite list of a {@code ParticleSpriteManager$SimpleSpriteProvider} for the
 * reload audit: a provider whose sprites are still null after the bind stage has no
 * {@code particles/<id>.json} in any resource pack.
 */
@Mixin(targets = "net.minecraft.client.particle.ParticleSpriteManager$SimpleSpriteProvider")
public interface ParticleSpriteProviderAccessor {
	@Accessor("sprites")
	@Nullable List<Sprite> erd$getSprites();
}
