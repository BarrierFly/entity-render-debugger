package me.zcode.entity_render_debugger.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import me.zcode.entity_render_debugger.EntityRenderDebugger;
import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleSpriteManager;
import net.minecraft.client.texture.SpriteLoader;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Audits the particle sprite resources at the end of a resource reload
 * ({@code net.minecraft.class_11939}, apply/bind stage {@code method_74298}): after vanilla
 * resolved every {@code particles/<id>.json} texture list against the freshly stitched particle
 * atlas and bound the sprite sets, this reports —
 *
 * <ul>
 *   <li>textures referenced by a sprite set but absent from the atlas (vanilla substitutes the
 *       missingno sprite and logs a single summary line, without owner or per-set attribution);</li>
 *   <li>sprite sets whose texture list json parsed to an empty list;</li>
 *   <li>sprite sets requested by factories with no {@code particles/<id>.json} in any resource
 *       pack — their providers stay unloaded, which vanilla NPEs on at first use (the runtime
 *       guards catch that per particle);</li>
 *   <li>modded particle types without a client factory (they silently render nothing).</li>
 * </ul>
 *
 * <p>It also refreshes the debugger's cached atlas missingno sprite, which every reload
 * invalidates. The audits must never break the reload, so everything is wrapped in catch-alls.
 */
@Mixin(ParticleSpriteManager.class)
public abstract class ParticleSpriteManagerMixin {
	@Shadow
	@Final
	private Map<Identifier, ?> spriteAwareParticleFactories;

	@Shadow
	public abstract Int2ObjectMap<ParticleFactory<?>> getParticleFactories();

	@Inject(method = "method_74298", at = @At("TAIL"))
	private void erd$auditSpriteReload(CompletableFuture<?> stitchFuture, CompletableFuture<?> definitionsFuture,
			Void unused, CallbackInfo ci) {
		try {
			// the apply stage joins both futures before binding, so join() here never blocks
			if (!(stitchFuture.join() instanceof SpriteLoader.StitchResult stitchResult)) {
				EntityRenderDebugger.LOGGER.warn("[ERD] unexpected particle atlas stitch result, skipping the"
						+ " particle sprite reload audit");
				return;
			}
			IncidentCenter.refreshMissingSprite(stitchResult.missing());
			if (!(definitionsFuture.join() instanceof List<?> definitions)) {
				EntityRenderDebugger.LOGGER.warn("[ERD] unexpected particle sprite definitions, skipping the"
						+ " particle sprite reload audit");
				return;
			}
			erd$auditDefinitions(stitchResult, definitions);
			erd$auditUnboundSets();
			erd$auditParticleFactories();
		} catch (Throwable throwable) {
			EntityRenderDebugger.LOGGER.error("[ERD] particle sprite reload audit failed", throwable);
		}
	}

	@Unique
	private void erd$auditDefinitions(SpriteLoader.StitchResult stitchResult, List<?> definitions) {
		for (Object definitionObject : definitions) {
			if (!(definitionObject instanceof ParticleSpriteManagerReloadResultAccessor definition)) {
				continue;
			}
			Identifier setId = definition.erd$getId();
			try {
				Optional<List<Identifier>> spriteIds = definition.erd$getSprites();
				if (spriteIds.isEmpty()) {
					// no texture list was loaded for this id: either the set is not requested by any
					// factory (vanilla "redundant texture list", intentional) or the json failed to
					// parse (that failure aborts the reload before this audit runs)
					continue;
				}
				List<Identifier> referenced = spriteIds.get();
				if (referenced.isEmpty()) {
					IncidentCenter.onReloadSpriteSetEmpty(setId);
					continue;
				}
				List<Identifier> missing = null;
				for (Identifier spriteId : referenced) {
					if (stitchResult.getSprite(spriteId) == null) {
						if (missing == null) {
							missing = new ArrayList<>();
						}
						missing.add(spriteId);
					}
				}
				if (missing != null) {
					IncidentCenter.onReloadSpriteSetTexturesMissing(setId, missing, referenced.size());
				}
			} catch (Throwable throwable) {
				EntityRenderDebugger.LOGGER.error("[ERD] failed to audit particle sprite set {}", setId, throwable);
			}
		}
	}

	@Unique
	private void erd$auditUnboundSets() {
		try {
			List<Identifier> unbound = new ArrayList<>();
			for (Map.Entry<Identifier, ?> entry : this.spriteAwareParticleFactories.entrySet()) {
				// providers created for factory requests stay sprite-less when no particles/<id>.json exists
				if (((ParticleSpriteProviderAccessor) entry.getValue()).erd$getSprites() == null) {
					unbound.add(entry.getKey());
				}
			}
			if (!unbound.isEmpty()) {
				IncidentCenter.onReloadUnboundSpriteSets(unbound);
			}
		} catch (Throwable throwable) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to audit unbound particle sprite sets", throwable);
		}
	}

	@Unique
	private void erd$auditParticleFactories() {
		try {
			Int2ObjectMap<ParticleFactory<?>> factories = this.getParticleFactories();
			List<Identifier> withoutFactory = new ArrayList<>();
			for (ParticleType<?> type : Registries.PARTICLE_TYPE) {
				Identifier id = Registries.PARTICLE_TYPE.getId(type);
				// vanilla registers a factory for every one of its own types; only report modded gaps
				if ("minecraft".equals(id.getNamespace())) {
					continue;
				}
				if (!factories.containsKey(Registries.PARTICLE_TYPE.getRawId(type))) {
					withoutFactory.add(id);
				}
			}
			if (!withoutFactory.isEmpty()) {
				IncidentCenter.onReloadMissingParticleFactories(withoutFactory);
			}
		} catch (Throwable throwable) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to audit particle factories", throwable);
		}
	}
}
