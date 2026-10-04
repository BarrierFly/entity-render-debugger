package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Item pickup particles re-render the picked-up entity's render state. A state whose renderer
 * is broken (or the debugger's blank substitute) must not crash the frame.
 */
@Mixin(targets = "net.minecraft.client.particle.ItemPickupParticleRenderer$Result")
public abstract class ItemPickupParticleRendererResultMixin {
	@Redirect(
			method = "submit",
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
			IncidentCenter.onEntityRenderError(state, throwable,
					"ItemPickupParticleRenderer$Result#submit -> " + IncidentCenter.ROUTE_ENTITY_RENDER);
		}
	}
}
