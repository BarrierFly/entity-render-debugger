package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The main world entity render pass. Vanilla {@code EntityRenderManager#render} wraps every
 * failure into {@code CrashException "Rendering entity in world"}; here the failing entity is
 * skipped instead. The debugger's blank substitute state is skipped silently (its failure was
 * already reported when the state was extracted).
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
	@Redirect(
			method = "pushEntityRenders",
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
			IncidentCenter.onEntityRenderError(state, throwable, IncidentCenter.ROUTE_ENTITY_RENDER);
		}
	}
}
