package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteLoader;
import net.minecraft.resource.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Guards the stitch of the particle atlas during the resource reload.
 *
 * <p>Vanilla loads every atlas through {@code AtlasManager$Entry#load}. The returned future fails
 * when the stitcher cannot fit a sprite ({@code TextureStitcherCannotFitException} wrapped into
 * {@code CrashException "Stitching"}), which fails the whole reload and makes the client either
 * purge all resource packs or crash outright. For the particle atlas only, this guard attaches a
 * {@code handle} to the load future: on failure it stitches a 16x16 missingno-only fallback
 * {@code SpriteLoader.StitchResult} with vanilla's own {@code SpriteLoader#stitch} logic, so the
 * reload finishes and every particle sprite set falls back to the missingno sprite. All other
 * atlases are untouched, and the success path of the particle atlas is untouched as well.
 */
@Mixin(targets = "net.minecraft.client.texture.AtlasManager$Entry")
public abstract class AtlasManagerEntryMixin {
	@Unique
	private static final ThreadLocal<SpriteAtlasTexture> ERD_CAPTURED_ATLAS = new ThreadLocal<>();
	@Unique
	private static final ThreadLocal<SpriteLoader> ERD_CAPTURED_LOADER = new ThreadLocal<>();

	/** Captures the loader of the atlas being loaded ({@code Entry#load} runs on the main thread during the reload). */
	@Redirect(
			method = "load(Lnet/minecraft/resource/ResourceManager;Ljava/util/concurrent/Executor;I)Ljava/util/concurrent/CompletableFuture;",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/texture/SpriteLoader;fromAtlas(Lnet/minecraft/client/texture/SpriteAtlasTexture;)Lnet/minecraft/client/texture/SpriteLoader;"
			)
	)
	private SpriteLoader erd$captureAtlas(SpriteAtlasTexture atlasTexture) {
		SpriteLoader loader = SpriteLoader.fromAtlas(atlasTexture);
		ERD_CAPTURED_ATLAS.set(atlasTexture);
		ERD_CAPTURED_LOADER.set(loader);
		return loader;
	}

	/** Wraps the returned stitch future of the particle atlas with the fallback handler. */
	@Inject(
			method = "load(Lnet/minecraft/resource/ResourceManager;Ljava/util/concurrent/Executor;I)Ljava/util/concurrent/CompletableFuture;",
			at = @At("RETURN"),
			cancellable = true
	)
	private void erd$wrapStitchFuture(ResourceManager resourceManager, Executor executor, int mipLevel,
			CallbackInfoReturnable<CompletableFuture<SpriteLoader.StitchResult>> cir) {
		SpriteAtlasTexture atlas = ERD_CAPTURED_ATLAS.get();
		SpriteLoader loader = ERD_CAPTURED_LOADER.get();
		ERD_CAPTURED_ATLAS.remove();
		ERD_CAPTURED_LOADER.remove();
		if (atlas == null || loader == null || !atlas.getId().equals(SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE)
				|| !IncidentCenter.isAtlasStitchFallbackEnabled()) {
			return;
		}
		// the particle atlas is always loaded without mipmaps (AtlasManager.Metadata createMipmaps=false)
		CompletableFuture<SpriteLoader.StitchResult> original = cir.getReturnValue();
		cir.setReturnValue(original.handle((stitchResult, throwable) -> throwable == null
				? stitchResult
				: erd$stitchFallback(loader, executor, throwable)));
	}

	@Unique
	private static SpriteLoader.StitchResult erd$stitchFallback(SpriteLoader loader, Executor executor, Throwable error) {
		SpriteLoader.StitchResult fallback = null;
		Throwable fallbackFailure = null;
		try {
			fallback = ((SpriteLoaderStitchInvoker) (Object) loader).erd$stitch(
					List.of(MissingSprite.createSpriteContents()), 0, executor);
		} catch (Throwable t) {
			fallbackFailure = t;
		}
		IncidentCenter.onAtlasStitchFailure(error, fallbackFailure);
		if (fallback != null) {
			return fallback;
		}
		// even the fallback failed: propagate the original error so the vanilla recovery path
		// (purge resource packs / crash) still runs
		if (error instanceof RuntimeException runtimeException) {
			throw runtimeException;
		}
		throw new CompletionException(error);
	}
}
