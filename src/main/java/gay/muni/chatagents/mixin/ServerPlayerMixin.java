package gay.muni.chatagents.mixin;

import gay.muni.chatagents.ChatAgents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the agents when a player dies, with the vanilla death message. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@Inject(method = "die", at = @At("HEAD"))
	private void chatagents$onDie(DamageSource source, CallbackInfo ci) {
		if (!ChatAgents.active()) return;
		ServerPlayer self = (ServerPlayer) (Object) this;
		ChatAgents.onDeath(self, self.getCombatTracker().getDeathMessage().getString());
	}
}
