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
	ENTITY_RENDER("entity_render") {
		@Override
		public String route(String subjectClass) {
			return "EntityRenderManager#render -> " + subjectClass + "#render";
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
