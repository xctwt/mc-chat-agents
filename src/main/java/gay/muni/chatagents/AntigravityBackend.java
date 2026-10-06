package gay.muni.chatagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import static gay.muni.chatagents.ClaudeBackend.num;
import static gay.muni.chatagents.ClaudeBackend.str;

/**
 * Antigravity CLI in print mode (agy -p), resuming one conversation across turns.
 * agy has no system prompt flag, so the prompt goes into the workspace's AGENTS.md; the console tool and the
 * permission rules it needs are merged into agy's own config files under HOME.
 */
final class AntigravityBackend extends Backend {
	private static final Map<Path, ReentrantLock> HOMES = new ConcurrentHashMap<>();
	private static final Pattern EFFORT_SUFFIX = Pattern.compile("-(minimal|low|medium|high|max)$");

	AntigravityBackend(Agent agent, String name) {
		super(agent, name);
	}

	private Path home() {
		String home = harness().home;
		return Path.of(home.isEmpty() ? System.getProperty("user.home") : home);
	}

	private void prepare() {
		Store.writeString(workdir().resolve("AGENTS.md"), agent.systemPrompt());

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
	String run(String prompt, String kind, List<String> players, String model, String effort) throws Exception {
		// The console server entry in HOME holds this agent's URL and token, so agents sharing a HOME take turns.
		ReentrantLock lock = HOMES.computeIfAbsent(home().toAbsolutePath().normalize(), h -> new ReentrantLock());
		lock.lockInterruptibly();
		try {
			return runLocked(prompt, kind, players, model, effort);
		} finally {
			lock.unlock();
		}
	}

	/** agy's model ids carry the effort (claude-sonnet-5-5-low, gemini-3.8-flash-high), so a bare model gets it appended. */
	@Override
	String modelId(String model, String effort) {
		return model.isEmpty() || effort.isEmpty() || EFFORT_SUFFIX.matcher(model).find() ? model : model + "-" + effort;
	}

	private String runLocked(String prompt, String kind, List<String> players, String model, String effort) throws Exception {
		prepare();
		String conversation = session();
		List<String> cmd = new ArrayList<>(List.of(harness().command, "--output-format", "json", "--disable-slash-commands",
				"--print-timeout", agent.hub.cfg.timing.turnTimeoutSeconds + "s"));
		if (!model.isEmpty()) cmd.addAll(List.of("--model", modelId(model, effort)));
		else if (!effort.isEmpty()) cmd.addAll(List.of("--effort", effort));
		cmd.addAll(harness().extraArgs);
		if (conversation != null) cmd.addAll(List.of("--conversation", conversation));
		cmd.addAll(List.of("-p", prompt));

		StringBuilder stdout = new StringBuilder();
		Output out = exec(cmd, null, line -> stdout.append(line).append('\n'));
		JsonObject res = parse(stdout.toString());
		if (res == null || !str(res, "status").equals("SUCCESS")) {
			String why = res == null ? tail(out.stderr() + stdout, 500) : str(res, "status") + ": " + str(res, "error");
			if (conversation != null && !isLimit(why)) { // the conversation may be gone; start fresh once
				agent.log("resume failed on " + name + ", starting a new conversation: " + why);
				setSession(null);
				return runLocked(prompt, kind, players, model, effort);
			}
			throw failure("agy failed (" + out.exitCode() + ")", why);
		}
		if (!str(res, "conversation_id").isEmpty()) setSession(str(res, "conversation_id"));
		logUsage(res, kind, players, modelId(model, effort));
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

	private void logUsage(JsonObject out, String kind, List<String> players, String model) {
		JsonObject u = out.get("usage") instanceof JsonObject o ? o : new JsonObject();
		JsonObject rec = usageRecord(kind, players, str(out, "conversation_id"), model);
		rec.addProperty("turns", (long) num(out, "num_turns"));
		rec.addProperty("ms", Math.round(num(out, "duration_seconds") * 1000));
		for (String k : List.of("input_tokens", "output_tokens", "thinking_tokens", "cache_read_tokens", "total_tokens")) {
			rec.addProperty(k, (long) num(u, k));
		}
		logUsage(rec);
	}
}
