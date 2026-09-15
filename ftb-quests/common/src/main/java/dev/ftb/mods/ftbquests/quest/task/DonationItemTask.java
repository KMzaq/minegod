package dev.ftb.mods.ftbquests.quest.task;

import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import dev.ftb.mods.ftbquests.client.gui.quests.DonationItemsScreen;
import dev.ftb.mods.ftbquests.item.MissingItem;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.util.PlayerInventorySummary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * An item contribution task with an independently confirmed completion state.
 * Progress is retained after confirmation so external reward systems can read
 * the actual donated amount rather than a synthetic maximum value.
 */
public class DonationItemTask extends ItemTask {
	private long minimumCount;
	private boolean hideMaximum;
	private boolean manualCompletion;
	private boolean autoCompleteAtMaximum;

	public DonationItemTask(long id, Quest quest) {
		super(id, quest);
		minimumCount = 1L;
		hideMaximum = false;
		manualCompletion = true;
		autoCompleteAtMaximum = false;
	}

	@Override
	public TaskType getType() {
		return TaskTypes.DONATION_ITEM;
	}

	public long getMinimumCount() {
		return minimumCount;
	}

	public long getMaximumCount() {
		return getMaxProgress();
	}

	public boolean isManualCompletion() {
		return manualCompletion;
	}

	public boolean canCompleteManually(TeamData teamData) {
		return manualCompletion && !teamData.isCompleted(this) && teamData.getProgress(this) >= minimumCount;
	}

	public void setMaximumCount(long maximumCount) {
		setStackAndCount(getItemStack(), Math.max(minimumCount, maximumCount));
	}

	@Override
	public boolean shouldAutoComplete(TeamData teamData, long progress) {
		return (!manualCompletion && progress >= minimumCount)
				|| (autoCompleteAtMaximum && progress >= getMaximumCount());
	}

	@Override
	public boolean hideMaximumProgress() {
		return hideMaximum;
	}

	@Override
	public String formatMaxProgress() {
		return hideMaximum ? "???" : super.formatMaxProgress();
	}

	@Override
	public MutableComponent getAltTitle() {
		return hideMaximum ? Component.empty().append(getItemStack().getHoverName()) : super.getAltTitle();
	}

	@Override
	public void writeData(CompoundTag nbt, HolderLookup.Provider provider) {
		super.writeData(nbt, provider);
		nbt.putLong("minimum_count", minimumCount);
		nbt.putLong("maximum_count", getMaximumCount());
		if (hideMaximum) nbt.putBoolean("hide_maximum", true);
		if (!manualCompletion) nbt.putBoolean("manual_completion", false);
		if (autoCompleteAtMaximum) nbt.putBoolean("auto_complete_at_maximum", true);
	}

	@Override
	public void readData(CompoundTag nbt, HolderLookup.Provider provider) {
		super.readData(nbt, provider);
		long maximum = Math.max(1L, nbt.contains("maximum_count") ? nbt.getLong("maximum_count") : getMaxProgress());
		minimumCount = Math.clamp(nbt.contains("minimum_count") ? nbt.getLong("minimum_count") : 1L, 1L, maximum);
		setMaximumCount(maximum);
		hideMaximum = nbt.getBoolean("hide_maximum");
		manualCompletion = !nbt.contains("manual_completion") || nbt.getBoolean("manual_completion");
		autoCompleteAtMaximum = nbt.getBoolean("auto_complete_at_maximum");
	}

	@Override
	public void writeNetData(RegistryFriendlyByteBuf buffer) {
		super.writeNetData(buffer);
		buffer.writeVarLong(minimumCount);
		buffer.writeBoolean(hideMaximum);
		buffer.writeBoolean(manualCompletion);
		buffer.writeBoolean(autoCompleteAtMaximum);
	}

	@Override
	public void readNetData(RegistryFriendlyByteBuf buffer) {
		super.readNetData(buffer);
		minimumCount = Math.clamp(buffer.readVarLong(), 1L, getMaximumCount());
		hideMaximum = buffer.readBoolean();
		manualCompletion = buffer.readBoolean();
		autoCompleteAtMaximum = buffer.readBoolean();
	}

	@Override
	@Environment(EnvType.CLIENT)
	public void fillConfigGroup(ConfigGroup config) {
		fillItemConfigGroup(config, false);
		config.addLong("minimum_count", minimumCount,
				value -> minimumCount = Math.clamp(value, 1L, getMaximumCount()), 1L, 1L, Long.MAX_VALUE);
		config.addLong("maximum_count", getMaximumCount(), this::setMaximumCount,
				getMaximumCount(), 1L, Long.MAX_VALUE);
		config.addBool("hide_maximum", hideMaximum, value -> hideMaximum = value, false);
		config.addBool("manual_completion", manualCompletion, value -> manualCompletion = value, true);
		config.addBool("auto_complete_at_maximum", autoCompleteAtMaximum,
				value -> autoCompleteAtMaximum = value, false);
	}

	@Override
	@Environment(EnvType.CLIENT)
	public void onButtonClicked(dev.ftb.mods.ftblibrary.ui.Button button, boolean canClick) {
		button.playClickSound();
		new DonationItemsScreen(this, canClick).openGui();
	}

	@Override
	@Environment(EnvType.CLIENT)
	public void addMouseOverText(TooltipList list, TeamData teamData) {
		super.addMouseOverText(list, teamData);
		if (!teamData.isCompleted(this)) {
			list.blankLine();
			list.add(Component.translatable("ftbquests.task.ftbquests.donation_item.minimum", minimumCount)
					.withStyle(ChatFormatting.GRAY));
			if (canCompleteManually(teamData)) {
				list.add(Component.translatable("ftbquests.task.ftbquests.donation_item.ready")
						.withStyle(ChatFormatting.GREEN));
			}
		}
	}

	@Override
	public void submitTask(TeamData teamData, ServerPlayer player, ItemStack craftedItem) {
		if (isTaskScreenOnly() || !checkTaskSequence(teamData) || teamData.isCompleted(this)
				|| getItemStack().getItem() instanceof MissingItem
				|| craftedItem.getItem() instanceof MissingItem) {
			return;
		}

		if (!consumesResources()) {
			if (isOnlyFromCrafting()) {
				if (!craftedItem.isEmpty() && test(craftedItem)) {
					teamData.addProgress(this, craftedItem.getCount());
				}
			} else {
				long matching = 0L;
				for (ItemStack stack : PlayerInventorySummary.getRelevantItems(getItemStack())) {
					if (test(stack)) {
						matching = Math.min(getMaximumCount(), matching + stack.getCount());
					}
				}
				if (matching > teamData.getProgress(this)) {
					teamData.setProgress(this, matching);
				}
			}
		} else if (craftedItem.isEmpty()) {
			boolean changed = false;
			for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
				ItemStack stack = player.getInventory().items.get(slot);
				ItemStack remainder = insert(teamData, stack, false);
				if (stack != remainder) {
					changed = true;
					player.getInventory().items.set(slot, remainder.isEmpty() ? ItemStack.EMPTY : remainder);
				}
			}
			if (changed) {
				player.getInventory().setChanged();
				player.containerMenu.broadcastChanges();
			}
		}
	}

	/** Server-side final confirmation; does not alter the accumulated progress. */
	public boolean completeDonation(TeamData teamData, ServerPlayer player) {
		if (!canCompleteManually(teamData) || !checkTaskSequence(teamData)) {
			return false;
		}
		teamData.markTaskCompleted(this);
		return true;
	}
}
