package gay.muni.chatagents;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One agent CLI. Implementations run a single turn in the agent's persistent conversation. */
abstract class Backend {
	final Agent agent;
	private volatile Process process;

	Backend(Agent agent) {
		this.agent = agent;
	}

	static Backend create(Agent agent) {
		return switch (agent.cfg.backend) {
			case "claude" -> new ClaudeBackend(agent);
			case "antigravity" -> new AntigravityBackend(agent);
			default -> throw new IllegalArgumentException("unknown backend: " + agent.cfg.backend);
		};
	}

	/** Runs one turn and returns the agent's reply text. */
	abstract String run(String prompt, String kind, List<String> players) throws Exception;

	/** Forgets the conversation so the next turn starts fresh. */
	void reset() {
		Store.writeString(agent.stateDir.resolve("session"), "");
	}

	String session() {
		String s = Store.readString(agent.stateDir.resolve("session"));
		return s.isEmpty() ? null : s;
	}

	void setSession(String id) {
		Store.writeString(agent.stateDir.resolve("session"), id == null ? "" : id);
	}

	void kill() {
		Process p = process;
		if (p != null) p.descendants().forEach(ProcessHandle::destroyForcibly);
		if (p != null) p.destroyForcibly();
	}

	record Output(int exitCode, String stderr) {}

	/** Starts the CLI, feeds stdin, hands each stdout line to onLine, and kills it after the turn timeout. */
	Output exec(List<String> cmd, String stdin, Consumer<String> onLine) throws IOException, InterruptedException {
		List<String> full = new java.util.ArrayList<>(agent.cfg.commandPrefix);
		full.addAll(cmd);
		ProcessBuilder pb = new ProcessBuilder(full).directory(agent.workdir.toFile());
		Map<String, String> env = pb.environment();
		if (!agent.cfg.home.isEmpty()) env.put("HOME", agent.cfg.home);
		env.putAll(agent.cfg.env);
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
					ChatAgents.LOG.warn("[{}] turn timed out, killing the CLI", agent.cfg.id);
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

	static String tail(String s, int n) {
		s = s.strip();
		return s.length() <= n ? s : s.substring(s.length() - n);
	}

	Path path(String p) {
		return agent.hub.serverDir.resolve(p);
	}
}
