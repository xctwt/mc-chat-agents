package gay.muni.chatagents;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.io.IOException;

/**
 * /chatagents [list|reload|<agent> on|off|status|reset], plus a shortcut per agent: /clod on, /agy off, ...
 * Works from the server console too (e.g. over SSH: mc 'clod off').
 */
final class AgentCommands {
	private AgentCommands() {}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher, Config cfg) {
		var agentArg = Commands.argument("agent", StringArgumentType.word())
				.suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.agents.stream().map(a -> a.id), b))
				.executes(c -> status(c, StringArgumentType.getString(c, "agent")));
		for (var action : actions(c -> StringArgumentType.getString(c, "agent"))) agentArg.then(action);
		dispatcher.register(Commands.literal("chatagents")
				.executes(AgentCommands::list)
				.then(Commands.literal("list").executes(AgentCommands::list))
				.then(Commands.literal("reload").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(AgentCommands::reload))
				.then(agentArg));
		for (Config.AgentConfig a : cfg.agents) {
			if (dispatcher.getRoot().getChild(a.id) != null) {
				ChatAgents.LOG.warn("/{} is taken by another command; use /chatagents {} instead", a.id, a.id);
				continue;
			}
			LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(a.id).executes(c -> status(c, a.id));
			for (var action : actions(c -> a.id)) root.then(action);
			dispatcher.register(root);
		}
	}

	private interface IdOf {
		String get(CommandContext<CommandSourceStack> c);
	}

	@SuppressWarnings("unchecked")
	private static LiteralArgumentBuilder<CommandSourceStack>[] actions(IdOf id) {
		return new LiteralArgumentBuilder[] {
				Commands.literal("status").executes(c -> status(c, id.get(c))),
				Commands.literal("on").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> toggle(c, id.get(c), true)),
				Commands.literal("off").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> toggle(c, id.get(c), false)),
				Commands.literal("reset").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> reset(c, id.get(c))),
		};
	}

	private static Agent find(CommandContext<CommandSourceStack> c, String id) {
		Hub hub = ChatAgents.hub();
		Agent a = hub == null ? null : hub.agent(id);
		if (a == null) {
			c.getSource().sendFailure(Component.literal(hub == null ? "chat agents are not running (check the server log)"
					: "no agent called " + id));
		}
		return a;
	}

	private static int list(CommandContext<CommandSourceStack> c) {
		Hub hub = ChatAgents.hub();
		if (hub == null) {
			c.getSource().sendFailure(Component.literal("chat agents are not running (check the server log)"));
			return 0;
		}
		for (Agent a : hub.agents()) {
			c.getSource().sendSuccess(() -> Component.literal(a.cfg.id + ": " + a.status()), false);
		}
		return hub.agents().size();
	}

	private static int status(CommandContext<CommandSourceStack> c, String id) {
		Agent a = find(c, id);
		if (a == null) return 0;
		c.getSource().sendSuccess(() -> Component.literal(a.cfg.displayName + ": " + a.status()), false);
		return a.cfg.enabled ? 1 : 0;
	}

	private static int toggle(CommandContext<CommandSourceStack> c, String id, boolean on) {
		Agent a = find(c, id);
		if (a == null) return 0;
		try {
			ChatAgents.hub().setEnabled(a, on);
		} catch (IOException e) {
			c.getSource().sendFailure(Component.literal("turned " + (on ? "on" : "off") + ", but could not save the config: " + e.getMessage()));
			return 0;
		}
		c.getSource().sendSuccess(() -> Component.literal(a.cfg.displayName + " is now " + (on ? "on" : "off")), true);
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> c, String id) {
		Agent a = find(c, id);
		if (a == null) return 0;
		a.halt();
		a.backend.reset();
		c.getSource().sendSuccess(() -> Component.literal(a.cfg.displayName + "'s conversation was reset"), true);
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> c) {
		Hub hub = ChatAgents.hub();
		if (hub == null) return list(c);
		try {
			String result = hub.reload();
			c.getSource().sendSuccess(() -> Component.literal("chat agents " + result), true);
			return 1;
		} catch (IOException | RuntimeException e) {
			c.getSource().sendFailure(Component.literal("could not reload: " + e.getMessage()));
			return 0;
		}
	}
}
