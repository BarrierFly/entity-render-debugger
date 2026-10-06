package me.zcode.entity_render_debugger.mixin;

import me.zcode.entity_render_debugger.IncidentCenter;
import net.minecraft.client.render.SkyRendering;
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
	/**
	 * Guards the sky state update in {@code WorldRenderer#render}: vanilla dereferences
	 * {@code this.skyRendering} without a null check, but the instance is only created in
	 * {@code WorldRenderer#reload}, so a frame rendered before the reload finishes crashes with
	 * {@code NullPointerException: Cannot invoke "net.minecraft.class_9975.method_74926(...)"
	 * because "this.field_53075" is null}. The update is skipped instead (vanilla's sky pass is
	 * null-guarded and simply doesn't draw that frame); exceptions from a present renderer are
	 * caught the same way.
	 */
	@Redirect(
			method = "render(Lnet/minecraft/client/util/memory/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/render/SkyRendering;updateRenderState(Lnet/minecraft/client/world/ClientWorld;FLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/state/SkyRenderState;)V"
			)
	)
	private void erd$guardSkyUpdateRenderState(SkyRendering skyRendering, net.minecraft.client.world.ClientWorld world,
			float tickDelta, net.minecraft.client.render.Camera camera, net.minecraft.client.render.state.SkyRenderState skyRenderState) {
		if (skyRendering == null) {
			IncidentCenter.onSkyUpdateFailure(null);
			return;
		}
		try {
			skyRendering.updateRenderState(world, tickDelta, camera, skyRenderState);
		} catch (Throwable throwable) {
			IncidentCenter.onSkyUpdateFailure(throwable);
		}
	}

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
