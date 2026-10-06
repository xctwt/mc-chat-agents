package gay.muni.chatagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Shared state for all agents: who is online, recent chat, reminders, first-time players, and routing. */
final class Hub {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter MDHM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

	record Online(String name, double joined) {}

	record Line(String author, String text) {}

	record Reminder(String id, String player, String text, double due, String by, String agent) {}

	record ToolResult(String text, boolean error) {}

	volatile Config cfg;
	final Path serverDir;
	final Path configDir;
	final Path configFile;
	final Path stateDir;
	final MinecraftServer server;
	final Gateway gateway = new Gateway(this);
	private final Map<String, Agent> agents = new LinkedHashMap<>();
	private final Map<String, Online> online = new ConcurrentHashMap<>();
	private final Deque<Line> recent = new ArrayDeque<>();
	private final Object stateLock = new Object();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "chatagents-scheduler");
		t.setDaemon(true);
		return t;
	});

	Hub(MinecraftServer server, Path configDir, Config cfg) throws IOException {
		this.server = server;
		this.serverDir = server.getServerDirectory().toAbsolutePath();
		this.configDir = configDir;
		this.configFile = configDir.resolve("config.json");
		this.cfg = cfg;
		this.stateDir = serverDir.resolve(cfg.stateDir);
		for (Config.AgentConfig a : cfg.agents) {
			ChatAgents.copyDefaultPrompt(configDir, a.systemPrompt);
			agents.put(a.id.toLowerCase(), new Agent(this, a));
		}
	}

	void start() throws IOException {
		gateway.start(cfg.gatewayHost, cfg.gatewayPort);
		seedSeen();
		agents.values().forEach(Agent::start);
		scheduler.scheduleWithFixedDelay(this::deliverReminders, 5, 5, TimeUnit.SECONDS);
		ChatAgents.LOG.info("chat agents: {}", agents.values().stream().map(a -> a.cfg.id + " (" + (a.cfg.enabled ? "on" : "off") + ")").toList());
	}

	void stop() {
		scheduler.shutdownNow();
		agents.values().forEach(Agent::halt);
		gateway.stop();
	}

	Agent agent(String id) {
		return agents.get(id.toLowerCase());
	}

	Collection<Agent> agents() {
		return agents.values();
	}

	// --- Toggling and config ---

	void setEnabled(Agent a, boolean on) throws IOException {
		a.cfg.enabled = on;
		if (!on) a.halt();
		cfg.save(configFile);
	}

	/** Re-reads config.json; changes to the list of agents need a restart. */
	String reload() throws IOException {
		Config fresh = Config.load(configFile);
		List<String> notes = new ArrayList<>();
		for (Config.AgentConfig ac : fresh.agents) {
			Agent a = agent(ac.id);
			if (a == null) {
				notes.add("new agent " + ac.id + " needs a restart");
				continue;
			}
			if (a.cfg.enabled && !ac.enabled) a.halt();
			a.update(ac);
		}
		cfg = fresh;
		return notes.isEmpty() ? "reloaded" : "reloaded; " + String.join("; ", notes);
	}

	// --- Game events (server thread) ---

	void onChat(ServerPlayer player, String msg) {
		String name = player.nameAndId().name();
		boolean anyNamed = agents.values().stream().anyMatch(a -> a.isNamedIn(msg));
		for (Agent a : agents.values()) a.onChat(name, msg, anyNamed);
		addRecent(null, "<" + name + "> " + msg);
	}

	void onJoin(ServerPlayer player) {
		String name = player.nameAndId().name();
		online.put(name.toLowerCase(), new Online(name, Agent.now()));
		if (markSeen(name)) {
			ChatAgents.LOG.info("[chatagents] newcomer {}", name);
			// Wait until they've loaded in (and registered/logged in) so they actually see it.
			scheduler.schedule(() -> {
				Agent greeter = first(a -> a.cfg.greetNewcomers);
				if (greeter != null) {
					greeter.enqueue(new Agent.Item("newcomer", name, "NEWCOMER: " + name + " joined the server for the "
							+ "very first time. Greet them; your reply is a private message only " + name + " sees.", false));
				}
			}, cfg.timing.joinDelaySeconds, TimeUnit.SECONDS);
		}
	}

	void onLeave(ServerPlayer player) {
		String key = player.nameAndId().name().toLowerCase();
		online.remove(key);
		for (Agent a : agents.values()) a.convo.remove(key);
	}

	void onDeath(ServerPlayer player, String message) {
		String name = player.nameAndId().name();
		event("death: " + message, name, "* " + message);
	}

	void onAdvancement(ServerPlayer player, AdvancementType type, String title) {
		String name = player.nameAndId().name();
		String[] how = switch (type) {
			case CHALLENGE -> new String[] {"CHALLENGE (rare!)", "has completed the challenge"};
			case GOAL -> new String[] {"goal", "has reached the goal"};
			default -> new String[] {"advancement", "has made the advancement"};
		};
		String text = name + " " + how[1] + " [" + title + "]";
		event(how[0] + ": " + text, name, "* " + text);
	}

	private void event(String text, String name, String recentLine) {
		if (!isOnline(name)) return;
		Agent a = first(x -> x.cfg.events);
		if (a != null) a.enqueue(new Agent.Item("event", name.toLowerCase(), text, false));
		addRecent(null, recentLine);
	}

	private Agent first(java.util.function.Predicate<Agent> p) {
		return agents.values().stream().filter(a -> a.cfg.enabled && p.test(a)).findFirst().orElse(null);
	}

	// --- Shared state ---

	boolean isOnline(String name) {
		return online.containsKey(name.toLowerCase());
	}

	String onlineNames() {
		String s = String.join(", ", online.values().stream().map(Online::name).sorted(String.CASE_INSENSITIVE_ORDER).toList());
		return s.isEmpty() ? "(nobody)" : s;
	}

	boolean isOp(String name) {
		return Arrays.stream(server.getPlayerList().getOpNames()).anyMatch(n -> n.equalsIgnoreCase(name));
	}

	void addRecent(String author, String text) {
		synchronized (recent) {
			recent.addLast(new Line(author, text));
			while (recent.size() > 12) recent.removeFirst();
		}
	}

	/** Recent lines, minus the agent's own replies (it already has those in its conversation). */
	List<String> recentFor(Agent agent) {
		synchronized (recent) {
			return recent.stream().filter(l -> !agent.cfg.id.equals(l.author())).map(Line::text).toList();
		}
	}

	String header(String kind) {
		ZonedDateTime utc = ZonedDateTime.now(ZoneOffset.UTC);
		ZonedDateTime local = utc.withZoneSameInstant(zone());
		return "[server-verified] " + utc.format(STAMP) + " UTC (" + local.format(HM) + " " + cfg.timezoneLabel + ") | " + kind;
	}

	String localTime(double epochSeconds) {
		return Instant.ofEpochMilli((long) (epochSeconds * 1000)).atZone(zone()).format(MDHM) + " " + cfg.timezoneLabel;
	}

	private ZoneId zone() {
		try {
			return ZoneId.of(cfg.timezone);
		} catch (RuntimeException e) {
			return ZoneOffset.UTC;
		}
	}

	static String ago(Instant t) {
		long s = Duration.between(t, Instant.now()).toSeconds();
		return s < 60 ? "just now" : s < 3600 ? s / 60 + " min ago" : String.format("%.1f h ago", s / 3600.0);
	}

	// --- Output ---

	/** Posts a line as the agent: to everyone, to one player, or as a private whisper. Any thread. */
	void say(Agent agent, String text, String target, boolean whisper) {
		if (target == null) addRecent(agent.cfg.id, "<" + agent.cfg.displayName + "> " + text);
		server.execute(() -> {
			Component msg;
			if (whisper) { // styled like a vanilla /msg so it's clear only they can see it
				msg = Component.literal(agent.cfg.displayName + " whispers to you: " + text)
						.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
			} else {
				MutableComponent tag = Component.literal("<" + agent.cfg.displayName + "> ");
				try {
					tag.withStyle(ChatFormatting.valueOf(agent.cfg.color.toUpperCase(java.util.Locale.ROOT)));
				} catch (IllegalArgumentException ignored) { // not a color name; leave it plain
				}
				msg = tag.append(Component.literal(text).withStyle(ChatFormatting.RESET));
			}
			if (target == null) {
				server.getPlayerList().broadcastSystemMessage(msg, false);
			} else {
				ServerPlayer p = server.getPlayerList().getPlayerByName(target);
				if (p != null) p.sendSystemMessage(msg);
			}
		});
	}

	/** A gray system note from an agent (e.g. a route switch): to everyone, to online operators only, or nobody. */
	void notice(Agent agent, String text, String audience) {
		agent.log(text);
		if (audience.equals("off")) return;
		boolean everyone = audience.equals("everyone");
		server.execute(() -> {
			Component msg = Component.literal("[" + agent.cfg.displayName + "] " + text).withStyle(ChatFormatting.GRAY);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (everyone || isOp(p.nameAndId().name())) p.sendSystemMessage(msg);
			}
		});
	}

	ToolResult runConsole(Agent agent, String command) {
		Console.Role role = agent.turnRole;
		if (role == null) return new ToolResult("no turn is in progress", true);
		String denied = Console.check(cfg.policy, role, command);
		if (denied != null) {
			agent.log("console denied (" + role + "): " + command);
			return new ToolResult("denied: " + denied, true);
		}
		agent.log("console (" + role + "): " + command);
		try {
			return new ToolResult(Console.run(server, command), false);
		} catch (Exception e) {
			return new ToolResult("error: " + e, true);
		}
	}

	// --- First-time players ---

	private Path seenFile() {
		return stateDir.resolve("seen.json");
	}

	/** On first run, treat everyone who has ever joined as already greeted. */
	private void seedSeen() {
		if (seenFile().toFile().exists()) return;
		JsonArray names = new JsonArray();
		JsonElement cache = Store.read(serverDir.resolve("usercache.json"), new JsonArray());
		if (cache.isJsonArray()) {
			for (JsonElement e : cache.getAsJsonArray()) {
				if (e instanceof JsonObject o && o.has("name")) names.add(o.get("name").getAsString().toLowerCase());
			}
		}
		Store.write(seenFile(), names);
	}

	private boolean markSeen(String name) {
		synchronized (stateLock) {
			JsonArray seen = Store.read(seenFile(), new JsonArray()) instanceof JsonArray a ? a : new JsonArray();
			for (JsonElement e : seen) {
				if (e.getAsString().equalsIgnoreCase(name)) return false;
			}
			seen.add(name.toLowerCase());
			Store.write(seenFile(), seen);
			return true;
		}
	}

	// --- Reminders ---

	private Path remindersFile() {
		return stateDir.resolve("reminders.json");
	}

	private List<Reminder> loadReminders() {
		JsonElement data = Store.read(remindersFile(), new JsonArray());
		List<Reminder> out = new ArrayList<>();
		if (!data.isJsonArray()) return out;
		for (JsonElement e : data.getAsJsonArray()) {
			try {
				Reminder r = Config.GSON.fromJson(e, Reminder.class);
				if (r.id() != null && r.player() != null) out.add(r);
			} catch (RuntimeException ignored) {
			}
		}
		return out;
	}

	private void saveReminders(List<Reminder> rs) {
		Store.write(remindersFile(), Config.GSON.toJsonTree(rs));
	}

	List<Reminder> remindersFor(Collection<String> names) {
		Set<String> keys = new java.util.HashSet<>(names.stream().map(String::toLowerCase).toList());
		synchronized (stateLock) {
			return loadReminders().stream().filter(r -> keys.contains(r.player().toLowerCase())).toList();
		}
	}

	void addReminder(Agent agent, double minutes, String player, String text, String by) {
		if (!(minutes > 0 && minutes <= cfg.timing.maxReminderMinutes)) {
			agent.log("reminder rejected (time out of range): " + minutes);
			return;
		}
		synchronized (stateLock) {
			List<Reminder> rs = new ArrayList<>(loadReminders());
			if (rs.stream().filter(r -> r.player().equalsIgnoreCase(player)).count() >= cfg.timing.maxRemindersPerPlayer) {
				agent.log("reminder rejected (too many) for " + player);
				return;
			}
			text = text.strip();
			if (text.length() > cfg.timing.maxReplyChars) text = text.substring(0, cfg.timing.maxReplyChars);
			Reminder r = new Reminder(UUID.randomUUID().toString().replace("-", "").substring(0, 6), player, text,
					System.currentTimeMillis() / 1000.0 + minutes * 60, by, agent.cfg.id);
			rs.add(r);
			saveReminders(rs);
			agent.log("reminder set " + r);
		}
	}

	/** allowed: lowercase players whose reminders may be cancelled, or null for any (operators). */
	void cancelReminder(String id, Set<String> allowed) {
		synchronized (stateLock) {
			List<Reminder> rs = loadReminders();
			List<Reminder> kept = rs.stream().filter(r -> !(r.id().equalsIgnoreCase(id)
					&& (allowed == null || allowed.contains(r.player().toLowerCase())))).toList();
			if (kept.size() != rs.size()) {
				saveReminders(kept);
				ChatAgents.LOG.info("[chatagents] reminder cancelled {}", id);
			}
		}
	}

	private void deliverReminders() {
		try {
			double now = System.currentTimeMillis() / 1000.0, mono = Agent.now();
			List<Reminder> due;
			synchronized (stateLock) {
				List<Reminder> rs = loadReminders();
				due = rs.stream().filter(r -> r.due() <= now && online.containsKey(r.player().toLowerCase())
						&& mono - online.get(r.player().toLowerCase()).joined() >= cfg.timing.joinDelaySeconds).toList();
				if (due.isEmpty()) return;
				saveReminders(rs.stream().filter(r -> !due.contains(r)).toList());
			}
			for (Reminder r : due) {
				Online who = online.get(r.player().toLowerCase());
				if (who == null) continue;
				Agent a = agent(r.agent() == null ? "" : r.agent());
				if (a == null) a = agents.values().iterator().next();
				boolean late = now - r.due() > 300;
				ChatAgents.LOG.info("[chatagents] delivering reminder {}", r);
				say(a, (late ? "(reminder from earlier) " : "") + r.text(), who.name(), false);
				server.execute(() -> server.getCommands().performPrefixedCommand(
						server.createCommandSourceStack().withSuppressedOutput(),
						"playsound minecraft:block.note_block.bell master " + who.name()));
			}
		} catch (Throwable t) {
			ChatAgents.LOG.error("reminder delivery failed", t);
		}
	}
}
