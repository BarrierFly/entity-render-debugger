package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.render.block.entity.MobSpawnerBlockEntityRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mob/trial spawner display entities. The spawner's render-state extraction already runs
 * through the guarded {@code EntityRenderManager#getAndUpdateRenderState}; this guard keeps
 * the actual display render from crashing the block entity pass (e.g. a spawner holding an
 * entity whose renderer is missing or broken).
 */
@Mixin(MobSpawnerBlockEntityRenderer.class)
public abstract class MobSpawnerBlockEntityRendererMixin {
	@Redirect(
			method = "renderDisplayEntity",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/render/entity/EntityRenderManager;render(Lnet/minecraft/client/render/entity/state/EntityRenderState;Lnet/minecraft/client/render/state/CameraRenderState;DDDLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;)V"
			)
	)
	private static void erd$guardDisplayEntityRender(EntityRenderManager manager, EntityRenderState state,
			CameraRenderState cameraState, double x, double y, double z, MatrixStack matrices, OrderedRenderCommandQueue queue) {
		if (IncidentCenter.isBlankRenderState(state)) {
			return;
		}
		try {
			manager.render(state, cameraState, x, y, z, matrices, queue);
		} catch (Throwable throwable) {
			IncidentCenter.onEntityRenderError(state, throwable,
					"MobSpawnerBlockEntityRenderer#renderDisplayEntity -> " + IncidentCenter.ROUTE_ENTITY_RENDER);
		}
	}
}
