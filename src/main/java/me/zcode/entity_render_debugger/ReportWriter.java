package me.zcode.entity_render_debugger;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Locale;

/**
 * Writes one detailed incident report per emitted issue and derives a root-cause
 * analysis from the interception site, the subject and the throwable.
 */
final class ReportWriter {
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
	private static final DateTimeFormatter READABLE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
			.withZone(ZoneOffset.UTC);
	private static final int STACK_TRACE_MAX_CHARS = 6000;

	record Report(Path file, String relativePath, String summary) {}

	private ReportWriter() {}

	static @Nullable Report write(IncidentCenter.Incident i) {
		try {
			Path dir = FabricLoader.getInstance().getGameDir()
					.resolve(EntityRenderDebugger.MOD_ID)
					.resolve("reports");
			Files.createDirectories(dir);

			String stamp = FILE_TIME.format(Instant.now());
			String fileSafeSubject = i.subjectClass.replaceAll("[^A-Za-z0-9._-]", "_");
			Path file = dir.resolve(stamp + "_" + i.site.key + "_" + fileSafeSubject + ".txt");

			List<String> lines = new ArrayList<>();
			lines.add("================================================================================");
			lines.add("Entity Render Debugger - incident report");
			lines.add("================================================================================");
			lines.add("Time             : " + READABLE_TIME.format(Instant.now()) + " (" + stamp + ")");
			lines.add("Category         : " + i.site.key);
			lines.add("Subject class    : " + i.subjectClass);
			lines.add("Occurrence       : emitted after " + i.suppressed + " suppressed identical occurrence(s)");
			lines.add("");
			lines.add("--- Environment ----------------------------------------------------------------");
			lines.add("Minecraft        : " + modVersion("minecraft"));
			lines.add("Fabric Loader    : " + modVersion("fabricloader"));
			lines.add("This mod         : " + EntityRenderDebugger.MOD_ID + " " + EntityRenderDebugger.MOD_VERSION);
			lines.add("Java             : " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
			lines.add("OS               : " + System.getProperty("os.name") + " " + System.getProperty("os.version")
					+ " (" + System.getProperty("os.arch") + ")");
			lines.add("");
			lines.add("--- Subject details ------------------------------------------------------------");
			i.details.forEach((key, value) -> lines.add(String.format(Locale.ROOT, "%-17s: %s", key, value)));
			lines.add("");
			lines.add("--- Call route -----------------------------------------------------------------");
			lines.add(i.route());
			lines.add("");
			if (i.error != null) {
				lines.add("--- Error ----------------------------------------------------------------------");
				lines.add(stackTraceOf(i.error));
				lines.add("");
			}
			lines.add("--- Root cause analysis ---------------------------------------------------------");
			List<String> analysis = analyze(i);
			for (String paragraph : analysis) {
				lines.add(paragraph);
				lines.add("");
			}
			lines.add("--- Action taken by the debugger ------------------------------------------------");
			lines.add("The failing call was intercepted; the game continued instead of crashing. See the");
			lines.add("category description in the log/chat message for the exact fallback behaviour.");
			lines.add("================================================================================");

			Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
			return new Report(file, relativize(file), summarize(analysis, i));
		} catch (Throwable t) {
			EntityRenderDebugger.LOGGER.error("[ERD] failed to write incident report", t);
			return null;
		}
	}

	private static String summarize(List<String> analysis, IncidentCenter.Incident i) {
		StringBuilder sb = new StringBuilder();
		for (String paragraph : analysis) {
			sb.append(paragraph).append("\n\n");
		}
		String summary = sb.toString().trim();
		return summary.length() > 1500 ? summary.substring(0, 1500) + "\n..." : summary;
	}

	private static String relativize(Path file) {
		try {
			return FabricLoader.getInstance().getGameDir().relativize(file).toString();
		} catch (Throwable t) {
			return file.toString();
		}
	}

	private static String modVersion(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("?");
	}

	static String stackTraceOf(Throwable error) {
		StringWriter stringWriter = new StringWriter(2048);
		error.printStackTrace(new PrintWriter(stringWriter));
		String trace = stringWriter.toString();
		if (trace.length() > STACK_TRACE_MAX_CHARS) {
			trace = trace.substring(0, STACK_TRACE_MAX_CHARS) + "\n...";
		}
		return trace;
	}

	// ----------------------------------------------------------- analysis

	private static List<String> analyze(IncidentCenter.Incident i) {
		List<String> out = new ArrayList<>();
		@Nullable String culprit = i.error != null ? attributeMod(i.error) : null;

		switch (i.site) {
			case ENTITY_MISSING_RENDERER -> {
				out.add("The entity type '" + i.details.getOrDefault("Entity type", "?") + "' (owned by "
						+ i.details.getOrDefault("Owner mod", "?") + ") has no EntityRenderer registered on the client.");
				out.add("Vanilla behaviour: EntityRenderManager#getRenderer(Entity) returns null, and callers dereference it"
						+ " without a check. The first crash usually happens in EntityRenderManager#shouldRender"
						+ " (net.minecraft.class_898 method_3950), which executes getRenderer(entity).shouldRender(...) - a"
						+ " plain NullPointerException; getAndUpdateRenderState additionally wraps any failure into"
						+ " CrashException 'Extracting render state for an entity in world'. The debugger skipped the entity instead.");
				out.add("Likely causes (in order of probability): (1) the mod owning the entity type is not client-safe or"
						+ " never registers its renderer on the client (missing EntityRenderers.register / client entrypoint"
						+ " code); (2) the renderer registration ran after the entity was first rendered because of mod loading"
						+ " order or a broken/missing dependency of that mod; (3) a resource pack or mod removal left the world"
						+ " with entities of a type no present mod can render (e.g. entities saved by a previously installed mod).");
			}
			case ENTITY_SHOULD_RENDER -> {
				out.add("The visibility check for entity '" + i.details.getOrDefault("Entity type", "?")
						+ "' threw inside EntityRenderer#shouldRender (invoked via EntityRenderManager#shouldRender, method_3950)."
						+ " Vanilla does not guard this path, so any exception here would abort the whole frame and close the client.");
				out.add("The debugger treated the entity as invisible for this frame and continued.");
			}
			case ENTITY_RENDER_STATE -> {
				out.add("Building the EntityRenderState for entity '" + i.details.getOrDefault("Entity type", "?")
						+ "' threw (EntityRenderer#getAndUpdateRenderState / updateRenderState).");
				out.add("Vanilla wraps every failure of this path into CrashException 'Extracting render state for an entity"
						+ " in world'. The debugger substituted a blank render state; all consumers of the blank state are guarded"
						+ " as well, so the entity is simply not rendered.");
			}
			case ENTITY_RENDER -> {
				out.add("Rendering entity '" + i.details.getOrDefault("Entity type", "?") + "' threw inside EntityRenderer#render"
						+ " (via EntityRenderManager#render). Vanilla wraps every failure of this path into CrashException"
						+ " 'Rendering entity in world'. The debugger skipped the entity for this frame.");
			}
			case ENTITY_RENDERER_REGISTRATION -> {
				out.add("Building the renderer for entity type '" + i.details.getOrDefault("Entity type", "?")
						+ "' via factory " + i.subjectClass + " threw while the resource reload rebuilt the renderer"
						+ " maps (EntityRendererFactories#reloadEntityRenderers / #reloadPlayerRenderers).");
				out.add("Vanilla wraps this into IllegalArgumentException 'Failed to create model for <type>' which fails"
						+ " the whole resource reload and closes the game. The debugger skipped only this renderer and"
						+ " let the reload finish; instances of the type are handled by the runtime guards (invisible,"
						+ " light 0) instead of crashing.");
				out.add("Likely causes: the owning mod's renderer is incompatible with this Minecraft/modpack version"
						+ " (outdated update, binary incompatibility, missing client dependency) or its model/textures"
						+ " initialization is broken - the trace and the attribution below identify the mod.");
			}
			case ENTITY_RENDERER_AUDIT -> {
				out.add("Post-reload audit: the freshly rebuilt renderer map does not cover every entity type in the"
						+ " registry (the per-type list with owner mods is in the subject section above).");
				out.add("Types 'without factory': the owner mod never registered a client renderer"
						+ " (EntityRendererFactories.register or its client entrypoint is missing/broken) - the first"
						+ " rendered instance would NPE in vanilla; the debugger guards handle it instead.");
				out.add("Types 'with failed factory': the factory threw while building - each of them also has its own"
						+ " entity_renderer_registration report containing the full stack trace.");
			}
			case PARTICLE_SPRITES_RELOAD -> {
				out.add("Post-reload audit of the particle sprite resources; the exact finding is in the subject section"
						+ " above. Possible findings: (a) textures referenced by a particles/<id>.json texture list are"
						+ " missing from the freshly stitched particle atlas; (b) a texture list json parsed to an empty"
						+ " list; (c) sprite sets requested by factories with no particles/<id>.json in any resource"
						+ " pack; (d) modded particle types without a client factory.");
				out.add("Vanilla logs at most one 'Missing particle sprites: ...' warning for (a) and nothing for (b)-(d);"
						+ " unloaded providers would NPE on the first particle spawn (guarded by this mod at runtime),"
						+ " and missing factories make the particle type silently render nothing.");
				out.add("Likely causes: a broken/outdated resource pack or mod removed or renamed texture files or"
						+ " particle jsons, or the owning mod forgot its client particle registration.");
			}
			case SKY_RENDER_STATE -> {
				out.add("WorldRenderer#render calls this.skyRendering.updateRenderState(world, tickDelta, camera, skyRenderState)"
						+ " without a null check, but the SkyRendering instance is only created inside WorldRenderer#reload -"
						+ " not in the WorldRenderer constructor. A frame rendered before reload() has finished"
						+ " (joining a world during a resource reload, a failed/slow reload, or a mod interfering with the"
						+ " reload cycle) therefore crashes with: NullPointerException: Cannot invoke"
						+ " \"net.minecraft.class_9975.method_74926(...)\" because \"this.field_53075\" is null.");
				out.add("This is a vanilla 1.21.9+ issue (widely reported on 1.21.11). The debugger skipped the sky state"
						+ " update for this frame; the sky render pass itself is null-guarded by vanilla (WorldRenderer#renderSky"
						+ " returns early when the field is still null) and simply does not draw until the reload completes.");
			}
			case PARTICLE_CREATE -> out.add("A particle factory threw while creating a particle"
					+ " (ParticleManager#createParticle -> ParticleFactory#createParticle). Vanilla crashes with"
					+ " 'Unable to create particle'; the debugger skipped the particle instead.");
			case PARTICLE_TICK -> out.add("A particle threw while ticking (ParticleRenderer#tickParticle). Vanilla crashes with"
					+ " 'Ticking Particle'; the debugger removed the offending particle instead.");
			case PARTICLE_BILLBOARD_RENDER -> out.add("A billboard particle threw while rendering. Vanilla crashes with"
					+ " 'Rendering Particle'; the debugger removed the offending particle instead.");
			case PARTICLE_BATCH_RENDER -> out.add("A whole ParticleRenderer (texture sheet) threw while building the render batch."
					+ " The debugger replaced the batch with an empty one instead of crashing.");
			case PARTICLE_SPRITE_PROVIDER -> {
				out.add("The particle's sprite set (ParticleSpriteManager$SimpleSpriteProvider#sprites) is empty or was never"
						+ " loaded, and any further use would NPE. The debugger fell back to the atlas' missingno sprite.");
				out.add("Likely causes: the particle texture list json (assets/<namespace>/particles/<particle id>.json) is"
						+ " missing, failed to parse, or its texture files were removed by the active resource pack.");
			}
			case PARTICLE_NULL_SPRITE -> {
				out.add("A BillboardParticle ended up with a null sprite (never reloaded, resource pack removed the texture,"
						+ " or a buggy factory) and would NPE on the first UV access. The debugger swapped in the missingno sprite.");
			}
		}

		if (i.error != null) {
			classifyThrowable(out, i.error);
		}
		if (culprit != null) {
			out.add("Attribution: stack frames outside vanilla Minecraft point to " + culprit
					+ " - the bug most likely lives in that mod (or in a mod it depends on).");
		}
		return out;
	}

	private static void classifyThrowable(List<String> out, Throwable error) {
		String name = error.getClass().getName();
		@Nullable String message = error.getMessage();

		if (error instanceof StackOverflowError) {
			out.add("Diagnosis: StackOverflowError - infinite/very deep recursion in rendering code, typically a renderer"
					+ " re-entering itself (e.g. rendering the entity it is currently rendering) or a broken recursive model.");
		} else if (error instanceof OutOfMemoryError) {
			out.add("Diagnosis: OutOfMemoryError - the client ran out of heap while rendering. Consider increasing the JVM"
					+ " -Xmx value; the failing allocation in the trace points at the resource that grew unbounded.");
		} else if (error instanceof NoClassDefFoundError || name.equals("ClassNotFoundException")) {
			out.add("Diagnosis: a class could not be loaded (" + (message != null ? message : name)
					+ ") - a mod dependency is missing, outdated, or an optional integration was compiled against a class"
					+ " this modpack does not provide. Check the mods owning the packages named in the trace.");
		} else if (error instanceof NoSuchMethodError || error instanceof NoSuchFieldError
				|| error instanceof AbstractMethodError || error instanceof IncompatibleClassChangeError
				|| error instanceof IllegalAccessError) {
			out.add("Diagnosis: binary incompatibility (LinkageError) - a mod was compiled against a different Minecraft"
					+ " or mod-API version than the one running here, so a method/field it calls no longer exists."
					+ " Update or remove the mod identified in the attribution line below the trace.");
		} else if (error instanceof NullPointerException) {
			out.add("Diagnosis: NullPointerException - something in the rendering path was not initialized."
					+ " Read the first non-vanilla frame of the trace for the guilty field; on the particle sites this is"
					+ " frequently an unloaded/missing sprite, on the entity sites an uninitialized renderer field.");
		} else if (error instanceof ClassCastException) {
			out.add("Diagnosis: ClassCastException - two mods (or a mod and vanilla) disagree about a class or render-state"
					+ " type; typical for mixin-based mods that assume their own subclass everywhere.");
		} else if (error instanceof ConcurrentModificationException) {
			out.add("Diagnosis: ConcurrentModificationException - a collection was mutated while being iterated on another"
					+ " thread; the involved mod does particle/entity bookkeeping without synchronization.");
		} else if (error instanceof IndexOutOfBoundsException) {
			out.add("Diagnosis: IndexOutOfBoundsException - a renderer indexed past the end of a list/array (stale model"
					+ " data, sprite strip too small, or a bad random-seed based lookup).");
		} else if (error instanceof ArithmeticException) {
			out.add("Diagnosis: ArithmeticException - a division by zero or bad modulo in the rendering math"
					+ " (e.g. sprite strip math with maxAge=0).");
		} else if (message != null && message.contains("Cannot get location of")) {
			out.add("Diagnosis: a sprite/atlas lookup failed - a texture referenced by a particle or entity renderer is"
					+ " missing from the active resource packs.");
		} else {
			out.add("Diagnosis: " + name + (message != null ? " (" + message + ")" : "")
					+ " - an unclassified failure; the trace below shows the exact failing frame.");
		}
	}

	/**
	 * {@return a human-readable description of the mod owning the first non-vanilla stack frame,
	 * or the bare package name when it cannot be mapped to a mod}
	 */
	private static @Nullable String attributeMod(Throwable error) {
		try {
			for (StackTraceElement frame : error.getStackTrace()) {
				String className = frame.getClassName();
				if (isVanillaOrInfra(className)) {
					continue;
				}
				String viaCodeSource = modForClass(className);
				if (viaCodeSource != null) {
					return viaCodeSource;
				}
				int lastDot = className.lastIndexOf('.');
				return lastDot > 0 ? "package '" + className.substring(0, lastDot) + "' (no mod matched)" : ("class '" + className + "'");
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	private static boolean isVanillaOrInfra(String className) {
		return className.startsWith("net.minecraft.")
				|| className.startsWith("com.mojang.")
				|| className.startsWith("java.")
				|| className.startsWith("javax.")
				|| className.startsWith("jdk.")
				|| className.startsWith("sun.")
				|| className.startsWith("org.spongepowered.")
				|| className.startsWith("org.objectweb.asm.")
				|| className.startsWith("me.zcode.entity_render_debugger.")
				|| className.startsWith("it.unimi.dsi.fastutil.")
				|| className.startsWith("com.google.")
				|| className.startsWith("org.slf4j.")
				|| className.startsWith("org.apache.");
	}

	private static @Nullable String modForClass(String className) {
		try {
			Class<?> clazz = Class.forName(className, false, ReportWriter.class.getClassLoader());
			if (clazz.getProtectionDomain() != null && clazz.getProtectionDomain().getCodeSource() != null) {
				URL location = clazz.getProtectionDomain().getCodeSource().getLocation();
				if (location != null && "file".equals(location.getProtocol())) {
					Path classPath = Path.of(location.toURI());
					for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
						for (Path root : container.getRootPaths()) {
							if (classPath.startsWith(root.toAbsolutePath().normalize())) {
								return container.getMetadata().getName() + " (" + container.getMetadata().getId() + ")";
							}
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}
		return null;
	}
}
