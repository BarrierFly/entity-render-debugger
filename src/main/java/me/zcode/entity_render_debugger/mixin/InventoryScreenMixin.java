package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import me.zcode.entity_render_debugger.Site;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code InventoryScreen#drawEntity} dereferences {@code getRenderer(entity)} without a check
 * and then reads fields of the produced state — a missing renderer NPEs while opening the
 * inventory. The blank substitute state keeps the screen alive (its rendering is skipped by the
 * {@code EntityGuiElementRenderer} guard).
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	@Redirect(
			method = "drawEntity(Lnet/minecraft/entity/LivingEntity;)Lnet/minecraft/client/render/entity/state/EntityRenderState;",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/render/entity/EntityRenderer;getAndUpdateRenderState(Lnet/minecraft/entity/Entity;F)Lnet/minecraft/client/render/entity/state/EntityRenderState;"
			)
	)
	private static EntityRenderState erd$guardDrawEntity(EntityRenderer renderer, Entity entity, float tickDelta) {
		if (renderer == null) {
			IncidentCenter.onMissingRenderer(entity, "InventoryScreen#drawEntity -> EntityRenderManager#getRenderer");
			return IncidentCenter.blankRenderState();
		}
		try {
			return renderer.getAndUpdateRenderState(entity, tickDelta);
		} catch (Throwable throwable) {
			IncidentCenter.onEntityError(Site.ENTITY_RENDER_STATE, entity, throwable,
					"InventoryScreen#drawEntity -> EntityRenderer#getAndUpdateRenderState");
			return IncidentCenter.blankRenderState();
		}
	}
}
