package gay.muni.chatagents.mixin;

import gay.muni.chatagents.ChatAgents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells the agents when a player completes an advancement that is announced in chat. */
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
	@Shadow
	@Final
	private ServerPlayer player;

	@Shadow
	public abstract AdvancementProgress getOrStartProgress(AdvancementHolder holder);

	@Inject(method = "award", at = @At("RETURN"))
	private void chatagents$onAward(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
		// award returns true when it granted a criterion; if the advancement is now done, that one finished it.
		if (!cir.getReturnValue() || !getOrStartProgress(holder).isDone() || !ChatAgents.active()) return;
		holder.value().display().filter(DisplayInfo::announceToChat).ifPresent(d ->
				ChatAgents.onAdvancement(player, d.type(), d.title().getString()));
	}
}
