package gay.muni.chatagents;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class ChatAgents implements DedicatedServerModInitializer {
	public static final String MOD_ID = "chatagents";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
	static final String VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");

	private static volatile Hub hub;

	public static boolean active() {
		return hub != null;
	}

	/** Called from mixins (server thread). */
	public static void onDeath(net.minecraft.server.level.ServerPlayer player, String message) {
		Hub h = hub;
		if (h != null) h.onDeath(player, message);
	}

	public static void onAdvancement(net.minecraft.server.level.ServerPlayer player,
			net.minecraft.advancements.AdvancementType type, String title) {
		Hub h = hub;
		if (h != null) h.onAdvancement(player, type, title);
	}

	/** The running hub, or null before the server has started (or if the config failed to load). */
	static Hub hub() {
		return hub;
	}

	static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	}

	@Override
	public void onInitializeServer() {
		Config cfg;
		try {
			cfg = Config.load(configDir().resolve("config.json"));
		} catch (IOException | RuntimeException e) {
			LOG.error("chat agents disabled: could not load {}", configDir().resolve("config.json"), e);
			return;
		}
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
				AgentCommands.register(dispatcher, cfg));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				Hub h = new Hub(server, configDir(), cfg);
				h.start();
				hub = h;
			} catch (IOException | RuntimeException e) {
				LOG.error("chat agents failed to start", e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			Hub h = hub;
			hub = null;
			if (h != null) h.stop();
		});

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			Hub h = hub;
			if (h != null) h.onChat(sender, message.signedContent());
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			Hub h = hub;
			if (h != null) h.onJoin(handler.player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Hub h = hub;
			if (h != null) h.onLeave(handler.player);
		});
	}

	/** Puts the bundled prompt in config/chatagents/prompts/name unless one is already there. */
	static void copyDefaultPrompt(Path configDir, String name) throws IOException {
		Path file = configDir.resolve("prompts").resolve(name);
		if (Files.exists(file)) return;
		Files.createDirectories(file.getParent());
		try (InputStream in = ChatAgents.class.getResourceAsStream("/chatagents/default-prompt.md")) {
			if (in != null) Files.copy(in, file);
		}
	}
}
