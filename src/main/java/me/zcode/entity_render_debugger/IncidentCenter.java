package me.zcode.entity_render_debugger;

import me.zcode.entity_render_debugger.mixin.BillboardParticleAccessor;
import me.zcode.entity_render_debugger.mixin.BillboardParticleRendererAccess;
import me.zcode.entity_render_debugger.mixin.ParticleAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderer;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.render.Submittable;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.texture.AtlasManager;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Atlases;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central hub of the debugger: every guarded call site funnels through here.
 *
 * <p>Each incident is de-duplicated and rate limited, then reported to the log, to the
 * client chat and (optionally) as a detailed root-cause report file under
 * {@code <game dir>/entity_render_debugger/reports/}. The center itself must never be
 * the reason for a crash, so everything is wrapped in catch-alls.
 */
public final class IncidentCenter {
	// Vanilla call routes through EntityRenderManager (net.minecraft.class_898).
	public static final String ROUTE_SHOULD_RENDER =
			"WorldRenderer#fillEntityRenderStates -> EntityRenderManager#shouldRender (method_3950) -> EntityRenderer#shouldRender";
	public static final String ROUTE_RENDER_STATE =
			"EntityRenderManager#getAndUpdateRenderState -> EntityRenderer#getAndUpdateRenderState -> EntityRenderer#updateRenderState";
	/** {@code GameRenderer#renderHand} asks for the local player's packed light every frame (method_23839). */
	public static final String ROUTE_GET_LIGHT =
			"GameRenderer#renderHand -> EntityRenderManager#getLight (method_23839) -> EntityRenderer#getLight";
	public static final String ROUTE_ENTITY_RENDER =
			"EntityRenderManager#render -> EntityRenderer#render";

	private static final Map<Particle, String> PARTICLE_TYPE_IDS =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<String, IncidentCenter.ReportState> REPORT_STATES = new LinkedHashMap<>();

	/** Returned to {@code ParticleManager#addToBatch} when a whole render batch fails. */
	private static final Submittable EMPTY_SUBMITTABLE = (queue, cameraRenderState) -> {};

	/** Extracts the offending sprite id from a {@code TextureStitcherCannotFitException} message. */
	private static final Pattern UNABLE_TO_FIT = Pattern.compile("Unable to fit: (\\S+)");

	/**
	 * A blank {@link EntityRenderState} substituted whenever render-state extraction fails.
	 * All vanilla consumers of entity render states are guarded against it: the outline
	 * check reads only defaulted primitive fields, and every {@code EntityRenderManager#render}
	 * call site skips it, so the dummy never reaches an actual renderer.
	 */
	private static final EntityRenderState BLANK_RENDER_STATE = new EntityRenderState();

	private static volatile @Nullable Sprite cachedMissingSprite;
	private static DebugConfig config = new DebugConfig();

	private IncidentCenter() {}

	// ------------------------------------------------------------------ init

	public static void init(DebugConfig loadedConfig) {
		config = loadedConfig;
	}

	// ------------------------------------------------- shared helpers (mixins)

	public static EntityRenderState blankRenderState() {
		return BLANK_RENDER_STATE;
	}

	public static boolean isBlankRenderState(EntityRenderState state) {
		return state == BLANK_RENDER_STATE;
	}

	/** {@return the missingno sprite of the particle atlas, or null while the atlas is not ready} */
	public static @Nullable Sprite missingSpriteOrNull() {
		if (cachedMissingSprite != null) {
			return cachedMissingSprite;
		}
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			AtlasManager atlasManager = client.getAtlasManager();
			SpriteAtlasTexture particleAtlas = atlasManager.getAtlasTexture(Atlases.PARTICLES);
			cachedMissingSprite = particleAtlas.getMissingSprite();
			return cachedMissingSprite;
		} catch (Throwable t) {
			// the atlas may not be stitched yet during resource reload; retry on the next call
			EntityRenderDebugger.LOGGER.debug("[ERD] missing sprite not available yet", t);
			return null;
		}
	}

	/**
	 * Replaces the cached missingno sprite with the instance of a freshly stitched atlas — the
	 * resource-reload audits call this because the cached sprite is invalidated by every reload.
	 */
	public static void refreshMissingSprite(@Nullable Sprite freshMissingSprite) {
		cachedMissingSprite = freshMissingSprite;
	}

	public static Submittable emptySubmittable() {
		return EMPTY_SUBMITTABLE;
	}

	/** {@return whether a failed particle atlas stitch should be degraded to a fallback atlas} */
	public static boolean isAtlasStitchFallbackEnabled() {
		return config.atlasStitchFallback;
	}

	// ------------------------------------------------ entity hooks (from mixins)

	/** {@code EntityRenderManager#getRenderer(Entity)} returned null — the entity type has no client renderer. */
	public static void onMissingRenderer(Entity entity, @Nullable String route) {
		try {
			Incident i = new Incident(Site.ENTITY_MISSING_RENDERER);
			i.subjectClass = entity.getClass().getName();
			i.errorKey = "missing-renderer";
			if (route != null) {
				i.customRoute = route;
			}
			collectEntityDetails(i, entity);
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report missing entity renderer for {}", entity, t);
		}
	}

	/** {@code EntityRenderManager#getRenderer(EntityRenderState)} returned null for a real (non-blank) state. */
	public static void onMissingRendererForState(EntityRenderState state) {
		try {
			Incident i = new Incident(Site.ENTITY_MISSING_RENDERER);
			i.subjectClass = state.getClass().getName();
			i.errorKey = "missing-renderer";
			i.details.put("Render state class", state.getClass().getName());
			if (state.entityType != null) {
				collectEntityTypeDetails(i, state.entityType);
			}
			i.details.put("Render state position", format("%.3f / %.3f / %.3f", state.x, state.y, state.z));
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report missing entity renderer for state {}", state, t);
		}
	}

	/** A guarded entity call site caught an exception (shouldRender / getAndUpdateRenderState). */
	public static void onEntityError(Site site, Entity entity, Throwable error, String route) {
		try {
			Incident i = new Incident(site);
			i.subjectClass = entity.getClass().getName();
			i.errorKey = error.getClass().getName();
			i.error = error;
			i.customRoute = route;
			collectEntityDetails(i, entity);
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report entity error at {}", site, t);
		}
	}

	/** {@code EntityRenderManager#render} threw for a real render state (guarded at the call sites). */
	public static void onEntityRenderError(@Nullable EntityRenderState state, Throwable error, String route) {
		try {
			Incident i = new Incident(Site.ENTITY_RENDER);
			i.subjectClass = state != null ? state.getClass().getName() : "?";
			i.errorKey = error.getClass().getName();
			i.error = error;
			i.customRoute = route;
			if (state != null) {
				if (state.entityType != null) {
					collectEntityTypeDetails(i, state.entityType);
				}
				i.details.put("Render state class", state.getClass().getName());
				i.details.put("Position", format("%.3f / %.3f / %.3f", state.x, state.y, state.z));
			}
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report entity render error", t);
		}
	}

	/**
	 * {@code WorldRenderer#render} called {@code SkyRendering#updateRenderState} on a null
	 * {@code skyRendering} field (vanilla 1.21.9+ reload race) or the call threw.
	 */
	public static void onSkyUpdateFailure(@Nullable Throwable error) {
		try {
			Incident i = new Incident(Site.SKY_RENDER_STATE);
			i.subjectClass = "net.minecraft.client.render.SkyRendering";
			i.errorKey = error != null ? error.getClass().getName() : "null-sky-renderer";
			i.error = error;
			i.details.put("Sky renderer field", error == null
					? "null (WorldRenderer#reload has not created the SkyRendering instance yet)"
					: "initialized (the call itself threw)");
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report sky update failure", t);
		}
	}

	/**
	 * The particle atlas failed to stitch during the resource reload (e.g. a texture that cannot
	 * fit into the atlas). {@code AtlasManagerEntryMixin} substituted a missingno-only fallback
	 * stitch result so the reload can finish; {@code fallbackFailure} is set when even the
	 * fallback failed and the vanilla recovery path (resource pack purge / crash) applies instead.
	 */
	public static void onAtlasStitchFailure(Throwable error, @Nullable Throwable fallbackFailure) {
		try {
			Incident i = new Incident(Site.ATLAS_STITCH_FALLBACK);
			i.subjectClass = "minecraft:particles";
			Throwable root = error;
			while (root.getCause() != null && root.getCause() != root) {
				root = root.getCause();
			}
			i.errorKey = root.getClass().getSimpleName();
			i.error = error;
			i.details.put("Atlas", "minecraft:particles (textures/atlas/particles.png)");
			i.details.put("Original error", String.valueOf(error));
			String offender = null;
			for (Throwable candidate = error; candidate != null && offender == null; candidate = candidate.getCause()) {
				String message = candidate.getMessage();
				if (message != null) {
					Matcher matcher = UNABLE_TO_FIT.matcher(message);
					if (matcher.find()) {
						offender = matcher.group(1);
					}
				}
			}
			if (offender != null) {
				i.details.put("Offending sprite", offender);
				try {
					i.details.put("Owner mod", describeOwnerMod(Identifier.of(offender)));
				} catch (Throwable ignored) {
					// the extracted string was not a valid identifier
				}
			}
			i.details.put("Fallback", fallbackFailure == null
					? "stitched a 16x16 missingno-only particle atlas (reload continues)"
					: "fallback stitch failed too: " + fallbackFailure);
			i.details.put("Consequence", fallbackFailure == null
					? "every particle sprite set falls back to the missingno sprite until the resource packs are fixed"
					: "the reload still fails and the vanilla recovery path (resource pack purge / crash) applies");
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report atlas stitch failure", t);
		}
	}

	// ---------------------------------------------- resource reload audits (mixins)

	/**
	 * An {@link EntityRendererFactory} threw (or returned null) while the resource reload was
	 * rebuilding the renderer maps. Vanilla wraps this into {@code IllegalArgumentException
	 * "Failed to create model for <type>"} which fails the whole reload; the debugger skips only
	 * this renderer instead (its instances are handled by the runtime guards).
	 */
	public static void onRendererRegistrationFailure(EntityType<?> entityType, EntityRendererFactory<?> factory,
			Throwable error) {
		try {
			Incident i = new Incident(Site.ENTITY_RENDERER_REGISTRATION);
			i.subjectClass = factory.getClass().getName();
			i.errorKey = error.getClass().getName();
			i.error = error;
			collectEntityTypeDetails(i, entityType);
			i.details.put("Factory class", factory.getClass().getName());
			i.details.put("Consequence", "the reload continues without this renderer; instances of the type are"
					+ " treated as invisible by the runtime guards instead of crashing");
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report renderer registration failure for {}", entityType, t);
		}
	}

	/**
	 * Post-reload audit: entity types that the freshly built renderer map does not cover, split
	 * into "no factory registered at all" and "factory present but its creation failed" (the
	 * latter also have their own {@code entity_renderer_registration} report with the trace).
	 */
	public static void onReloadRendererAudit(List<EntityType<?>> withoutFactory, List<EntityType<?>> withFailedFactory,
			int totalTypes) {
		try {
			Incident i = new Incident(Site.ENTITY_RENDERER_AUDIT);
			i.subjectClass = "resource-reload-audit";
			i.errorKey = "missing-renderer";
			i.details.put("Entity types in registry", String.valueOf(totalTypes));
			i.details.put("Types without renderer", String.valueOf(withoutFactory.size() + withFailedFactory.size()));
			appendEntityTypeList(i, "Type without factory", withoutFactory);
			appendEntityTypeList(i, "Type with failed factory", withFailedFactory);
			i.details.put("Consequence", "instances of these types are treated as invisible by the runtime guards"
					+ " instead of crashing");
			i.details.put("Summary", summarizeEntityTypes(withoutFactory, withFailedFactory));
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report the entity renderer reload audit", t);
		}
	}

	/** The player/mannequin avatar renderers (SLIM/WIDE) could not be built during the reload. */
	public static void onAvatarRenderersReloadFailure(Throwable error) {
		try {
			Incident i = new Incident(Site.ENTITY_RENDERER_REGISTRATION);
			i.subjectClass = "player-avatar-renderers";
			i.errorKey = error.getClass().getName();
			i.error = error;
			i.details.put("Renderer kind", "player avatar renderers (PlayerSkinType SLIM/WIDE)");
			i.details.put("Consequence", "players and mannequins have no renderer; the runtime guards render them as"
					+ " invisible with light 0 instead of crashing");
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report the avatar renderer reload failure", t);
		}
	}

	/**
	 * A particle sprite set bound during the reload referenced textures that are not in the
	 * freshly stitched particle atlas. Vanilla substitutes the missingno sprite and logs one
	 * summary line; this reports each affected set with its owner and the missing texture ids.
	 */
	public static void onReloadSpriteSetTexturesMissing(Identifier setId, List<Identifier> missingSpriteIds,
			int referencedCount) {
		try {
			Incident i = new Incident(Site.PARTICLE_SPRITES_RELOAD);
			i.subjectClass = setId.toString();
			i.errorKey = "missing-sprite-textures";
			i.details.put("Sprite set", setId + " (assets/" + setId.getNamespace() + "/particles/" + setId.getPath()
					+ ".json)");
			i.details.put("Owner mod", describeOwnerMod(setId));
			i.details.put("Textures referenced", String.valueOf(referencedCount));
			appendIdentifierList(i, "Missing texture", missingSpriteIds);
			i.details.put("Consequence", "the missing textures are substituted with the atlas missingno sprite"
					+ " (vanilla behaviour), so particles using this set render with the missing texture");
			i.details.put("Summary", summarizeIdentifiers("sprite set " + setId, missingSpriteIds.size()
					+ " of " + referencedCount + " textures missing", missingSpriteIds));
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report missing particle sprites for set {}", setId, t);
		}
	}

	/** A particle sprite set's texture list json parsed to an empty list. */
	public static void onReloadSpriteSetEmpty(Identifier setId) {
		try {
			Incident i = new Incident(Site.PARTICLE_SPRITES_RELOAD);
			i.subjectClass = setId.toString();
			i.errorKey = "empty-texture-list";
			i.details.put("Sprite set", setId + " (assets/" + setId.getNamespace() + "/particles/" + setId.getPath()
					+ ".json)");
			i.details.put("Owner mod", describeOwnerMod(setId));
			i.details.put("Consequence", "the set was bound with only the missingno sprite, particles using it render"
					+ " with the missing texture");
			i.details.put("Summary", "sprite set " + setId + " has an empty texture list");
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report the empty particle sprite set {}", setId, t);
		}
	}

	/**
	 * A {@code particles/<id>.json} definition failed to load or parse during the reload. Vanilla
	 * rethrows this as an unchecked exception that fails the whole resource reload; the guarded
	 * definition loader downgrades it to an unbound set instead, so the reload finishes and the
	 * runtime missingno guards handle every factory use.
	 */
	public static void onReloadSpriteDefinitionFailure(Identifier setId, Resource resource, Throwable error) {
		try {
			Incident i = new Incident(Site.PARTICLE_SPRITES_RELOAD);
			i.subjectClass = setId.toString();
			i.errorKey = "definition-load-failure";
			i.error = error;
			i.details.put("Sprite set", setId + " (assets/" + setId.getNamespace() + "/particles/" + setId.getPath()
					+ ".json)");
			i.details.put("Owner mod", describeOwnerMod(setId));
			i.details.put("Resource pack", resource.getPackId());
			i.details.put("Consequence", "the texture list could not be loaded, so the set stays unbound; every"
					+ " factory use falls back to the runtime missingno guard instead of crashing");
			i.details.put("Summary", "particle sprite set " + setId + " failed to load from pack "
					+ resource.getPackId());
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report particle definition load failure for {}", setId, t);
		}
	}

	/**
	 * Sprite sets that factories requested but for which no {@code particles/<id>.json} exists in
	 * any resource pack: their providers stay unloaded (vanilla NPEs on first use, which the
	 * runtime guard catches per particle).
	 */
	public static void onReloadUnboundSpriteSets(List<Identifier> setIds) {
		try {
			Incident i = new Incident(Site.PARTICLE_SPRITES_RELOAD);
			i.subjectClass = "sprite-sets-without-definition";
			i.errorKey = "missing-definition";
			i.details.put("Sets without definition", String.valueOf(setIds.size()));
			appendIdentifierList(i, "Set without definition", setIds);
			i.details.put("Consequence", "the providers stay unloaded; every factory use falls back to the runtime"
					+ " missingno guard instead of crashing");
			i.details.put("Summary", summarizeIdentifiers(setIds.size() + " particle sprite set(s) have no"
					+ " particles/<id>.json", "expected by registered factories", setIds));
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report the unbound particle sprite sets", t);
		}
	}

	/** Modded particle types without a client factory after the reload (they silently render nothing). */
	public static void onReloadMissingParticleFactories(List<Identifier> typeIds) {
		try {
			Incident i = new Incident(Site.PARTICLE_SPRITES_RELOAD);
			i.subjectClass = "particle-types-without-factory";
			i.errorKey = "missing-factory";
			i.details.put("Types without factory", String.valueOf(typeIds.size()));
			appendIdentifierList(i, "Type without factory", typeIds);
			i.details.put("Consequence", "createParticle returns null for them, so they are never rendered");
			i.details.put("Summary", summarizeIdentifiers(typeIds.size() + " modded particle type(s) have no client"
					+ " factory", "", typeIds));
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report the missing particle factories", t);
		}
	}

	// ---------------------------------------------- particle hooks (from mixins)

	/** Records the particle type id so later failures can be reported with it. */
	public static void recordType(Particle particle, @Nullable Identifier typeId) {
		if (typeId != null) {
			PARTICLE_TYPE_IDS.put(particle, typeId.toString());
		}
	}

	/** A particle factory threw in {@code ParticleManager#createParticle}. */
	public static void onCreateFailure(ParticleEffect effect, Throwable error) {
		String typeId;
		try {
			Identifier id = Registries.PARTICLE_TYPE.getId(effect.getType());
			typeId = id != null ? id.toString() : null;
		} catch (Throwable ignored) {
			typeId = null;
		}
		reportParticle(Site.PARTICLE_CREATE, null, typeId, null, error);
	}

	/** A particle threw while ticking ({@code ParticleRenderer#tickParticle}). */
	public static void onTickFailure(Particle particle, Throwable error) {
		reportParticle(Site.PARTICLE_TICK, particle, null, null, error);
	}

	/** A billboard particle threw while rendering ({@code BillboardParticleRenderer#render}). */
	public static void onBillboardRenderFailure(BillboardParticle particle, ParticleTextureSheet sheet, Throwable error) {
		reportParticle(Site.PARTICLE_BILLBOARD_RENDER, particle, null, sheet.name(), error);
	}

	/** A whole sheet renderer threw in {@code ParticleManager#addToBatch}. */
	public static void onRendererFailure(ParticleRenderer<?> renderer, Throwable error) {
		String sheet = renderer instanceof BillboardParticleRendererAccess access ? access.erd$getTextureSheet().name() : null;
		EntityRenderDebugger.LOGGER.warn("[ERD] failing particle renderer: {}", renderer.getClass().getName());
		reportParticle(Site.PARTICLE_BATCH_RENDER, null, null, sheet, error);
	}

	/** The sprite set of a {@code ParticleSpriteManager.SimpleSpriteProvider} is empty or unset. */
	public static void onSpriteSetMissing() {
		reportParticle(Site.PARTICLE_SPRITE_PROVIDER, null, null, null, null);
	}

	/** {@code SimpleSpriteProvider#getSprite(int, int)} was called with a non-positive max age. */
	public static void onSpriteSetBadAge(int maxAge) {
		reportParticle(Site.PARTICLE_SPRITE_PROVIDER, null, null, "maxAge=" + maxAge,
				new ArithmeticException("sprite age with maxAge=" + maxAge));
	}

	/** A {@link BillboardParticle} ended up with a null sprite; the mixin already swapped in missingno. */
	public static void onNullSprite(BillboardParticle particle) {
		reportParticle(Site.PARTICLE_NULL_SPRITE, particle, null, null, null);
	}

	private static void reportParticle(Site site, @Nullable Particle particle, @Nullable String typeId,
			@Nullable String sheetName, @Nullable Throwable error) {
		try {
			Incident i = new Incident(site);
			i.subjectClass = particle != null ? particle.getClass().getName() : "?";
			i.errorKey = error != null ? error.getClass().getName() : "missing-sprite";
			i.error = error;
			i.sheet = sheetName;
			String resolvedTypeId = particle != null ? resolveTypeId(particle, typeId) : typeId;
			if (resolvedTypeId != null || particle != null) {
				i.details.put("Particle", (resolvedTypeId != null ? resolvedTypeId : "?") + " (class " + i.subjectClass + ")");
			}
			if (particle != null) {
				ParticleAccessor accessor = (ParticleAccessor) particle;
				i.details.put("Particle position", format("%.2f / %.2f / %.2f", accessor.erd$getX(), accessor.erd$getY(), accessor.erd$getZ()));
			}
			if (particle instanceof BillboardParticle billboard) {
				Sprite sprite = ((BillboardParticleAccessor) billboard).erd$getSprite();
				if (sprite != null) {
					i.details.put("Sprite", sprite.getContents().getId() + " (atlas " + sprite.getAtlasId() + ")");
				}
			}
			emit(i);
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to report particle error", t);
		}
	}

	private static @Nullable String resolveTypeId(Particle particle, @Nullable String fallback) {
		if (fallback != null) {
			return fallback;
		}
		return PARTICLE_TYPE_IDS.get(particle);
	}

	// ------------------------------------------------------------------ report

	private static void emit(Incident i) {
		if (!config.enabled) {
			return;
		}

		int suppressed;
		synchronized (REPORT_STATES) {
			IncidentCenter.ReportState state = REPORT_STATES.computeIfAbsent(
					i.site.key + '|' + i.subjectClass + '|' + i.errorKey, key -> new ReportState());
			long now = System.currentTimeMillis();
			if (now - state.lastReportAt < config.chatCooldownMs) {
				state.suppressed++;
				return;
			}
			suppressed = state.suppressed;
			state.suppressed = 0;
			state.lastReportAt = now;
		}
		i.suppressed = suppressed;

		// log always, with full stack trace if enabled
		String summary = i.site.key + ": " + i.subjectClass + (i.sheet != null ? " sheet=" + i.sheet : "");
		if (i.error != null && config.logStackTraces) {
			EntityRenderDebugger.LOGGER.error("[ERD] intercepted rendering issue ({} suppressed): {}",
					suppressed, summary, i.error);
		} else {
			EntityRenderDebugger.LOGGER.warn("[ERD] intercepted rendering issue ({} suppressed): {} error={}",
					suppressed, summary, i.error);
		}

		// detailed report file on disk
		ReportWriter.Report report = config.writeReports ? ReportWriter.write(i) : null;
		if (report != null) {
			EntityRenderDebugger.LOGGER.info("[ERD] detailed report written to {}", report.file());
		}

		sendChatMessage(buildChatMessage(i, report));
	}

	private static MutableText buildChatMessage(Incident i, ReportWriter.@Nullable Report report) {
		MutableText text = Text.literal("[EntityRenderDebugger] ").formatted(Formatting.GOLD, Formatting.BOLD);
		text.append(Text.translatable("entity_render_debugger.header").formatted(Formatting.RED));
		text.append(Text.translatable("entity_render_debugger.action." + i.site.key).formatted(Formatting.RED));
		if (i.suppressed > 0) {
			text.append(Text.translatable("entity_render_debugger.suppressed", i.suppressed).formatted(Formatting.GRAY));
		}

		@Nullable String typeLine = i.details.get("Entity type");
		if (typeLine != null) {
			text.append("\n").append(Text.translatable("entity_render_debugger.entity_of_type",
					typeLine, i.details.getOrDefault("Entity class", "?"), i.details.getOrDefault("Owner mod", "?"))
					.formatted(Formatting.GRAY));
		} else if (!"?".equals(i.subjectClass)) {
			text.append("\n").append(Text.translatable("entity_render_debugger.entity", "?", i.subjectClass).formatted(Formatting.GRAY));
		}

		@Nullable String position = i.details.get("Position");
		if (position != null) {
			text.append("\n").append(Text.translatable("entity_render_debugger.position", position).formatted(Formatting.GRAY));
		}

		@Nullable String summaryLine = i.details.get("Summary");
		if (summaryLine != null) {
			text.append("\n").append(Text.literal(summaryLine).formatted(Formatting.GRAY));
		}

		@Nullable String particle = i.details.get("Particle");
		if (particle != null) {
			text.append("\n").append(Text.translatable("entity_render_debugger.particle", particle).formatted(Formatting.GRAY));
		}

		text.append("\n").append(Text.translatable("entity_render_debugger.route", i.route()).formatted(Formatting.GRAY));

		if (i.sheet != null) {
			text.append("\n").append(Text.translatable("entity_render_debugger.sheet", i.sheet).formatted(Formatting.GRAY));
		}

		String errorDesc = i.error != null ? i.error.toString() : describeErrorless(i);
		String stackTrace = i.error != null && config.logStackTraces ? ReportWriter.stackTraceOf(i.error) : null;
		Text errorText = Text.translatable("entity_render_debugger.error", errorDesc).styled(style -> {
			Style styled = style.withColor(Formatting.RED);
			if (stackTrace != null) {
				styled = styled.withHoverEvent(new HoverEvent.ShowText(Text.literal(stackTrace)));
			}
			return styled;
		});
		text.append("\n").append(errorText);

		if (report != null) {
			Text reportText = Text.translatable("entity_render_debugger.report", report.relativePath())
					.styled(style -> style.withColor(Formatting.AQUA).withUnderline(true)
							.withHoverEvent(new HoverEvent.ShowText(Text.literal(report.summary()))));
			text.append("\n").append(reportText);
		}
		return text;
	}

	private static String describeErrorless(Incident i) {
		return switch (i.site) {
			case PARTICLE_SPRITE_PROVIDER -> "sprite set missing / not loaded";
			case PARTICLE_NULL_SPRITE -> "sprite == null (replaced with missingno)";
			case ENTITY_MISSING_RENDERER -> "no EntityRenderer registered for this entity type";
			case SKY_RENDER_STATE -> "skyRendering == null (WorldRenderer#reload not finished yet)";
			case ENTITY_RENDERER_REGISTRATION -> "the renderer factory threw while the reload rebuilt the renderer maps";
			case ENTITY_RENDERER_AUDIT -> "entity types without renderer detected by the post-reload audit";
			case PARTICLE_SPRITES_RELOAD -> "missing particle sprite resources detected by the post-reload audit";
			case ATLAS_STITCH_FALLBACK -> "the particle atlas failed to stitch; a missingno-only fallback atlas was substituted";
			default -> "no exception";
		};
	}

	private static void sendChatMessage(MutableText message) {
		if (!config.chatReports) {
			return;
		}
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client == null || client.inGameHud == null) {
				return;
			}
			Runnable send = () -> client.inGameHud.getChatHud().addMessage(message);
			if (client.isOnThread()) {
				send.run();
			} else {
				client.execute(send);
			}
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.warn("[ERD] failed to show chat message", t);
		}
	}

	// ------------------------------------------------------------ collectors

	/** {@return a "<name> (<namespace>)" description of the mod owning the id's namespace} */
	private static String describeOwnerMod(Identifier id) {
		String namespace = id.getNamespace();
		return FabricLoader.getInstance().getModContainer(namespace)
				.map(container -> container.getMetadata().getName() + " (" + namespace + ")")
				.orElse("unknown mod (" + namespace + ")");
	}

	private static void appendEntityTypeList(Incident i, String key, List<EntityType<?>> types) {
		int shown = Math.min(types.size(), 30);
		for (int idx = 0; idx < shown; idx++) {
			i.details.put(key + " " + (idx + 1), describeEntityType(types.get(idx)));
		}
		if (types.size() > shown) {
			i.details.put(key + " ...", (types.size() - shown) + " more");
		}
	}

	private static String describeEntityType(EntityType<?> type) {
		try {
			Identifier id = EntityType.getId(type);
			return id + " (class " + type.getBaseClass().getName() + ", owner " + describeOwnerMod(id) + ")";
		} catch (Throwable t) {
			return "<failed to describe: " + t + ">";
		}
	}

	private static String summarizeEntityTypes(List<EntityType<?>> withoutFactory, List<EntityType<?>> withFailedFactory) {
		List<Identifier> ids = new ArrayList<>();
		for (EntityType<?> type : withoutFactory) {
			ids.add(entityTypeId(type));
		}
		for (EntityType<?> type : withFailedFactory) {
			ids.add(entityTypeId(type));
		}
		int total = withoutFactory.size() + withFailedFactory.size();
		String detail = withoutFactory.isEmpty()
				? "all from failed factories"
				: withFailedFactory.isEmpty()
						? "all without factory"
						: (withoutFactory.size() + " without factory, " + withFailedFactory.size() + " with failed factory");
		return summarizeIdentifiers(total + " entity type(s) without renderer (" + detail + ")", "", ids);
	}

	private static Identifier entityTypeId(EntityType<?> type) {
		try {
			return EntityType.getId(type);
		} catch (Throwable t) {
			return Identifier.of("?", "unknown");
		}
	}

	private static void appendIdentifierList(Incident i, String key, List<Identifier> ids) {
		int shown = Math.min(ids.size(), 30);
		for (int idx = 0; idx < shown; idx++) {
			Identifier id = ids.get(idx);
			i.details.put(key + " " + (idx + 1), id + " (owner " + describeOwnerMod(id) + ")");
		}
		if (ids.size() > shown) {
			i.details.put(key + " ...", (ids.size() - shown) + " more");
		}
	}

	private static String summarizeIdentifiers(String headline, String suffix, List<Identifier> ids) {
		StringBuilder sb = new StringBuilder(headline);
		if (!suffix.isEmpty()) {
			sb.append(" — ").append(suffix).append(':');
		} else {
			sb.append(':');
		}
		int shown = Math.min(ids.size(), 5);
		for (int idx = 0; idx < shown; idx++) {
			sb.append(idx == 0 ? " " : ", ").append(ids.get(idx));
		}
		if (ids.size() > shown) {
			sb.append(", ... (+").append(ids.size() - shown).append(")");
		}
		return sb.toString();
	}

	private static void collectEntityDetails(Incident i, Entity entity) {
		collectEntityTypeDetails(i, entity.getType());
		i.details.put("Runtime class", entity.getClass().getName());
		try {
			i.details.put("Display name", entity.getDisplayName().getString());
		} catch (Throwable t) {
			i.details.put("Display name", "<failed: " + t + ">");
		}
		i.details.put("UUID", entity.getUuidAsString());
		i.details.put("Network id", String.valueOf(entity.getId()));
		i.details.put("Dimension", entity.getEntityWorld().getRegistryKey().getValue().toString());
		Vec3d pos = entity.getEntityPos();
		i.details.put("Position", format("%.3f / %.3f / %.3f", pos.x, pos.y, pos.z));
		i.details.put("NBT", serializeNbt(entity));
	}

	private static void collectEntityTypeDetails(Incident i, EntityType<?> entityType) {
		try {
			Identifier id = EntityType.getId(entityType);
			i.details.put("Entity type", id.toString());
			String namespace = id.getNamespace();
			i.details.put("Owner mod", FabricLoader.getInstance().getModContainer(namespace)
					.map(container -> container.getMetadata().getName() + " (" + namespace + ")")
					.orElse("unknown mod (" + namespace + ")"));
			i.details.put("Entity class", entityType.getBaseClass().getName());
		} catch (Throwable t) {
			i.details.put("Entity type", "<failed: " + t + ">");
		}
	}

	private static String serializeNbt(Entity entity) {
		try (ErrorReporter.Logging collector = new ErrorReporter.Logging(entity.getErrorReporterContext(), EntityRenderDebugger.LOGGER)) {
			NbtWriteView view = NbtWriteView.create(collector, entity.getRegistryManager());
			entity.writeData(view);
			NbtCompound nbt = view.getNbt();
			String nbtString = nbt.toString();
			return nbtString.length() > 8000 ? nbtString.substring(0, 8000) + "..." : nbtString;
		} catch (Throwable throwable) {
			return "<failed to serialize nbt: " + throwable + ">";
		}
	}

	private static String format(String pattern, Object... args) {
		return String.format(java.util.Locale.ROOT, pattern, args);
	}

	// ------------------------------------------------------------------ misc

	/** Lightweight reference to an incident being assembled by a hook before emission. */
	static final class Incident {
		final Site site;
		final LinkedHashMap<String, String> details = new LinkedHashMap<>();
		String subjectClass = "?";
		String errorKey = "missing-renderer";
		@Nullable Throwable error;
		@Nullable String sheet;
		@Nullable String customRoute;
		int suppressed;

		Incident(Site site) {
			this.site = site;
		}

		String route() {
			return customRoute != null ? customRoute : site.route(subjectClass);
		}
	}

	private static final class ReportState {
		long lastReportAt = Long.MIN_VALUE;
		int suppressed;
	}
}
