package gay.muni.chatagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * One harness (agent CLI) for one agent. Implementations run a single turn in the agent's persistent conversation
 * in that harness; each harness keeps its own session and working directory.
 */
abstract class Backend {
	/** Error text that means a usage, quota or rate limit ran out rather than something being broken. */
	private static final Pattern LIMIT = Pattern.compile("usage limit|rate.?limit|limit reached|hit your limit|quota"
			+ "|resource.?exhausted|too many requests|\\b429\\b|credit balance|out of credits", Pattern.CASE_INSENSITIVE);

	final Agent agent;
	/** The harness's key in the config. */
	final String name;
	private volatile Process process;

	Backend(Agent agent, String name) {
		this.agent = agent;
		this.name = name;
	}

	static Backend create(Agent agent, String name, String backend) {
		return switch (backend) {
			case "claude" -> new ClaudeBackend(agent, name);
			case "antigravity" -> new AntigravityBackend(agent, name);
			case "codex" -> new CodexBackend(agent, name);
			case "opencode" -> new OpenCodeBackend(agent, name);
			default -> throw new IllegalArgumentException("unknown backend: " + backend);
		};
	}

	/** Runs one turn and returns the agent's reply text. model is already resolved for this harness. */
	abstract String run(String prompt, String kind, List<String> players, String model, String effort) throws Exception;

	/** A turn failed because a usage, quota or rate limit ran out. until: epoch seconds when it resets, 0 if unknown. */
	static final class LimitException extends RuntimeException {
		final long until;

		LimitException(String message, long until) {
			super(message);
			this.until = until;
		}
	}

	/** Whether error text means a usage limit ran out: the built-in patterns, or one from the config's limitPatterns. */
	boolean isLimit(String why) {
		if (LIMIT.matcher(why).find()) return true;
		for (String p : agent.hub.cfg.limitPatterns) {
			if (Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(why).find()) return true;
		}
		return false;
	}

	/** The exception for a failed turn: a LimitException if the error looks like a usage limit. */
	RuntimeException failure(String what, String why) {
		return isLimit(why) ? new LimitException(what + ": " + why, 0) : new RuntimeException(what + ": " + why);
	}

	/** The model id this harness is actually given for a route's (alias-resolved) model and effort. */
	String modelId(String model, String effort) {
		return model;
	}

	Config.Harness harness() {
		Config.Harness h = agent.hub.cfg.harnesses.get(name);
		if (h == null) throw new IllegalStateException("harness " + name + " is no longer in the config");
		return h;
	}

	Path workdir() {
		return agent.workdir.resolve(name);
	}

	/** Forgets the conversation so the next turn starts fresh. */
	void reset() {
		Store.writeString(sessionFile(), "");
	}

	String session() {
		String s = Store.readString(sessionFile());
		return s.isEmpty() ? null : s;
	}

	void setSession(String id) {
		Store.writeString(sessionFile(), id == null ? "" : id);
	}

	private Path sessionFile() {
		return agent.stateDir.resolve("session-" + name);
	}

	void kill() {
		Process p = process;
		if (p != null) p.descendants().forEach(ProcessHandle::destroyForcibly);
		if (p != null) p.destroyForcibly();
	}

	record Output(int exitCode, String stderr) {}

	Output exec(List<String> cmd, String stdin, Consumer<String> onLine) throws IOException, InterruptedException {
		return exec(cmd, stdin, Map.of(), onLine);
	}

	/** Starts the CLI, feeds stdin, hands each stdout line to onLine, and kills it after the turn timeout. */
	Output exec(List<String> cmd, String stdin, Map<String, String> extraEnv, Consumer<String> onLine)
			throws IOException, InterruptedException {
		Config.Harness h = harness();
		List<String> full = new java.util.ArrayList<>(h.commandPrefix);
		full.addAll(cmd);
		Files.createDirectories(workdir());
		ProcessBuilder pb = new ProcessBuilder(full).directory(workdir().toFile());
		Map<String, String> env = pb.environment();
		if (!h.home.isEmpty()) env.put("HOME", h.home);
		env.putAll(h.env);
		env.putAll(extraEnv);
		Process p = pb.start();
		process = p;
		StringBuilder err = new StringBuilder();
		Thread errReader = Thread.ofVirtual().start(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					synchronized (err) {
						if (err.length() < 20000) err.append(line).append('\n');
					}
				}
			} catch (IOException ignored) {
			}
		});
		Thread killer = Thread.ofVirtual().start(() -> {
			try {
				if (!p.waitFor(agent.hub.cfg.timing.turnTimeoutSeconds, TimeUnit.SECONDS)) {
					ChatAgents.LOG.warn("[{}] turn timed out on {}, killing the CLI", agent.cfg.id, name);
					kill();
				}
			} catch (InterruptedException ignored) {
			}
		});
		try {
			try (var w = p.outputWriter(StandardCharsets.UTF_8)) {
				if (stdin != null) w.write(stdin);
			}
			try (BufferedReader r = p.inputReader(StandardCharsets.UTF_8)) {
				String line;
				while ((line = r.readLine()) != null) onLine.accept(line);
			}
			int code = p.waitFor();
			errReader.join(2000);
			synchronized (err) {
				return new Output(code, err.toString());
			}
		} finally {
			killer.interrupt();
			process = null;
		}
	}

	/** The fields every usage.jsonl record has; backends add their own token counts. */
	JsonObject usageRecord(String kind, List<String> players, String session, String model) {
		JsonObject rec = new JsonObject();
		rec.addProperty("ts", System.currentTimeMillis() / 1000.0);
		rec.addProperty("kind", kind);
		JsonArray ps = new JsonArray();
		players.forEach(ps::add);
		rec.add("players", ps);
		rec.addProperty("harness", name);
		rec.addProperty("model", model);
		rec.addProperty("session", session);
		return rec;
	}

	void logUsage(JsonObject rec) {
		Store.appendLine(agent.stateDir.resolve("usage.jsonl"), rec.toString());
	}

	static String tail(String s, int n) {
		s = s.strip();
		return s.length() <= n ? s : s.substring(s.length() - n);
	}
}
