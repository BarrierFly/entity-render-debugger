package me.zcode.entity_render_debugger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entity Render Debugger — merged from entity-renderer-debugger (missing entity renderer
 * reporting by Fallen_Breath) and particle-render-monitor (particle failure interception).
 *
 * <p>Prevents client crashes caused by broken entity rendering — including every call of
 * {@code net.minecraft.class_898.method_3950} (EntityRenderManager#shouldRender) and
 * {@code method_23839} (EntityRenderManager#getLight, used by GameRenderer#renderHand) — and by
 * broken particle creation/ticking/rendering. At the end of every resource reload it also audits
 * the monitored resources (entity renderer factories, particle sprite sets, particle factories)
 * and reports anything missing with full attribution. Core info goes to the client chat and the
 * log, and a detailed root-cause report is written to
 * {@code <game dir>/entity_render_debugger/reports/}.
 */
public class EntityRenderDebugger implements ClientModInitializer {
	public static final String MOD_ID = "entity_render_debugger";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final String MOD_VERSION = FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("?");

	@Override
	public void onInitializeClient() {
		DebugConfig config = DebugConfig.load(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json"));
		IncidentCenter.init(config);
		LOGGER.info("[ERD] initialized: {}", config);
	}
}
