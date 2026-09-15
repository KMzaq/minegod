package dev.ftb.mods.ftbquests.net;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftbquests.api.FTBQuestsAPI;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.task.DonationItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Client request to finalise a donation after its minimum contribution was met. */
public record CompleteDonationTaskMessage(long taskId) implements CustomPacketPayload {
	public static final Type<CompleteDonationTaskMessage> TYPE = new Type<>(FTBQuestsAPI.rl("complete_donation_task"));

	public static final StreamCodec<FriendlyByteBuf, CompleteDonationTaskMessage> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, CompleteDonationTaskMessage::taskId,
			CompleteDonationTaskMessage::new
	);

	@Override
	public Type<CompleteDonationTaskMessage> type() {
		return TYPE;
	}

	public static void handle(CompleteDonationTaskMessage message, NetworkManager.PacketContext context) {
		if (context.getPlayer() instanceof ServerPlayer player) {
			context.queue(() -> ServerQuestFile.INSTANCE.getTeamData(player).ifPresent(data -> {
				Task task = data.getFile().getTask(message.taskId);
				if (!data.isLocked() && task instanceof DonationItemTask donation
						&& data.getFile() instanceof ServerQuestFile file
						&& data.canStartTasks(task.getQuest())) {
					file.withPlayerContext(player, () -> donation.completeDonation(data, player));
				}
			}));
		}
	}
}
