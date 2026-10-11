package me.zcode.entity_render_debugger;

/**
 * Every interception point of the debugger. The key is used as translation suffix
 * ({@code entity_render_debugger.action.<key>}) and as report category.
 */
public enum Site {
	ENTITY_MISSING_RENDERER("entity_missing_renderer") {
		@Override
		public String route(String subjectClass) {
			return "EntityRenderManager#getRenderer(Entity) -> null (no renderer registered) <- " + subjectClass;
		}
	},
	/** {@code net.minecraft.class_898.method_3950} — EntityRenderManager#shouldRender. */
	ENTITY_SHOULD_RENDER("entity_should_render") {
		@Override
		public String route(String subjectClass) {
			return "WorldRenderer#fillEntityRenderStates -> EntityRenderManager#shouldRender (method_3950) -> "
					+ subjectClass + "#shouldRender";
		}
	},
	ENTITY_RENDER_STATE("entity_render_state") {
		@Override
		public String route(String subjectClass) {
			return "EntityRenderManager#getAndUpdateRenderState -> " + subjectClass
					+ "#getAndUpdateRenderState -> " + subjectClass + "#updateRenderState";
		}
	},
	/** {@code net.minecraft.class_898.method_23839} — EntityRenderManager#getLight. */
	ENTITY_GET_LIGHT("entity_get_light") {
		@Override
		public String route(String subjectClass) {
			return "GameRenderer#renderHand -> EntityRenderManager#getLight (method_23839) -> "
					+ subjectClass + "#getLight";
		}
	},
	ENTITY_RENDER("entity_render") {
		@Override
		public String route(String subjectClass) {
			return "EntityRenderManager#render -> " + subjectClass + "#render";
		}
	},
	/** {@code WorldRenderer#render} calling into a not-yet-created/throwing {@code SkyRendering}. */
	SKY_RENDER_STATE("sky_render_state") {
		@Override
		public String route(String subjectClass) {
			return "WorldRenderer#render -> " + subjectClass + "#updateRenderState(ClientWorld, float, Camera, SkyRenderState)";
		}
	},
	PARTICLE_CREATE("particle_create") {
		@Override
		public String route(String subjectClass) {
			return "ParticleManager#addParticle -> ParticleManager#createParticle -> ParticleFactory#createParticle -> "
					+ subjectClass;
		}
	},
	PARTICLE_TICK("particle_tick") {
		@Override
		public String route(String subjectClass) {
			return "ParticleManager#tick -> ParticleRenderer#tick -> ParticleRenderer#tickParticle -> "
					+ subjectClass + "#tick";
		}
	},
	PARTICLE_BILLBOARD_RENDER("particle_billboard_render") {
		@Override
		public String route(String subjectClass) {
			return "ParticleManager#addToBatch -> BillboardParticleRenderer#render -> " + subjectClass
					+ "#render -> BillboardParticleSubmittable#render";
		}
	},
	PARTICLE_BATCH_RENDER("particle_batch_render") {
		@Override
		public String route(String subjectClass) {
			return "ParticleManager#addToBatch -> " + subjectClass + "#render";
		}
	},
	PARTICLE_SPRITE_PROVIDER("particle_sprite_provider") {
		@Override
		public String route(String subjectClass) {
			return "ParticleSpriteManager$SimpleSpriteProvider#getSprite (sprite set empty) <- " + subjectClass;
		}
	},
	PARTICLE_NULL_SPRITE("particle_null_sprite") {
		@Override
		public String route(String subjectClass) {
			return subjectClass + " sprite == null -> fallback to minecraft:missingno";
		}
	};

	public final String key;

	Site(String key) {
		this.key = key;
	}

	public abstract String route(String subjectClass);
}
