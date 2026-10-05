package gay.muni.chatagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static gay.muni.chatagents.ClaudeBackend.num;
import static gay.muni.chatagents.ClaudeBackend.str;

/**
 * Antigravity CLI in print mode (agy -p), resuming one conversation across turns.
 * agy has no system prompt flag, so the prompt goes into the workspace's AGENTS.md; the console tool and the
 * permission rules it needs are merged into agy's own config files under HOME.
 */
final class AntigravityBackend extends Backend {
	AntigravityBackend(Agent agent) {
		super(agent);
	}

	private Path home() {
		return Path.of(agent.cfg.home.isEmpty() ? System.getProperty("user.home") : agent.cfg.home);
	}

	private void prepare() {
		Store.writeString(agent.workdir.resolve("AGENTS.md"), agent.systemPrompt());

		Path mcpFile = home().resolve(".gemini/config/mcp_config.json");
		JsonObject mcp = Store.read(mcpFile, new JsonObject()) instanceof JsonObject o ? o : new JsonObject();
		JsonObject servers = mcp.get("mcpServers") instanceof JsonObject s ? s : new JsonObject();
		JsonObject headers = new JsonObject();
		headers.addProperty("Authorization", "Bearer " + agent.token);
		JsonObject server = new JsonObject();
		server.addProperty("disabled", false);
		server.add("headers", headers);
		server.addProperty("serverUrl", agent.hub.gateway.url(agent.cfg.id));
		if (!server.equals(servers.get("minecraft"))) {
			servers.add("minecraft", server);
			mcp.add("mcpServers", servers);
			Store.write(mcpFile, mcp);
		}

		// Allow the console tool, and no shell: agents only act through the console tool.
		Path settingsFile = home().resolve(".gemini/antigravity-cli/settings.json");
		JsonObject settings = Store.read(settingsFile, new JsonObject()) instanceof JsonObject o ? o : new JsonObject();
		JsonObject perms = settings.get("permissions") instanceof JsonObject p ? p : new JsonObject();
		boolean changed = addRule(perms, "allow", "mcp(minecraft/*)") | addRule(perms, "deny", "command(*)");
		if (changed) {
			settings.add("permissions", perms);
			Store.write(settingsFile, settings);
		}
	}

	private static boolean addRule(JsonObject perms, String list, String rule) {
		JsonArray rules = perms.get(list) instanceof JsonArray a ? a : new JsonArray();
		for (JsonElement r : rules) {
			if (r.isJsonPrimitive() && r.getAsString().equals(rule)) return false;
		}
		rules.add(rule);
		perms.add(list, rules);
		return true;
	}

	@Override
	String run(String prompt, String kind, List<String> players) throws Exception {
		prepare();
		String conversation = session();
		List<String> cmd = new ArrayList<>(List.of(agent.cfg.command, "--output-format", "json", "--disable-slash-commands",
				"--print-timeout", agent.hub.cfg.timing.turnTimeoutSeconds + "s"));
		if (!agent.cfg.model.isEmpty()) cmd.addAll(List.of("--model", agent.cfg.model));
		if (!agent.cfg.effort.isEmpty()) cmd.addAll(List.of("--effort", agent.cfg.effort));
		cmd.addAll(agent.cfg.extraArgs);
		if (conversation != null) cmd.addAll(List.of("--conversation", conversation));
		cmd.addAll(List.of("-p", prompt));

		StringBuilder stdout = new StringBuilder();
		Output out = exec(cmd, null, line -> stdout.append(line).append('\n'));
		JsonObject res = parse(stdout.toString());
		if (res == null || !str(res, "status").equals("SUCCESS")) {
			String why = res == null ? tail(out.stderr() + stdout, 500) : str(res, "status") + ": " + str(res, "error");
			if (conversation != null) { // the conversation may be gone; start fresh once
				agent.log("resume failed, starting a new conversation: " + why);
				setSession(null);
				return run(prompt, kind, players);
			}
			throw new RuntimeException("agy failed (" + out.exitCode() + "): " + why);
		}
		if (!str(res, "conversation_id").isEmpty()) setSession(str(res, "conversation_id"));
		logUsage(res, kind, players);
		return str(res, "response").strip();
	}

	private static JsonObject parse(String stdout) {
		try {
			return JsonParser.parseString(stdout).getAsJsonObject();
		} catch (RuntimeException e) {
			String[] lines = stdout.strip().split("\n");
			for (int i = lines.length - 1; i >= 0; i--) {
				try {
					return JsonParser.parseString(lines[i]).getAsJsonObject();
				} catch (RuntimeException ignored) {
				}
			}
			return null;
		}
	}

	private void logUsage(JsonObject out, String kind, List<String> players) {
		JsonObject u = out.get("usage") instanceof JsonObject o ? o : new JsonObject();
		JsonObject rec = new JsonObject();
		rec.addProperty("ts", System.currentTimeMillis() / 1000.0);
		rec.addProperty("kind", kind);
		JsonArray ps = new JsonArray();
		players.forEach(ps::add);
		rec.add("players", ps);
		rec.addProperty("turns", (long) num(out, "num_turns"));
		rec.addProperty("ms", Math.round(num(out, "duration_seconds") * 1000));
		rec.addProperty("session", str(out, "conversation_id"));
		for (String k : List.of("input_tokens", "output_tokens", "thinking_tokens", "cache_read_tokens", "total_tokens")) {
			rec.addProperty(k, (long) num(u, k));
		}
		Store.appendLine(agent.stateDir.resolve("usage.jsonl"), rec.toString());
	}
}
