package gay.muni.chatagents;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static gay.muni.chatagents.ClaudeBackend.num;
import static gay.muni.chatagents.ClaudeBackend.str;

/**
 * Codex CLI in non-interactive mode (codex exec --json), resuming one thread across turns.
 * Everything is passed as -c overrides, so the user's ~/.codex/config.toml is never rewritten: the prompt goes in
 * as developer instructions, the console tool as an HTTP MCP server whose token comes from the environment, and
 * the shell tool is turned off.
 */
final class CodexBackend extends Backend {
	private static final String TOKEN_ENV = "CHATAGENTS_MCP_TOKEN";

	CodexBackend(Agent agent, String name) {
		super(agent, name);
	}

	@Override
	String run(String prompt, String kind, List<String> players, String model, String effort) throws Exception {
		String thread = session();
		List<String> cmd = new ArrayList<>(List.of(harness().command, "exec", "--json", "--skip-git-repo-check"));
		set(cmd, "sandbox_mode", toml("read-only"));
		set(cmd, "approval_policy", toml("never"));
		set(cmd, "features.shell_tool", "false");
		set(cmd, "web_search", toml("live"));
		set(cmd, "developer_instructions", toml(agent.systemPrompt()));
		set(cmd, "mcp_servers.minecraft.url", toml(agent.hub.gateway.url(agent.cfg.id)));
		set(cmd, "mcp_servers.minecraft.bearer_token_env_var", toml(TOKEN_ENV));
		set(cmd, "mcp_servers.minecraft.default_tools_approval_mode", toml("approve"));
		if (!model.isEmpty()) set(cmd, "model", toml(model));
		if (!effort.isEmpty()) set(cmd, "model_reasoning_effort", toml(effort));
		cmd.addAll(harness().extraArgs);
		if (thread != null) cmd.addAll(List.of("resume", thread));
		cmd.add("-"); // the prompt comes from stdin

		JsonObject[] usage = {null};
		String[] reply = {null}, threadId = {null}, error = {null};
		Output out = exec(cmd, prompt, Map.of(TOKEN_ENV, agent.token), line -> {
			JsonObject ev;
			try {
				ev = JsonParser.parseString(line).getAsJsonObject();
			} catch (RuntimeException e) {
				return;
			}
			switch (str(ev, "type")) {
				case "thread.started" -> threadId[0] = str(ev, "thread_id");
				case "item.completed" -> {
					JsonObject item = ev.get("item") instanceof JsonObject o ? o : new JsonObject();
					if (str(item, "type").equals("agent_message")) reply[0] = str(item, "text");
				}
				case "turn.completed" -> usage[0] = ev.get("usage") instanceof JsonObject o ? o : new JsonObject();
				case "turn.failed", "error" -> error[0] = message(ev);
				default -> {
				}
			}
		});
		if (usage[0] == null) {
			String why = error[0] != null ? error[0] : tail(out.stderr(), 500);
			if (thread != null && !isLimit(why)) { // the thread may be gone; start fresh once
				agent.log("resume failed on " + name + ", starting a new thread: " + why);
				setSession(null);
				return run(prompt, kind, players, model, effort);
			}
			throw failure("codex failed (" + out.exitCode() + ")", why);
		}
		if (threadId[0] != null && !threadId[0].isEmpty()) setSession(threadId[0]);
		logUsage(usage[0], threadId[0] != null ? threadId[0] : thread, kind, players, model);
		return reply[0] == null ? "" : reply[0].strip();
	}

	private static void set(List<String> cmd, String key, String value) {
		cmd.addAll(List.of("-c", key + "=" + value));
	}

	/** A TOML basic string; JSON string escapes are valid TOML ones. */
	private static String toml(String s) {
		return new JsonPrimitive(s).toString();
	}

	private static String message(JsonObject ev) {
		if (ev.get("error") instanceof JsonObject e) return str(e, "message");
		return str(ev, "message");
	}

	private void logUsage(JsonObject u, String thread, String kind, List<String> players, String model) {
		JsonObject rec = usageRecord(kind, players, thread, model);
		for (String k : List.of("input_tokens", "cached_input_tokens", "output_tokens", "reasoning_output_tokens")) {
			rec.addProperty(k, (long) num(u, k));
		}
		logUsage(rec);
	}
}
