package gay.muni.chatagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * A minimal MCP server (streamable HTTP transport, JSON responses only) on localhost with one tool, `console`.
 * Each agent has its own URL and bearer token, and the console tool only works while that agent is in a turn,
 * with the permissions of whoever started the turn.
 */
final class Gateway {
	static final String TOOL = "console";
	private static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

	private final Hub hub;
	private HttpServer http;

	Gateway(Hub hub) {
		this.hub = hub;
	}

	void start(String host, int port) throws IOException {
		http = HttpServer.create(new InetSocketAddress(host, port), 0);
		http.createContext("/mcp/", this::handle);
		http.setExecutor(Executors.newFixedThreadPool(4, r -> {
			Thread t = new Thread(r, "chatagents-gateway");
			t.setDaemon(true);
			return t;
		}));
		http.start();
	}

	void stop() {
		if (http != null) http.stop(0);
	}

	String url(String agentId) {
		InetSocketAddress a = http.getAddress();
		return "http://" + a.getHostString() + ":" + a.getPort() + "/mcp/" + agentId;
	}

	private void handle(HttpExchange ex) throws IOException {
		try {
			Agent agent = hub.agent(ex.getRequestURI().getPath().substring("/mcp/".length()));
			String auth = ex.getRequestHeaders().getFirst("Authorization");
			if (agent == null || auth == null || !MessageDigest.isEqual(
					auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + agent.token).getBytes(StandardCharsets.UTF_8))) {
				send(ex, 401, null);
				return;
			}
			if (!ex.getRequestMethod().equals("POST")) {
				send(ex, 405, null);
				return;
			}
			JsonElement body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			if (body.isJsonArray()) {
				JsonArray replies = new JsonArray();
				for (JsonElement m : body.getAsJsonArray()) {
					JsonObject r = respond(agent, m.getAsJsonObject());
					if (r != null) replies.add(r);
				}
				send(ex, replies.isEmpty() ? 202 : 200, replies.isEmpty() ? null : replies);
			} else {
				JsonObject r = respond(agent, body.getAsJsonObject());
				send(ex, r == null ? 202 : 200, r);
			}
		} catch (RuntimeException e) {
			ChatAgents.LOG.warn("gateway error", e);
			send(ex, 400, null);
		} finally {
			ex.close();
		}
	}

	private JsonObject respond(Agent agent, JsonObject msg) {
		if (!msg.has("id")) return null; // a notification
		String method = msg.has("method") ? msg.get("method").getAsString() : "";
		JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
		JsonObject result = new JsonObject();
		switch (method) {
			case "initialize" -> {
				String asked = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "";
				result.addProperty("protocolVersion", VERSIONS.contains(asked) ? asked : VERSIONS.getFirst());
				JsonObject caps = new JsonObject();
				caps.add("tools", new JsonObject());
				result.add("capabilities", caps);
				JsonObject info = new JsonObject();
				info.addProperty("name", "minecraft");
				info.addProperty("version", ChatAgents.VERSION);
				result.add("serverInfo", info);
			}
			case "ping" -> {
			}
			case "tools/list" -> {
				JsonArray tools = new JsonArray();
				tools.add(toolSchema());
				result.add("tools", tools);
			}
			case "tools/call" -> {
				String name = params.has("name") ? params.get("name").getAsString() : "";
				JsonObject args = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
				if (!name.equals(TOOL) || !args.has("command")) {
					return error(msg, -32602, "unknown tool or missing command");
				}
				Hub.ToolResult r = hub.runConsole(agent, args.get("command").getAsString());
				JsonArray content = new JsonArray();
				JsonObject text = new JsonObject();
				text.addProperty("type", "text");
				text.addProperty("text", r.text());
				content.add(text);
				result.add("content", content);
				result.addProperty("isError", r.error());
			}
			default -> {
				return error(msg, -32601, "method not found: " + method);
			}
		}
		JsonObject reply = new JsonObject();
		reply.addProperty("jsonrpc", "2.0");
		reply.add("id", msg.get("id"));
		reply.add("result", result);
		return reply;
	}

	private static JsonObject toolSchema() {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", TOOL);
		tool.addProperty("description", "Run one Minecraft server console command (no leading slash needed; WorldEdit "
				+ "commands keep their double slash, e.g. //set stone) and return what the console printed. "
				+ "What you may run depends on who asked you this turn.");
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject props = new JsonObject();
		JsonObject command = new JsonObject();
		command.addProperty("type", "string");
		command.addProperty("description", "The command, e.g. time query time");
		props.add("command", command);
		schema.add("properties", props);
		JsonArray required = new JsonArray();
		required.add("command");
		schema.add("required", required);
		tool.add("inputSchema", schema);
		return tool;
	}

	private static JsonObject error(JsonObject msg, int code, String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		JsonObject reply = new JsonObject();
		reply.addProperty("jsonrpc", "2.0");
		reply.add("id", msg.get("id"));
		reply.add("error", err);
		return reply;
	}

	private static void send(HttpExchange ex, int status, JsonElement body) throws IOException {
		if (body == null) {
			ex.sendResponseHeaders(status, -1);
			return;
		}
		byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		ex.getResponseBody().write(bytes);
	}
}
