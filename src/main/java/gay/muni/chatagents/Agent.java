package gay.muni.chatagents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** One chat agent: decides which messages reach it, runs turns one at a time, and posts the replies. */
final class Agent {
	private static final Pattern RESET = Pattern.compile("\\[\\[reset\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern AWAIT = Pattern.compile("\\[\\[await:\\s*([A-Za-z0-9_,\\s]*)\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern REMIND = Pattern.compile(
			"\\[\\[remind:\\s*(\\d+(?:\\.\\d+)?)\\s*:\\s*([A-Za-z0-9_]{1,16})\\s*:\\s*(.+?)\\]\\]",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern UNREMIND = Pattern.compile("\\[\\[unremind:\\s*([a-f0-9]{6})\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern ANY_TAG = Pattern.compile("\\[\\[[a-z-]+:?[^\\]]*\\]\\]", Pattern.CASE_INSENSITIVE);

	/** chat: a message for the agent; newcomer: a first-time player to greet privately; event: a death or advancement. */
	record Item(String type, String player, String text, boolean followUp) {}

	final Hub hub;
	volatile Config.AgentConfig cfg;
	final Path stateDir;
	final Path workdir;
	/** One per harness this agent has used, by harness name and backend type. */
	private final Map<String, Backend> backends = new ConcurrentHashMap<>();
	/** Route key -> epoch seconds until which the route is skipped because it ran out of usage. */
	private final Map<String, Long> benched = new ConcurrentHashMap<>();
	/** The route that answered last. */
	private volatile Config.Route active;
	/** Bumped by halt(), so a turn that was killed on purpose doesn't fail over to the next route. */
	private volatile int generation;
	/** Bearer token for this agent's console tool; new on every server start. */
	final String token;
	private volatile Pattern trigger;

	private final LinkedBlockingQueue<Item> pending = new LinkedBlockingQueue<>();
	/** Lowercase name -> deadline (seconds); infinity while the agent is still answering them. */
	final Map<String, Double> convo = new ConcurrentHashMap<>();
	private final Map<String, Double> lastTrigger = new ConcurrentHashMap<>();
	private double lastEvent = -1e9;
	private final Map<String, Double> lastEventFor = new HashMap<>();
	/** Who the current turn is for; null between turns, when the console tool refuses everything. */
	volatile Console.Role turnRole;
	volatile String lastError;
	volatile Instant lastReply;

	Agent(Hub hub, Config.AgentConfig cfg) throws java.io.IOException {
		this.hub = hub;
		this.cfg = cfg;
		this.stateDir = hub.stateDir.resolve(cfg.id);
		this.workdir = hub.serverDir.resolve(cfg.workdir);
		Files.createDirectories(stateDir);
		Files.createDirectories(workdir);
		byte[] b = new byte[24];
		new SecureRandom().nextBytes(b);
		this.token = HexFormat.of().formatHex(b);
		update(cfg);
	}

	void update(Config.AgentConfig cfg) {
		this.cfg = cfg;
		String words = cfg.triggers.stream().map(Pattern::quote).collect(Collectors.joining("|"));
		this.trigger = Pattern.compile("\\b(" + words + ")",
				Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
	}

	void start() {
		Thread t = new Thread(this::worker, "chatagents-" + cfg.id);
		t.setDaemon(true);
		t.start();
	}

	void log(String msg) {
		ChatAgents.LOG.info("[{}] {}", cfg.id, msg);
	}

	/** Drops queued work and kills a running turn, e.g. when the agent is turned off. */
	void halt() {
		generation++;
		pending.clear();
		convo.clear();
		backends.values().forEach(Backend::kill);
	}

	// --- Harnesses and routes ---

	Backend backend(String harness) {
		Config.Harness h = hub.cfg.harnesses.get(harness);
		if (h == null) throw new IllegalArgumentException("no harness called " + harness);
		return backends.computeIfAbsent(harness + ":" + h.backend, k -> Backend.create(this, harness, h.backend));
	}

	/** Starts a fresh conversation in every harness. */
	void resetSessions() {
		for (Config.Route r : cfg.routes) backend(r.harness).reset();
		backends.values().forEach(Backend::reset);
	}

	/** Seconds until the route may be used again, or 0 if it's available. */
	long benchedFor(Config.Route r) {
		return Math.max(benched.getOrDefault(r.key(), 0L) - System.currentTimeMillis() / 1000, 0);
	}

	/** The route the next turn will try first. */
	Config.Route current() {
		List<Config.Route> routes = cfg.routes;
		return routes.stream().filter(r -> benchedFor(r) == 0).findFirst().orElse(routes.getFirst());
	}

	Config.Route active() {
		return active;
	}

	/**
	 * Makes harness + model the preferred route, keeping the current model when model is null (and the current
	 * harness when harness is null). Returns the route; the caller saves the config.
	 */
	Config.Route use(String harness, String model) {
		if (harness != null && !hub.cfg.harnesses.containsKey(harness)) {
			throw new IllegalArgumentException("no harness called " + harness + "; known: " + hub.cfg.harnesses.keySet());
		}
		Config.Route cur = current();
		String h = harness != null ? harness : cur.harness, m = model != null ? model : cur.model;
		List<Config.Route> routes = new ArrayList<>(cfg.routes);
		Config.Route pick = routes.stream().filter(r -> r.harness.equals(h) && r.model.equals(m)).findFirst()
				.orElseGet(() -> new Config.Route(h, m, cur.effort));
		routes.remove(pick);
		routes.addFirst(pick);
		cfg.routes = routes;
		benched.remove(pick.key()); // asked for by hand, so give it another try
		return pick;
	}

	String describe(Config.Route r) {
		String model = hub.cfg.modelFor(r);
		String label = r.harness + " · " + (r.model.isEmpty() ? "default model" : r.model);
		return model.equals(r.model) ? label : label + " (" + model + ")";
	}

	void enqueue(Item item) {
		if (cfg.enabled) pending.add(item);
	}

	String systemPrompt() {
		String text = Store.readString(hub.configDir.resolve("prompts").resolve(cfg.systemPrompt));
		return text.replace("{name}", cfg.displayName)
				.replace("{triggers}", cfg.triggers.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", ")));
	}

	static double now() {
		return System.nanoTime() / 1e9;
	}

	// --- Incoming chat (server thread) ---

	boolean isNamedIn(String msg) {
		return cfg.enabled && trigger.matcher(msg).find();
	}

	/** otherNamed: the message names another agent (and not this one), so it isn't a follow-up for this one. */
	void onChat(String player, String msg, boolean otherNamed) {
		if (!cfg.enabled) return;
		String line = "<" + player + "> " + msg;
		double now = now();
		String key = player.toLowerCase();
		boolean talking = convo.getOrDefault(key, 0.0) > now;
		if (trigger.matcher(msg).find()) {
			if (talking || now - lastTrigger.getOrDefault(key, -1e9) >= hub.cfg.timing.cooldownSeconds) {
				lastTrigger.put(key, now);
				convo.put(key, Double.POSITIVE_INFINITY);
				pending.add(new Item("chat", player, line, false));
			}
		} else if (talking && !otherNamed) {
			convo.put(key, Double.POSITIVE_INFINITY);
			pending.add(new Item("chat", player, line, true));
		}
	}

	// --- Turns (worker thread) ---

	private void worker() {
		while (true) {
			try {
				List<Item> batch = new ArrayList<>(List.of(pending.take()));
				Thread.sleep(500); // let simultaneous lines (e.g. a death and a chat reply) land in the same batch
				pending.drainTo(batch);
				if (cfg.enabled) process(batch);
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				ChatAgents.LOG.error("[{}] worker error", cfg.id, t);
			}
		}
	}

	private void process(List<Item> batch) {
		List<Item> players = new ArrayList<>(), operators = new ArrayList<>(), greetings = new ArrayList<>();
		List<Item> events = new ArrayList<>();
		for (Item it : batch) {
			switch (it.type()) {
				case "chat" -> (hub.isOp(it.player()) ? operators : players).add(it);
				case "newcomer" -> greetings.add(it);
				default -> events.add(it);
			}
		}
		if (!players.isEmpty()) handleChat(players, false);
		if (!operators.isEmpty()) handleChat(operators, true);

		Config.Timing t = hub.cfg.timing;
		double now = now();
		if (!events.isEmpty() && now - lastEvent < t.eventCooldownSeconds) {
			log("skipping events (cooldown): " + events.stream().map(Item::text).toList());
			events.clear();
		}
		List<Item> cooling = events.stream()
				.filter(e -> now - lastEventFor.getOrDefault(e.player(), -1e9) < t.playerEventCooldownSeconds).toList();
		if (!cooling.isEmpty()) {
			log("skipping events (player cooldown): " + cooling.stream().map(Item::text).toList());
			events.removeAll(cooling);
		}
		for (Item g : greetings) {
			if (!hub.isOnline(g.player())) {
				log("newcomer left before the greeting: " + g.player());
			} else if (handleEvents(List.of(g.text()), g.player())) {
				// Let them answer the greeting without saying the agent's name.
				convo.put(g.player().toLowerCase(), now() + t.conversationSeconds);
			}
		}
		if (!events.isEmpty() && handleEvents(events.stream().map(Item::text).toList(), null)) {
			// Cooldowns only count reactions the agent actually posted, not its NO_REPLYs.
			lastEvent = now();
			for (Item e : events) lastEventFor.put(e.player(), lastEvent);
		}
	}

	private void handleChat(List<Item> batch, boolean elevated) {
		Set<String> lines = batch.stream().map(Item::text).collect(Collectors.toSet());
		List<String> shown = batch.stream().map(it -> it.text()
				+ (it.followUp() ? "   (follow-up: no mention, sent while you were talking with them)" : "")).toList();
		List<String> senders = new ArrayList<>(new TreeSet<>(batch.stream().map(Item::player).toList()));
		List<String> context = hub.recentFor(this).stream().filter(l -> !lines.contains(l)).toList();
		String role = elevated ? "operator" : "regular player";
		List<Hub.Reminder> rs = hub.remindersFor(senders);
		StringBuilder prompt = new StringBuilder(hub.header("requester(s): "
				+ senders.stream().map(p -> p + " = " + role).collect(Collectors.joining(", "))));
		prompt.append("\nOnline now: ").append(hub.onlineNames());
		if (!rs.isEmpty()) {
			prompt.append("\nTheir pending reminders: ").append(rs.stream()
					.map(r -> r.id() + " for " + r.player() + " at " + hub.localTime(r.due()) + ": " + r.text())
					.collect(Collectors.joining("; ")));
		}
		prompt.append("\nRecent chat:\n").append(context.isEmpty() ? "(none)" : String.join("\n", context));
		prompt.append("\n\nMessage(s) for you:\n").append(String.join("\n", shown));
		log("-> " + (elevated ? "(op) " : "") + shown);

		String reply;
		try {
			reply = turn(prompt.toString(), elevated ? Console.Role.OPERATOR : Console.Role.PLAYER, "chat", senders);
		} catch (Exception e) {
			lastError = e.getMessage();
			ChatAgents.LOG.warn("[{}] turn failed: {}", cfg.id, e.getMessage());
			if (cfg.enabled) hub.say(this, "sorry, my brain glitched... try again in a bit", null, false);
			for (String p : senders) convo.remove(p.toLowerCase());
			return;
		}
		log("<- " + reply);

		double now = now();
		boolean silent = isSilent(stripTags(reply));
		for (String p : senders) {
			// Keep the conversation open after a real reply; a NO_REPLY means they've moved on.
			if (silent) convo.remove(p.toLowerCase());
			else convo.put(p.toLowerCase(), now + hub.cfg.timing.conversationSeconds);
		}
		Matcher m = AWAIT.matcher(reply);
		while (m.find()) {
			for (String name : m.group(1).split("[,\\s]+")) {
				if (!name.isEmpty()) {
					convo.merge(name.toLowerCase(), now + hub.cfg.timing.awaitSeconds, Math::max);
					log("awaiting answer from " + name);
				}
			}
		}
		m = REMIND.matcher(reply);
		while (m.find()) hub.addReminder(this, Double.parseDouble(m.group(1)), m.group(2), m.group(3), String.join(",", senders));
		m = UNREMIND.matcher(reply);
		while (m.find()) {
			Set<String> allowed = elevated ? null : senders.stream().map(String::toLowerCase).collect(Collectors.toSet());
			hub.cancelReminder(m.group(1), allowed);
		}
		if (RESET.matcher(reply).find()) {
			if (elevated) {
				resetSessions();
				convo.clear();
				log("chat reset by operator request");
			} else {
				log("ignored reset request from non-operator turn");
			}
		}
		post(stripTags(reply), null, false);
	}

	/** React to server events; with whisperTo (a newcomer's greeting), the reply goes only to that player. */
	private boolean handleEvents(List<String> events, String whisperTo) {
		String prompt = hub.header("server event(s), nobody asked you anything")
				+ "\nOnline now: " + hub.onlineNames()
				+ "\nRecent chat:\n" + (hub.recentFor(this).isEmpty() ? "(none)" : String.join("\n", hub.recentFor(this)))
				+ "\n\nEvent(s):\n" + String.join("\n", events);
		log("-> events " + events);
		String reply;
		try {
			reply = turn(prompt, Console.Role.PLAYER, whisperTo != null ? "greeting" : "event", List.of());
		} catch (Exception e) {
			lastError = e.getMessage();
			ChatAgents.LOG.warn("[{}] event turn failed: {}", cfg.id, e.getMessage());
			return false;
		}
		log("<- " + reply);
		reply = stripTags(reply); // events never act on tags like reset/remind
		post(reply, whisperTo, whisperTo != null);
		return !isSilent(reply);
	}

	/**
	 * Runs the turn on the first route that isn't benched, moving down the list when one fails (if failover is on).
	 * A route that ran out of usage is benched until its limit resets, so later turns skip it and come back to it
	 * on their own afterwards. If every route is benched, the one that frees up first gets a try anyway.
	 */
	private String turn(String prompt, Console.Role role, String kind, List<String> players) throws Exception {
		int gen = generation;
		List<Config.Route> order = cfg.routes.stream().filter(r -> benchedFor(r) == 0).toList();
		if (order.isEmpty()) {
			order = List.of(cfg.routes.stream().min(java.util.Comparator.comparingLong(this::benchedFor)).orElseThrow());
		}
		if (!cfg.failover) order = order.subList(0, 1);
		Exception last = null;
		turnRole = role;
		try {
			for (int i = 0; i < order.size(); i++) {
				Config.Route r = order.get(i);
				try {
					String reply = backend(r.harness).run(prompt, kind, players, hub.cfg.modelFor(r), r.effort);
					active = r;
					lastError = null;
					lastReply = Instant.now();
					return reply;
				} catch (Backend.LimitException e) {
					long now = System.currentTimeMillis() / 1000;
					long until = e.until > now ? e.until : now + hub.cfg.timing.limitCooldownMinutes * 60L;
					benched.put(r.key(), until);
					log(describe(r) + " is out of usage until " + hub.localTime(until) + ": " + e.getMessage());
					last = e;
					if (i + 1 < order.size() && generation == gen && cfg.announceSwitches) {
						hub.say(this, "(" + r.harness + " is out of usage, switching to " + order.get(i + 1).harness
								+ " · back around " + hub.localTime(until) + ")", null, false);
					}
				} catch (Exception e) {
					log(describe(r) + " failed: " + e.getMessage());
					last = e;
				}
				if (generation != gen || !cfg.enabled) break; // killed on purpose: don't try the next route
			}
			throw last;
		} finally {
			turnRole = null;
		}
	}

	private void post(String reply, String target, boolean whisper) {
		if (isSilent(reply) || !cfg.enabled) return;
		for (String line : reply.split("\n")) {
			line = line.strip();
			if (line.isEmpty()) continue;
			if (line.length() > hub.cfg.timing.maxReplyChars) line = line.substring(0, hub.cfg.timing.maxReplyChars);
			hub.say(this, line, target, whisper);
		}
	}

	private static boolean isSilent(String reply) {
		return reply.isEmpty() || reply.equals("NO_REPLY");
	}

	private static String stripTags(String reply) {
		return ANY_TAG.matcher(reply).replaceAll("").strip();
	}

	String status() {
		List<String> parts = new ArrayList<>(List.of(cfg.enabled ? "on" : "off", describe(current())));
		long out = cfg.routes.stream().filter(r -> benchedFor(r) > 0).count();
		if (out > 0) parts.add(out + " of " + cfg.routes.size() + " routes out of usage");
		if (turnRole != null) parts.add("busy");
		if (lastReply != null) parts.add("last reply " + Hub.ago(lastReply));
		if (lastError != null) parts.add("last error: " + lastError);
		return String.join(", ", parts);
	}
}
