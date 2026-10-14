package me.zcode.entity_render_debugger.mixin;

import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Exposes the private {@code SpriteLoader#stitch} for {@link AtlasManagerEntryMixin}: the fallback
 * atlas is stitched by vanilla's own logic from a single 16x16 missingno image, producing a
 * genuine {@link SpriteLoader.StitchResult} that is uploaded and bound exactly like a regular one.
 */
@Mixin(SpriteLoader.class)
public interface SpriteLoaderStitchInvoker {
	@Invoker("stitch")
	SpriteLoader.StitchResult erd$stitch(List<SpriteContents> sprites, int mipLevel, Executor executor);
}
