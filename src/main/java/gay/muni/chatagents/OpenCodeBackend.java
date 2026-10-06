package gay.muni.chatagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static gay.muni.chatagents.ClaudeBackend.num;
import static gay.muni.chatagents.ClaudeBackend.str;

/**
 * opencode in non-interactive mode (opencode run --format json), resuming one session across turns.
 * The prompt, the console tool and the permissions are passed as an inline config (OPENCODE_CONFIG_CONTENT) that
 * defines a "chatagents" agent, so the user's own opencode config is never rewritten.
 */
final class OpenCodeBackend extends Backend {
	private static final String AGENT = "chatagents";

	OpenCodeBackend(Agent agent) {
		super(agent);
	}

	@Override
	String run(String prompt, String kind, List<String> players) throws Exception {
		String sid = session();
		List<String> cmd = new ArrayList<>(List.of(agent.cfg.command, "run", "--format", "json", "--agent", AGENT));
		if (!agent.cfg.model.isEmpty()) cmd.addAll(List.of("--model", agent.cfg.model));
		if (!agent.cfg.effort.isEmpty()) cmd.addAll(List.of("--variant", agent.cfg.effort));
		cmd.addAll(agent.cfg.extraArgs);
		if (sid != null) cmd.addAll(List.of("--session", sid));

		StringBuilder reply = new StringBuilder();
		JsonObject totals = new JsonObject();
		String[] sessionId = {null}, error = {null};
		boolean[] finished = {false};
		Output out = exec(cmd, prompt, Map.of("OPENCODE_CONFIG_CONTENT", config().toString()), line -> {
			JsonObject ev;
			try {
				ev = JsonParser.parseString(line).getAsJsonObject();
			} catch (RuntimeException e) {
				return;
			}
			if (!str(ev, "sessionID").isEmpty()) sessionId[0] = str(ev, "sessionID");
			JsonObject part = ev.get("part") instanceof JsonObject o ? o : new JsonObject();
			switch (str(ev, "type")) {
				case "step_start" -> reply.setLength(0); // keep only the text of the last step, the final answer
				case "text" -> reply.append(str(part, "text"));
				case "step_finish" -> {
					finished[0] = true;
					add(totals, part);
				}
				case "error" -> error[0] = ev.get("error") instanceof JsonObject e ? errorText(e) : str(ev, "error");
				default -> {
				}
			}
		});
		if (!finished[0] || error[0] != null) {
			String why = error[0] != null ? error[0] : tail(out.stderr(), 500);
			if (sid != null) { // the session may be gone; start fresh once
				agent.log("resume failed, starting a new session: " + why);
				setSession(null);
				return run(prompt, kind, players);
			}
			throw new RuntimeException("opencode failed (" + out.exitCode() + "): " + why);
		}
		if (sessionId[0] != null) setSession(sessionId[0]);
		logUsage(totals, sessionId[0], kind, players);
		return reply.toString().strip();
	}

	/** An inline opencode config: the console tool, and an agent with our prompt that can only use it and the web. */
	private JsonObject config() {
		JsonObject headers = new JsonObject();
		headers.addProperty("Authorization", "Bearer " + agent.token);
		JsonObject server = new JsonObject();
		server.addProperty("type", "remote");
		server.addProperty("url", agent.hub.gateway.url(agent.cfg.id));
		server.addProperty("enabled", true);
		server.add("headers", headers);
		JsonObject mcp = new JsonObject();
		mcp.add("minecraft", server);

		JsonObject perms = new JsonObject();
		perms.addProperty("*", "deny");
		perms.addProperty("minecraft_*", "allow");
		perms.addProperty("webfetch", "allow");
		perms.addProperty("websearch", "allow");
		JsonObject def = new JsonObject();
		def.addProperty("mode", "primary");
		def.addProperty("description", "Minecraft chat helper");
		def.addProperty("prompt", agent.systemPrompt());
		def.add("permission", perms);
		JsonObject agents = new JsonObject();
		agents.add(AGENT, def);

		JsonObject root = new JsonObject();
		root.add("mcp", mcp);
		root.add("agent", agents);
		return root;
	}

	private static String errorText(JsonObject e) {
		if (e.get("data") instanceof JsonObject d && !str(d, "message").isEmpty()) return str(d, "message");
		return !str(e, "message").isEmpty() ? str(e, "message") : str(e, "name");
	}

	/** Adds one step's tokens and cost to the turn's totals. */
	private static void add(JsonObject totals, JsonObject step) {
		JsonObject t = step.get("tokens") instanceof JsonObject o ? o : new JsonObject();
		JsonObject cache = t.get("cache") instanceof JsonObject o ? o : new JsonObject();
		bump(totals, "input_tokens", num(t, "input"));
		bump(totals, "output_tokens", num(t, "output"));
		bump(totals, "reasoning_tokens", num(t, "reasoning"));
		bump(totals, "cache_read_tokens", num(cache, "read"));
		bump(totals, "cache_write_tokens", num(cache, "write"));
		bump(totals, "cost_usd", num(step, "cost"));
	}

	private static void bump(JsonObject o, String key, double v) {
		o.addProperty(key, num(o, key) + v);
	}

	private void logUsage(JsonObject totals, String sid, String kind, List<String> players) {
		JsonObject rec = new JsonObject();
		rec.addProperty("ts", System.currentTimeMillis() / 1000.0);
		rec.addProperty("kind", kind);
		JsonArray ps = new JsonArray();
		players.forEach(ps::add);
		rec.add("players", ps);
		rec.addProperty("session", sid);
		rec.addProperty("cost_usd", Math.round(num(totals, "cost_usd") * 1e6) / 1e6);
		for (String k : List.of("input_tokens", "output_tokens", "reasoning_tokens", "cache_read_tokens", "cache_write_tokens")) {
			rec.addProperty(k, (long) num(totals, k));
		}
		Store.appendLine(agent.stateDir.resolve("usage.jsonl"), rec.toString());
	}
}
