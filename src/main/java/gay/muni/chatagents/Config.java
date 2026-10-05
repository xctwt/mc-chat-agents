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
	}

	/** What the console tool lets an agent run. Rules match a command prefix on word boundaries. */
	public static class Policy {
		/** Turns from regular players and server events: only these. */
		public List<String> playerAllow = new ArrayList<>(List.of("time query", "time add", "list"));
		/** Turns from operators: anything except these (also after "execute ... run"). */
		public List<String> operatorDeny = new ArrayList<>(List.of(
				"stop", "op", "deop", "save-off", "reload", "ban-ip", "pardon-ip", "time set", "chatagents"));
	}

	public static class AgentConfig {
		/** Also the name of its in-game command, e.g. /clod on. */
		public String id;
		public String displayName;
		/** A Minecraft color name for the name tag in chat. */
		public String color = "gold";
		/** "claude" (Claude Code) or "antigravity" (Antigravity CLI). */
		public String backend;
		public boolean enabled = false;
		/** Words that address this agent, matched case-insensitively at the start of a word. */
		public List<String> triggers = new ArrayList<>();
		/** Path to the CLI binary. */
		public String command;
		/** Put in front of the command, e.g. ["sudo", "-n", "-u", "mcbot", "--"] to run the CLI as another user. */
		public List<String> commandPrefix = new ArrayList<>();
		/** HOME for the CLI (where its login lives). Empty keeps the server's. */
		public String home = "";
		/** Working directory for the CLI; sessions are tied to it. */
		public String workdir;
		public String model = "";
		public String effort = "low";
		/** File in config/chatagents/prompts. */
		public String systemPrompt;
		/** React to deaths and advancements (only the first enabled agent with this on does). */
		public boolean events = true;
		/** Greet first-time players (only the first enabled agent with this on does). */
		public boolean greetNewcomers = true;
		public List<String> extraArgs = new ArrayList<>();
		public Map<String, String> env = new LinkedHashMap<>();
	}

	static Config defaults() {
		Config c = new Config();
		AgentConfig claude = new AgentConfig();
		claude.id = "clod";
		claude.displayName = "clod";
		claude.color = "gold";
		claude.backend = "claude";
		claude.triggers = new ArrayList<>(List.of("claude", "clod"));
		claude.command = System.getProperty("user.home") + "/.local/bin/claude";
		claude.workdir = "config/chatagents/work/clod";
		claude.model = "claude-sonnet-5-5";
		claude.systemPrompt = "clod.md";
		claude.extraArgs = new ArrayList<>(List.of("--max-turns", "12"));
		claude.env.put("ENABLE_CLAUDEAI_MCP_SERVERS", "false");

		AgentConfig agy = new AgentConfig();
		agy.id = "agy";
		agy.displayName = "agy";
		agy.color = "aqua";
		agy.backend = "antigravity";
		agy.triggers = new ArrayList<>(List.of("agy", "antigravity", "gemini"));
		agy.command = System.getProperty("user.home") + "/.local/bin/agy";
		agy.workdir = "config/chatagents/work/agy";
		agy.systemPrompt = "agy.md";
		c.agents.add(claude);
		c.agents.add(agy);
		return c;
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
		for (AgentConfig a : c.agents) {
			if (a.id == null || a.backend == null || a.command == null) {
				throw new IOException("every agent needs id, backend and command");
			}
			if (a.displayName == null) a.displayName = a.id;
			if (a.workdir == null) a.workdir = "config/chatagents/work/" + a.id;
			if (a.systemPrompt == null) a.systemPrompt = a.id + ".md";
			if (a.triggers == null) a.triggers = new ArrayList<>(List.of(a.id));
			if (a.extraArgs == null) a.extraArgs = new ArrayList<>();
			if (a.env == null) a.env = new LinkedHashMap<>();
			if (a.commandPrefix == null) a.commandPrefix = new ArrayList<>();
		}
		return c;
	}

	synchronized void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(this) + "\n");
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
				java.nio.file.StandardCopyOption.ATOMIC_MOVE);
	}
}
