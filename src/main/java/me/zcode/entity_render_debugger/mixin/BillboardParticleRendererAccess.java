package me.zcode.entity_render_debugger.mixin;

import net.minecraft.client.particle.BillboardParticleRenderer;
import net.minecraft.client.particle.ParticleTextureSheet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BillboardParticleRenderer.class)
public interface BillboardParticleRendererAccess {
	@Accessor("textureSheet")
	ParticleTextureSheet erd$getTextureSheet();
}
