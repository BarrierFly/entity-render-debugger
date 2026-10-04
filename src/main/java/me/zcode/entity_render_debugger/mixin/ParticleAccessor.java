package me.zcode.entity_render_debugger.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link Particle} position fields are protected; reports need them read-only.
 */
@Mixin(Particle.class)
public interface ParticleAccessor {
	@Accessor("x")
	double erd$getX();

	@Accessor("y")
	double erd$getY();

	@Accessor("z")
	double erd$getZ();
}
