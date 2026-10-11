package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import me.zcode.entity_render_debugger.Site;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guards the whole render-dispatch front end of {@code EntityRenderManager}
 * ({@code net.minecraft.class_898}), most importantly:
 *
 * <ul>
 *   <li>{@code method_3950} / {@code shouldRender(Entity, Frustum, double, double, double)} —
 *       vanilla executes {@code getRenderer(entity).shouldRender(...)} with no null check and no
 *       try/catch, so a missing renderer (NPE) or a throwing renderer crashes the frame.</li>
 *   <li>{@code getAndUpdateRenderState(Entity, float)} — vanilla wraps every failure into
 *       {@code CrashException "Extracting render state for an entity in world"}.</li>
 *   <li>{@code getLight(Entity, float)} ({@code method_23839}) — vanilla does
 *       {@code getRenderer(entity).getLight(...)} with no null check; {@code GameRenderer#renderHand}
 *       calls it with the local player every frame, so a missing player renderer (startup/reload
 *       race) NPEs the whole frame here.</li>
 *   <li>both {@code getRenderer} overloads — monitored for null results (missing renderer
 *       detection, ported from the entity-renderer-debugger mod by Fallen_Breath).</li>
 * </ul>
 *
 * <p>The leash-holder lookups inside {@code EntityRenderer#shouldRender} and
 * {@code EntityRenderer#updateRenderState} ({@code getRenderer(leashHolder).getBoundingBoxForCulling /
 * getBlockLightLevel}) need no dedicated guard: they only run inside those two renderer methods,
 * which are wrapped by the {@code shouldRender} / {@code getAndUpdateRenderState} guards below.
 */
@Mixin(EntityRenderManager.class)
public abstract class EntityRenderManagerMixin {
	@Shadow
	public abstract <T extends Entity> EntityRenderer<? super T, ?> getRenderer(T entity);

	/**
	 * Reimplementation of {@code shouldRender} ({@code method_3950}) that can never propagate a
	 * failure: missing renderer -> report + "not visible", throwing renderer -> report + "not
	 * visible". Callers (e.g. {@code WorldRenderer#fillEntityRenderStates}) keep working.
	 */
	@Inject(
			method = "shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/Frustum;DDD)Z",
			at = @At("HEAD"),
			cancellable = true
	)
	private <E extends Entity> void erd$guardShouldRender(E entity, Frustum frustum, double x, double y, double z,
			CallbackInfoReturnable<Boolean> cir) {
		try {
			EntityRenderer<? super E, ?> renderer = this.getRenderer(entity);
			if (renderer == null) {
				IncidentCenter.onMissingRenderer(entity, IncidentCenter.ROUTE_SHOULD_RENDER);
				cir.setReturnValue(Boolean.FALSE);
				return;
			}
			try {
				cir.setReturnValue(renderer.shouldRender(entity, frustum, x, y, z));
			} catch (Throwable throwable) {
				IncidentCenter.onEntityError(Site.ENTITY_SHOULD_RENDER, entity, throwable, IncidentCenter.ROUTE_SHOULD_RENDER);
				cir.setReturnValue(Boolean.FALSE);
			}
		} catch (Throwable throwable) {
			IncidentCenter.onEntityError(Site.ENTITY_SHOULD_RENDER, entity, throwable, IncidentCenter.ROUTE_SHOULD_RENDER);
			cir.setReturnValue(Boolean.FALSE);
		}
	}

	/**
	 * Guarded reimplementation of render-state extraction: on any failure a blank state is
	 * substituted instead of the vanilla CrashException. Every consumer of the blank state is
	 * guarded too (WorldRenderer / GUI / spawner / item-pickup mixins).
	 */
	@Inject(
			method = "getAndUpdateRenderState(Lnet/minecraft/entity/Entity;F)Lnet/minecraft/client/render/entity/state/EntityRenderState;",
			at = @At("HEAD"),
			cancellable = true
	)
	private <E extends Entity> void erd$guardGetAndUpdateRenderState(E entity, float tickDelta,
			CallbackInfoReturnable<EntityRenderState> cir) {
		try {
			EntityRenderer<? super E, ?> renderer = this.getRenderer(entity);
			if (renderer == null) {
				IncidentCenter.onMissingRenderer(entity, IncidentCenter.ROUTE_RENDER_STATE);
				cir.setReturnValue(IncidentCenter.blankRenderState());
				return;
			}
			try {
				cir.setReturnValue(renderer.getAndUpdateRenderState(entity, tickDelta));
			} catch (Throwable throwable) {
				IncidentCenter.onEntityError(Site.ENTITY_RENDER_STATE, entity, throwable, IncidentCenter.ROUTE_RENDER_STATE);
				cir.setReturnValue(IncidentCenter.blankRenderState());
			}
		} catch (Throwable throwable) {
			IncidentCenter.onEntityError(Site.ENTITY_RENDER_STATE, entity, throwable, IncidentCenter.ROUTE_RENDER_STATE);
			cir.setReturnValue(IncidentCenter.blankRenderState());
		}
	}

	/**
	 * Guarded reimplementation of {@code getLight} ({@code method_23839}): on any failure light 0
	 * is reported instead of the vanilla NPE, keeping the frame alive. The main consumer is
	 * {@code GameRenderer#renderHand}, which asks for the local player's light every frame —
	 * a missing player renderer (startup/reload race) must not crash there.
	 */
	@Inject(
			method = "getLight(Lnet/minecraft/entity/Entity;F)I",
			at = @At("HEAD"),
			cancellable = true
	)
	private <E extends Entity> void erd$guardGetLight(E entity, float tickDelta,
			CallbackInfoReturnable<Integer> cir) {
		try {
			EntityRenderer<? super E, ?> renderer = this.getRenderer(entity);
			if (renderer == null) {
				IncidentCenter.onMissingRenderer(entity, IncidentCenter.ROUTE_GET_LIGHT);
				cir.setReturnValue(0);
				return;
			}
			try {
				cir.setReturnValue(renderer.getLight(entity, tickDelta));
			} catch (Throwable throwable) {
				IncidentCenter.onEntityError(Site.ENTITY_GET_LIGHT, entity, throwable, IncidentCenter.ROUTE_GET_LIGHT);
				cir.setReturnValue(0);
			}
		} catch (Throwable throwable) {
			IncidentCenter.onEntityError(Site.ENTITY_GET_LIGHT, entity, throwable, IncidentCenter.ROUTE_GET_LIGHT);
			cir.setReturnValue(0);
		}
	}

	/** Reports entities without a registered renderer wherever the manager is asked (entity-renderer-debugger). */
	@Inject(
			method = "getRenderer(Lnet/minecraft/entity/Entity;)Lnet/minecraft/client/render/entity/EntityRenderer;",
			at = @At("RETURN")
	)
	private <T extends Entity> void erd$onGetRenderer(T entity, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
		if (cir.getReturnValue() == null && entity != null) {
			IncidentCenter.onMissingRenderer(entity, null);
		}
	}

	/** Same monitoring for the render-state overload; the debugger's own blank state is ignored. */
	@Inject(
			method = "getRenderer(Lnet/minecraft/client/render/entity/state/EntityRenderState;)Lnet/minecraft/client/render/entity/EntityRenderer;",
			at = @At("RETURN")
	)
	private void erd$onGetRendererByState(EntityRenderState state, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
		if (cir.getReturnValue() == null && state != null && !IncidentCenter.isBlankRenderState(state)) {
			IncidentCenter.onMissingRendererForState(state);
		}
	}
}
