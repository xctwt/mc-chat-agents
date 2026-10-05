package gay.muni.chatagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Claude Code in print mode (claude -p), resuming one session across turns. */
final class ClaudeBackend extends Backend {
	/** Session id -> running total cost Claude Code reported after our last run in it. */
	private final Map<String, Double> sessionCost = new HashMap<>();

	ClaudeBackend(Agent agent) {
		super(agent);
	}

	@Override
	String run(String prompt, String kind, List<String> players) throws Exception {
		String sid = session();
		Path mcp = agent.stateDir.resolve("mcp.json");
		Store.write(mcp, mcpConfig());
		List<String> cmd = new ArrayList<>(List.of(agent.cfg.command, "-p",
				"--output-format", "stream-json", "--verbose",
				"--strict-mcp-config", "--mcp-config", mcp.toString(),
				"--system-prompt", agent.systemPrompt(),
				"--tools", "WebSearch,WebFetch",
				"--allowedTools", "mcp__minecraft__" + Gateway.TOOL, "WebSearch", "WebFetch",
				"--permission-mode", "dontAsk"));
		if (!agent.cfg.model.isEmpty()) cmd.addAll(List.of("--model", agent.cfg.model));
		if (!agent.cfg.effort.isEmpty()) cmd.addAll(List.of("--effort", agent.cfg.effort));
		cmd.addAll(agent.cfg.extraArgs);
		if (sid != null) cmd.addAll(List.of("--resume", sid));

		JsonObject[] result = {null};
		Output out = exec(cmd, prompt, line -> {
			JsonObject ev;
			try {
				ev = JsonParser.parseString(line).getAsJsonObject();
			} catch (RuntimeException e) {
				return;
			}
			String type = str(ev, "type"), subtype = str(ev, "subtype");
			if (type.equals("system") && subtype.equals("status") && str(ev, "status").equals("compacting")) {
				agent.log("compacting...");
				agent.hub.say(agent, "compacting... (∪｡∪)｡｡｡zzz", null, false);
			} else if (type.equals("system") && subtype.equals("compact_boundary")) {
				JsonObject meta = ev.has("compact_metadata") ? ev.getAsJsonObject("compact_metadata") : new JsonObject();
				agent.log("compacted " + meta);
				if (meta.has("pre_tokens") && meta.has("post_tokens")) {
					agent.hub.say(agent, "compacted: " + tokens(meta.get("pre_tokens").getAsLong()) + " → "
							+ tokens(meta.get("post_tokens").getAsLong()) + " tokens (◍•ᴗ•◍)", null, false);
				}
			} else if (type.equals("rate_limit_event") && ev.get("rate_limit_info") instanceof JsonObject info) {
				// Plan limit usage for the whole account; clod-usage style tools read the latest one.
				info.addProperty("seen", System.currentTimeMillis() / 1000.0);
				Store.write(agent.stateDir.resolve("limits.json"), info);
			} else if (type.equals("result")) {
				result[0] = ev;
			}
		});
		JsonObject res = result[0];
		if (res == null) {
			if (sid != null) { // the session may be gone; start fresh once
				agent.log("resume failed, starting a new session: " + tail(out.stderr(), 300));
				setSession(null);
				return run(prompt, kind, players);
			}
			throw new RuntimeException("claude failed (" + out.exitCode() + "): " + tail(out.stderr(), 500));
		}
		logUsage(res, kind, players);
		if (!str(res, "session_id").isEmpty()) setSession(str(res, "session_id"));
		if (res.get("permission_denials") instanceof JsonArray denials && !denials.isEmpty()) {
			agent.log("denied: " + denials);
		}
		if (res.has("is_error") && res.get("is_error").getAsBoolean()) {
			throw new RuntimeException("claude error: " + str(res, "result"));
		}
		return str(res, "result").strip();
	}

	private JsonObject mcpConfig() {
		JsonObject headers = new JsonObject();
		headers.addProperty("Authorization", "Bearer " + agent.token);
		JsonObject server = new JsonObject();
		server.addProperty("type", "http");
		server.addProperty("url", agent.hub.gateway.url(agent.cfg.id));
		server.add("headers", headers);
		JsonObject servers = new JsonObject();
		servers.add("minecraft", server);
		JsonObject root = new JsonObject();
		root.add("mcpServers", servers);
		return root;
	}

	private void logUsage(JsonObject out, String kind, List<String> players) {
		JsonObject u = out.get("usage") instanceof JsonObject o ? o : new JsonObject();
		String sid = str(out, "session_id");
		double total = num(out, "total_cost_usd");
		// total_cost_usd is a running total for the whole resumed conversation, so this run's cost is the increase.
		double cost = Math.max(total - previousCost(sid), 0);
		sessionCost.put(sid, total);
		JsonObject rec = new JsonObject();
		rec.addProperty("ts", System.currentTimeMillis() / 1000.0);
		rec.addProperty("kind", kind);
		JsonArray ps = new JsonArray();
		players.forEach(ps::add);
		rec.add("players", ps);
		rec.add("turns", out.has("num_turns") ? out.get("num_turns") : JsonNull.INSTANCE);
		rec.add("ms", out.has("duration_ms") ? out.get("duration_ms") : JsonNull.INSTANCE);
		rec.addProperty("cost_usd", Math.round(cost * 1e6) / 1e6);
		rec.addProperty("session", sid);
		rec.addProperty("session_cost_usd", total);
		rec.addProperty("error", out.has("is_error") && out.get("is_error").getAsBoolean());
		for (String k : List.of("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")) {
			rec.addProperty(k, (long) num(u, k));
		}
		Store.appendLine(agent.stateDir.resolve("usage.jsonl"), rec.toString());
	}

	private double previousCost(String sid) {
		return sessionCost.computeIfAbsent(sid, s -> {
			double cost = 0;
			try {
				for (String line : java.nio.file.Files.readAllLines(agent.stateDir.resolve("usage.jsonl"))) {
					JsonObject r = JsonParser.parseString(line).getAsJsonObject();
					if (s.equals(str(r, "session"))) cost = num(r, "session_cost_usd");
				}
			} catch (Exception ignored) {
			}
			return cost;
		});
	}

	static String tokens(long n) {
		return n >= 1000 ? String.format("%.1fk", n / 1000.0) : Long.toString(n);
	}

	static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	static double num(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
	}
}
