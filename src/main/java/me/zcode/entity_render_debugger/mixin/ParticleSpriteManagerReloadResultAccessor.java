package me.zcode.entity_render_debugger.mixin;

import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Optional;

/**
 * Read access to one loaded {@code particles/<id>.json} definition
 * ({@code ParticleSpriteManager$ReloadResult}) for the reload audit: the sprite set id and its
 * referenced texture ids.
 */
@Mixin(targets = "net.minecraft.client.particle.ParticleSpriteManager$ReloadResult")
public interface ParticleSpriteManagerReloadResultAccessor {
	@Accessor("id")
	Identifier erd$getId();

	@Accessor("sprites")
	Optional<List<Identifier>> erd$getSprites();
}
