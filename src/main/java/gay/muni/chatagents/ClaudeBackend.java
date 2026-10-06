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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Claude Code in print mode (claude -p), resuming one session across turns. */
final class ClaudeBackend extends Backend {
	/** Older versions say "Claude AI usage limit reached|<epoch seconds when it resets>". */
	private static final Pattern RESETS = Pattern.compile("limit reached\\|(\\d{9,})");
	/** Session id -> running total cost Claude Code reported after our last run in it. */
	private final Map<String, Double> sessionCost = new HashMap<>();

	ClaudeBackend(Agent agent, String name) {
		super(agent, name);
	}

	@Override
	String run(String prompt, String kind, List<String> players, String model, String effort) throws Exception {
		String sid = session();
		Path mcp = agent.stateDir.resolve("mcp-" + name + ".json");
		Store.write(mcp, mcpConfig());
		List<String> cmd = new ArrayList<>(List.of(harness().command, "-p",
				"--output-format", "stream-json", "--verbose",
				"--strict-mcp-config", "--mcp-config", mcp.toString(),
				"--system-prompt", agent.systemPrompt(),
				"--tools", "WebSearch,WebFetch",
				"--allowedTools", "mcp__minecraft__" + Gateway.TOOL, "WebSearch", "WebFetch",
				"--permission-mode", "dontAsk"));
		if (!model.isEmpty()) cmd.addAll(List.of("--model", model));
		if (!effort.isEmpty()) cmd.addAll(List.of("--effort", effort));
		cmd.addAll(harness().extraArgs);
		if (sid != null) cmd.addAll(List.of("--resume", sid));

		JsonObject[] result = {null};
		long[] rejectedUntil = {-1}; // set when Claude Code reports the plan limit as hit
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
				Store.write(agent.stateDir.resolve("limits-" + name + ".json"), info);
				if (str(info, "status").equals("rejected")) rejectedUntil[0] = (long) num(info, "resetsAt");
			} else if (type.equals("result")) {
				result[0] = ev;
			}
		});
		JsonObject res = result[0];
		if (res == null) {
			String why = tail(out.stderr(), 500);
			if (rejectedUntil[0] >= 0) throw new LimitException("claude is out of usage: " + why, rejectedUntil[0]);
			if (sid != null && !isLimit(why)) { // the session may be gone; start fresh once
				agent.log("resume failed on " + name + ", starting a new session: " + tail(why, 300));
				setSession(null);
				return run(prompt, kind, players, model, effort);
			}
			throw failure("claude failed (" + out.exitCode() + ")", why);
		}
		logUsage(res, kind, players, model);
		if (!str(res, "session_id").isEmpty()) setSession(str(res, "session_id"));
		if (res.get("permission_denials") instanceof JsonArray denials && !denials.isEmpty()) {
			agent.log("denied: " + denials);
		}
		if (res.has("is_error") && res.get("is_error").getAsBoolean()) {
			String why = str(res, "result");
			if (rejectedUntil[0] >= 0) throw new LimitException("claude is out of usage: " + why, rejectedUntil[0]);
			Matcher m = RESETS.matcher(why);
			if (m.find()) throw new LimitException("claude is out of usage", Long.parseLong(m.group(1)));
			throw failure("claude error", why);
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

	private void logUsage(JsonObject out, String kind, List<String> players, String model) {
		JsonObject u = out.get("usage") instanceof JsonObject o ? o : new JsonObject();
		String sid = str(out, "session_id");
		double total = num(out, "total_cost_usd");
		// total_cost_usd is a running total for the whole resumed conversation, so this run's cost is the increase.
		double cost = Math.max(total - previousCost(sid), 0);
		sessionCost.put(sid, total);
		JsonObject rec = usageRecord(kind, players, sid, model);
		rec.add("turns", out.has("num_turns") ? out.get("num_turns") : JsonNull.INSTANCE);
		rec.add("ms", out.has("duration_ms") ? out.get("duration_ms") : JsonNull.INSTANCE);
		rec.addProperty("cost_usd", Math.round(cost * 1e6) / 1e6);
		rec.addProperty("session_cost_usd", total);
		rec.addProperty("error", out.has("is_error") && out.get("is_error").getAsBoolean());
		for (String k : List.of("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")) {
			rec.addProperty(k, (long) num(u, k));
		}
		logUsage(rec);
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
