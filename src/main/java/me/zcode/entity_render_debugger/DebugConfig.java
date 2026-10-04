package me.zcode.entity_render_debugger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DebugConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean enabled = true;
	public boolean chatReports = true;
	public boolean logStackTraces = true;
	public boolean writeReports = true;
	public long chatCooldownMs = 5000L;

	static DebugConfig load(Path path) {
		DebugConfig config = new DebugConfig();
		if (Files.isRegularFile(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				@Nullable DebugConfig loaded = GSON.fromJson(reader, DebugConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception e) {
				EntityRenderDebugger.LOGGER.warn("[ERD] failed to read config {}, using defaults", path, e);
			}
		}
		config.save(path);
		return config;
	}

	private void save(Path path) {
		if (chatCooldownMs < 0) {
			chatCooldownMs = 0;
		}
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			EntityRenderDebugger.LOGGER.warn("[ERD] failed to write config {}", path, e);
		}
	}

	@Override
	public String toString() {
		return "Config{enabled=" + enabled + ", chatReports=" + chatReports + ", logStackTraces=" + logStackTraces
				+ ", writeReports=" + writeReports + ", chatCooldownMs=" + chatCooldownMs + "}";
	}
}
