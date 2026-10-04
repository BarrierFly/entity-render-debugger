package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.gui.render.EntityGuiElementRenderer;
import net.minecraft.client.gui.render.state.special.EntityGuiElementRenderState;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * GUI entity elements (entity previews in screens). A throwing renderer must not close the
 * client while opening an inventory — the element is skipped instead.
 */
@Mixin(EntityGuiElementRenderer.class)
public abstract class EntityGuiElementRendererMixin {
	@Redirect(
			method = "render(Lnet/minecraft/client/gui/render/state/special/EntityGuiElementRenderState;Lnet/minecraft/client/util/math/MatrixStack;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/render/entity/EntityRenderManager;render(Lnet/minecraft/client/render/entity/state/EntityRenderState;Lnet/minecraft/client/render/state/CameraRenderState;DDDLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;)V"
			)
	)
	private void erd$guardEntityRender(EntityRenderManager manager, EntityRenderState state, CameraRenderState cameraState,
			double x, double y, double z, MatrixStack matrices, OrderedRenderCommandQueue queue) {
		if (IncidentCenter.isBlankRenderState(state)) {
			return;
		}
		try {
			manager.render(state, cameraState, x, y, z, matrices, queue);
		} catch (Throwable throwable) {
			IncidentCenter.onEntityRenderError(state, throwable, "EntityGuiElementRenderer#render -> " + IncidentCenter.ROUTE_ENTITY_RENDER);
		}
	}
}
