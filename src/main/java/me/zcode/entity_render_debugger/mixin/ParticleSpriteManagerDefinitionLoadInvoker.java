package me.zcode.entity_render_debugger.mixin;

import net.minecraft.client.particle.ParticleSpriteManager;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.Optional;

/**
 * Exposes the private {@code ParticleSpriteManager#load(Identifier, Resource)} for
 * {@link ParticleSpriteManagerMixin}: the guarded per-definition call site has to run the original
 * parse logic while wrapping it in a catch-all, which is impossible from a plain {@code @Shadow}.
 */
@Mixin(ParticleSpriteManager.class)
public interface ParticleSpriteManagerDefinitionLoadInvoker {
	@Invoker("load")
	Optional<List<Identifier>> erd$load(Identifier id, Resource resource);
}
