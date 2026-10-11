package me.zcode.entity_render_debugger.mixin;

import com.google.common.collect.ImmutableMap;
import me.zcode.entity_render_debugger.EntityRenderDebugger;
import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.network.ClientPlayerLikeEntity;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactories;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.registry.Registries;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guards the resource-reload rebuild of the entity renderer maps
 * ({@code net.minecraft.class_5619}, called from {@code EntityRenderManager#reload}):
 *
 * <ul>
 *   <li>{@code reloadEntityRenderers(EntityRendererFactory.Context)} — vanilla invokes every
 *       registered factory inside one pass and wraps any failure into
 *       {@code IllegalArgumentException "Failed to create model for <type>"}, failing the whole
 *       reload. The guard invokes each factory separately: a failing factory is reported in full
 *       detail (entity type, owner mod, factory class, stack trace) and skipped, and the reload
 *       continues with the remaining renderers.</li>
 *   <li>{@code reloadPlayerRenderers(EntityRendererFactory.Context)} — a failure would crash the
 *       reload with {@code IllegalArgumentException "Failed to create avatar models"}; the guard
 *       reports it and continues with no player renderers (the runtime guards render players as
 *       invisible with light 0).</li>
 *   <li>After rebuilding, the entity type registry is audited against the fresh renderer map and
 *       every uncovered type (missing factory or failed factory) is reported with owner-mod
 *       attribution.</li>
 * </ul>
 */
@Mixin(EntityRendererFactories.class)
public abstract class EntityRendererFactoriesMixin {
	@Shadow
	@Final
	private static Map<EntityType<?>, EntityRendererFactory<?>> RENDERER_FACTORIES;

	/**
	 * Guarded reimplementation of {@code reloadEntityRenderers} ({@code method_32176}): every
	 * factory runs in its own try/catch and a post-build audit reports uncovered entity types.
	 */
	@Inject(method = "reloadEntityRenderers", at = @At("HEAD"), cancellable = true)
	private static void erd$guardReloadEntityRenderers(EntityRendererFactory.Context context,
			CallbackInfoReturnable<Map<EntityType<?>, EntityRenderer<?, ?>>> cir) {
		ImmutableMap.Builder<EntityType<?>, EntityRenderer<?, ?>> builder = ImmutableMap.builder();
		Set<EntityType<?>> failedFactories = new HashSet<>();
		try {
			for (Map.Entry<EntityType<?>, EntityRendererFactory<?>> entry : RENDERER_FACTORIES.entrySet()) {
				try {
					EntityRenderer<?, ?> renderer = entry.getValue().create(context);
					if (renderer == null) {
						IncidentCenter.onRendererRegistrationFailure(entry.getKey(), entry.getValue(),
								new IllegalStateException("EntityRendererFactory returned null"));
						failedFactories.add(entry.getKey());
						continue;
					}
					builder.put(entry.getKey(), renderer);
				} catch (Throwable throwable) {
					IncidentCenter.onRendererRegistrationFailure(entry.getKey(), entry.getValue(), throwable);
					failedFactories.add(entry.getKey());
				}
			}
			Map<EntityType<?>, EntityRenderer<?, ?>> renderers = builder.build();
			try {
				erd$auditRenderers(renderers, failedFactories);
			} catch (Throwable auditFailure) {
				EntityRenderDebugger.LOGGER.error("[ERD] entity renderer reload audit failed", auditFailure);
			}
			cir.setReturnValue(renderers);
		} catch (Throwable throwable) {
			EntityRenderDebugger.LOGGER.error("[ERD] guarded entity renderer reload failed unexpectedly,"
					+ " continuing with an empty renderer map", throwable);
			cir.setReturnValue(Map.of());
		}
	}

	/**
	 * Guarded reimplementation of {@code reloadPlayerRenderers} ({@code method_32177}): a failure
	 * is reported and the reload continues without player renderers instead of crashing.
	 */
	@Inject(method = "reloadPlayerRenderers", at = @At("HEAD"), cancellable = true)
	private static <T extends PlayerLikeEntity & ClientPlayerLikeEntity> void erd$guardReloadPlayerRenderers(
			EntityRendererFactory.Context context,
			CallbackInfoReturnable<Map<PlayerSkinType, PlayerEntityRenderer<T>>> cir) {
		try {
			cir.setReturnValue(Map.of(
					PlayerSkinType.WIDE, new PlayerEntityRenderer<>(context, false),
					PlayerSkinType.SLIM, new PlayerEntityRenderer<>(context, true)));
		} catch (Throwable throwable) {
			IncidentCenter.onAvatarRenderersReloadFailure(throwable);
			cir.setReturnValue(Map.of());
		}
	}

	@Unique
	private static void erd$auditRenderers(Map<EntityType<?>, EntityRenderer<?, ?>> renderers,
			Set<EntityType<?>> failedFactories) {
		List<EntityType<?>> withoutFactory = new ArrayList<>();
		List<EntityType<?>> withFailedFactory = new ArrayList<>();
		int totalTypes = 0;
		for (EntityType<?> type : Registries.ENTITY_TYPE) {
			totalTypes++;
			// players and mannequins are rendered through the player/mannequin renderer maps instead
			if (type == EntityType.PLAYER || type == EntityType.MANNEQUIN || renderers.containsKey(type)) {
				continue;
			}
			if (failedFactories.contains(type)) {
				withFailedFactory.add(type);
			} else {
				withoutFactory.add(type);
			}
		}
		if (!withoutFactory.isEmpty() || !withFailedFactory.isEmpty()) {
			IncidentCenter.onReloadRendererAudit(withoutFactory, withFailedFactory, totalTypes);
		}
	}
}
