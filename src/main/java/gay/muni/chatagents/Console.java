package gay.muni.chatagents;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs console commands for agents, checked against the policy for the turn's requester. */
final class Console {
	enum Role { PLAYER, OPERATOR }

	private Console() {}

	/** Returns null if allowed, otherwise why not. */
	static String check(Config.Policy policy, Role role, String command) {
		if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
			return "multi-line commands are not allowed";
		}
		String norm = normalize(command);
		if (norm.isEmpty()) return "empty command";
		if (role == Role.PLAYER) {
			for (String allowed : policy.playerAllow) {
				if (matches(norm, allowed)) return null;
			}
			return "only operators can ask for that";
		}
		// "execute ... run <command>" can wrap anything, so check every command it would run.
		List<String> words = Arrays.asList(norm.split(" "));
		List<String> runs = new ArrayList<>();
		runs.add(norm);
		for (int i = 0; i < words.size(); i++) {
			if (words.get(i).equals("run") && i + 1 < words.size()) {
				runs.add(normalize(String.join(" ", words.subList(i + 1, words.size()))));
			}
		}
		for (String run : runs) {
			for (String denied : policy.operatorDeny) {
				if (matches(run, denied)) return "`" + denied + "` is blocked for agents; an operator has to run it themselves";
			}
		}
		return null;
	}

	private static String normalize(String command) {
		String s = command.strip().replaceAll("\\s+", " ");
		if (s.startsWith("/") && !s.startsWith("//")) s = s.substring(1);
		if (s.toLowerCase(Locale.ROOT).startsWith("minecraft:")) s = s.substring("minecraft:".length());
		return s;
	}

	private static boolean matches(String command, String rule) {
		String c = command.toLowerCase(Locale.ROOT);
		String r = normalize(rule).toLowerCase(Locale.ROOT);
		return c.equals(r) || c.startsWith(r + " ");
	}

	/** Runs a command as the server console and returns what it printed. Call off the server thread. */
	static String run(MinecraftServer server, String command) throws Exception {
		List<String> out = new ArrayList<>();
		CommandSource sink = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				synchronized (out) {
					out.add(message.getString());
				}
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return true;
			}
		};
		CompletableFuture<Void> done = new CompletableFuture<>();
		server.execute(() -> {
			try {
				CommandSourceStack source = server.createCommandSourceStack().withSource(sink);
				server.getCommands().performPrefixedCommand(source, command);
				done.complete(null);
			} catch (Throwable t) {
				done.completeExceptionally(t);
			}
		});
		done.get(60, TimeUnit.SECONDS);
		Thread.sleep(300); // some commands (WorldEdit) report a moment later
		synchronized (out) {
			return out.isEmpty() ? "(no output)" : String.join("\n", out);
		}
	}
}
