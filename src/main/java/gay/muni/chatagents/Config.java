package gay.muni.chatagents;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** config/chatagents/config.json. Toggling an agent in game rewrites this file, so edits made by hand survive. */
public class Config {
	static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** IANA zone for the local time shown to agents, e.g. "Asia/Almaty". */
	public String timezone = "UTC";
	/** How the local time is labelled in prompts, e.g. "Astana time". */
	public String timezoneLabel = "local time";
	/** The console gateway agents call through MCP. Port 0 picks a free port at startup. */
	public String gatewayHost = "127.0.0.1";
	public int gatewayPort = 0;
	/** Seen players, reminders, sessions and usage logs. Relative paths are relative to the server directory. */
	public String stateDir = "config/chatagents/state";
	public Timing timing = new Timing();
	public Policy policy = new Policy();
	/** The CLIs agents can run on, by name. */
	public Map<String, Harness> harnesses = new LinkedHashMap<>();
	/** Optional model aliases, so one name works in every harness: alias -> harness name -> that harness's model id. */
	public Map<String, Map<String, String>> models = new LinkedHashMap<>();
	public List<AgentConfig> agents = new ArrayList<>();

	public static class Timing {
		/** Per player, for messages that mention an agent. */
		public int cooldownSeconds = 8;
		/** After an agent replies to someone, their next messages reach it without its name. */
		public int conversationSeconds = 90;
		/** Window for players named in [[await:...]]. */
		public int awaitSeconds = 120;
		/** Minimum gap between reactions to deaths/advancements, server-wide... */
		public int eventCooldownSeconds = 30;
		/** ...and per player. */
		public int playerEventCooldownSeconds = 90;
		/** Give players a moment after joining (and logging in) before reminders and greetings. */
		public int joinDelaySeconds = 15;
		public int turnTimeoutSeconds = 300;
		public int maxReplyChars = 400;
		public int maxReminderMinutes = 7 * 24 * 60;
		public int maxRemindersPerPlayer = 10;
		/** How long a route that ran out of usage is skipped, when the CLI doesn't say when the limit resets. */
		public int limitCooldownMinutes = 60;
	}

	/** What the console tool lets an agent run. Rules match a command prefix on word boundaries. */
	public static class Policy {
		/** Turns from regular players and server events: only these. */
		public List<String> playerAllow = new ArrayList<>(List.of("time query", "time add", "list"));
		/** Turns from operators: anything except these (also after "execute ... run"). */
		public List<String> operatorDeny = new ArrayList<>(List.of(
				"stop", "op", "deop", "save-off", "reload", "ban-ip", "pardon-ip", "time set", "chatagents"));
	}

	/** A CLI agents can run on, with its own login. Two harnesses can share a CLI with different logins (homes). */
	public static class Harness {
		/** "claude" (Claude Code), "antigravity" (Antigravity CLI), "codex" (Codex CLI) or "opencode" (opencode). */
		public String backend;
		/** Path to the CLI binary. */
		public String command;
		/** Put in front of the command, e.g. ["sudo", "-n", "-u", "mcbot", "--"] to run the CLI as another user. */
		public List<String> commandPrefix = new ArrayList<>();
		/** HOME for the CLI (where its login lives). Empty keeps the server's. */
		public String home = "";
		public List<String> extraArgs = new ArrayList<>();
		public Map<String, String> env = new LinkedHashMap<>();
	}

	/** One way to run an agent: a harness and a model. */
	public static class Route {
		/** A key in harnesses. */
		public String harness;
		/** A model id for that harness, or an alias from models. Empty uses the harness's default. */
		public String model = "";
		public String effort = "";

		Route() {}

		Route(String harness, String model, String effort) {
			this.harness = harness;
			this.model = model;
			this.effort = effort;
		}

		String key() {
			return model.isEmpty() ? harness : harness + "/" + model;
		}
	}

	public static class AgentConfig {
		/** Also the name of its in-game command, e.g. /clod on. */
		public String id;
		public String displayName;
		/** A Minecraft color name for the name tag in chat. */
		public String color = "gold";
		public boolean enabled = false;
		/** Words that address this agent, matched case-insensitively at the start of a word. */
		public List<String> triggers = new ArrayList<>();
		/** Harness + model pairs in order of preference; turns use the first one that isn't out of usage. */
		public List<Route> routes = new ArrayList<>();
		/** When a route fails, retry the turn on the next one. Usage and rate limits also bench the route for a while. */
		public boolean failover = true;
		/** Say in chat when the agent moves to another route because one ran out of usage. */
		public boolean announceSwitches = true;
		/** Working directory for the CLIs (each harness gets a subdirectory); sessions are tied to it. */
		public String workdir;
		/** File in config/chatagents/prompts. */
		public String systemPrompt;
		/** React to deaths and advancements (only the first enabled agent with this on does). */
		public boolean events = true;
		/** Greet first-time players (only the first enabled agent with this on does). */
		public boolean greetNewcomers = true;

		// Before harnesses and routes, an agent had exactly one CLI. Configs like that are converted on load.
		public String backend, command, home, model, effort;
		public List<String> commandPrefix, extraArgs;
		public Map<String, String> env;
	}

	static Config defaults() {
		Config c = new Config();
		String bin = System.getProperty("user.home") + "/.local/bin/";
		Harness claude = harness("claude", bin + "claude");
		claude.extraArgs = new ArrayList<>(List.of("--max-turns", "12"));
		claude.env.put("ENABLE_CLAUDEAI_MCP_SERVERS", "false");
		c.harnesses.put("claude", claude);
		c.harnesses.put("agy", harness("antigravity", bin + "agy"));
		c.harnesses.put("codex", harness("codex", bin + "codex"));
		c.harnesses.put("opencode", harness("opencode", System.getProperty("user.home") + "/.opencode/bin/opencode"));
		Map<String, String> sonnet = new LinkedHashMap<>();
		sonnet.put("claude", "claude-sonnet-5-5");
		sonnet.put("opencode", "anthropic/claude-sonnet-5-5");
		c.models.put("sonnet", sonnet);

		c.agents.add(agent("clod", "gold", List.of("claude", "clod"), new Route("claude", "sonnet", "low")));
		c.agents.add(agent("agy", "aqua", List.of("agy", "antigravity", "gemini"), new Route("agy", "", "low")));
		c.agents.add(agent("codex", "green", List.of("codex", "gpt"), new Route("codex", "", "low")));
		// opencode's effort is a model variant, and variant names depend on the provider.
		c.agents.add(agent("opencode", "light_purple", List.of("opencode"), new Route("opencode", "", "")));
		return c;
	}

	private static Harness harness(String backend, String command) {
		Harness h = new Harness();
		h.backend = backend;
		h.command = command;
		return h;
	}

	private static AgentConfig agent(String id, String color, List<String> triggers, Route route) {
		AgentConfig a = new AgentConfig();
		a.id = id;
		a.displayName = id;
		a.color = color;
		a.triggers = new ArrayList<>(triggers);
		a.routes = new ArrayList<>(List.of(route));
		a.workdir = "config/chatagents/work/" + id;
		a.systemPrompt = id + ".md";
		return a;
	}

	/** The model id a route's harness wants: an alias from models, or the route's model as written. */
	String modelFor(Route r) {
		Map<String, String> alias = models.get(r.model);
		return alias != null && alias.containsKey(r.harness) ? alias.get(r.harness) : r.model;
	}

	static Config load(Path file) throws IOException {
		if (!Files.exists(file)) {
			Config c = defaults();
			c.save(file);
			return c;
		}
		Config c = GSON.fromJson(Files.readString(file), Config.class);
		if (c.timing == null) c.timing = new Timing();
		if (c.policy == null) c.policy = new Policy();
		if (c.agents == null) c.agents = new ArrayList<>();
		if (c.harnesses == null) c.harnesses = new LinkedHashMap<>();
		if (c.models == null) c.models = new LinkedHashMap<>();
		boolean migrated = false;
		for (AgentConfig a : c.agents) {
			if (a.id == null) throw new IOException("every agent needs an id");
			if (a.routes == null) a.routes = new ArrayList<>();
			if (a.routes.isEmpty() && a.backend != null) {
				migrate(c, a);
				migrated = true;
			}
			if (a.routes.isEmpty()) throw new IOException("agent " + a.id + " needs at least one route");
			for (Route r : a.routes) {
				if (r.harness == null || !c.harnesses.containsKey(r.harness)) {
					throw new IOException("agent " + a.id + " uses unknown harness " + r.harness);
				}
				if (r.model == null) r.model = "";
				if (r.effort == null) r.effort = "";
			}
			if (a.displayName == null) a.displayName = a.id;
			if (a.workdir == null) a.workdir = "config/chatagents/work/" + a.id;
			if (a.systemPrompt == null) a.systemPrompt = a.id + ".md";
			if (a.triggers == null) a.triggers = new ArrayList<>(List.of(a.id));
		}
		for (Map.Entry<String, Harness> e : c.harnesses.entrySet()) {
			Harness h = e.getValue();
			if (h.backend == null || h.command == null) throw new IOException("harness " + e.getKey() + " needs backend and command");
			if (h.home == null) h.home = "";
			if (h.extraArgs == null) h.extraArgs = new ArrayList<>();
			if (h.env == null) h.env = new LinkedHashMap<>();
			if (h.commandPrefix == null) h.commandPrefix = new ArrayList<>();
		}
		if (migrated) c.save(file);
		return c;
	}

	/** Turns an old single-CLI agent into a harness (shared if an identical one exists) and one route. */
	private static void migrate(Config c, AgentConfig a) throws IOException {
		if (a.command == null) throw new IOException("agent " + a.id + " needs a command");
		Harness h = harness(a.backend, a.command);
		if (a.home != null) h.home = a.home;
		if (a.extraArgs != null) h.extraArgs = a.extraArgs;
		if (a.env != null) h.env = a.env;
		if (a.commandPrefix != null) h.commandPrefix = a.commandPrefix;
		String name = a.backend;
		Harness same = c.harnesses.get(name);
		if (same != null && !GSON.toJsonTree(same).equals(GSON.toJsonTree(h))) name = a.id;
		c.harnesses.putIfAbsent(name, h);
		a.routes.add(new Route(name, a.model == null ? "" : a.model, a.effort == null ? "low" : a.effort));
		a.backend = a.command = a.home = a.model = a.effort = null;
		a.commandPrefix = a.extraArgs = null;
		a.env = null;
	}

	synchronized void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(this) + "\n");
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
				java.nio.file.StandardCopyOption.ATOMIC_MOVE);
	}
}
