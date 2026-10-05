package gay.muni.chatagents;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Small JSON file helpers: atomic writes, and reads that fall back to a default. */
final class Store {
	private Store() {}

	static JsonElement read(Path file, JsonElement fallback) {
		try {
			return JsonParser.parseString(Files.readString(file));
		} catch (IOException | RuntimeException e) {
			return fallback;
		}
	}

	static void write(Path file, JsonElement data) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, Config.GSON.toJson(data));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			ChatAgents.LOG.warn("could not write {}: {}", file, e.toString());
		}
	}

	static String readString(Path file) {
		try {
			return Files.readString(file).strip();
		} catch (IOException e) {
			return "";
		}
	}

	static void writeString(Path file, String s) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, s);
		} catch (IOException e) {
			ChatAgents.LOG.warn("could not write {}: {}", file, e.toString());
		}
	}

	static void appendLine(Path file, String line) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			ChatAgents.LOG.warn("could not append to {}: {}", file, e.toString());
		}
	}
}
