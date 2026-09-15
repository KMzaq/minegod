package dev.ftb.mods.ftbquests.client.gui.quests;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.SimpleTextButton;
import dev.ftb.mods.ftblibrary.ui.Theme;
import dev.ftb.mods.ftblibrary.ui.WidgetType;
import dev.ftb.mods.ftblibrary.ui.input.Key;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.client.gui.FTBQuestsTheme;
import dev.ftb.mods.ftbquests.net.CompleteDonationTaskMessage;
import dev.ftb.mods.ftbquests.net.SubmitTaskMessage;
import dev.ftb.mods.ftbquests.quest.task.DonationItemTask;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Item submission screen with a separately validated donation-completion action. */
public class DonationItemsScreen extends BaseScreen {
	private final DonationItemTask task;
	private final boolean canClick;
	private final SimpleTextButton backButton;
	private final SimpleTextButton donateButton;
	private final SimpleTextButton completeButton;

	public DonationItemsScreen(DonationItemTask task, boolean canClick) {
		this.task = task;
		this.canClick = canClick;

		backButton = new SimpleTextButton(this, Component.translatable("gui.back"), Color4I.empty()) {
			@Override
			public void onClicked(MouseButton button) {
				playClickSound();
				onBack();
			}
		};
		donateButton = new SimpleTextButton(this,
				Component.translatable("ftbquests.task.ftbquests.donation_item.donate"), Color4I.empty()) {
			@Override
			public void onClicked(MouseButton button) {
				playClickSound();
				NetworkManager.sendToServer(new SubmitTaskMessage(task.id));
				onBack();
			}

			@Override
			public WidgetType getWidgetType() {
				return canClick && task.consumesResources() && !task.isTaskScreenOnly()
						? super.getWidgetType() : WidgetType.DISABLED;
			}
		};
		completeButton = new SimpleTextButton(this,
				Component.translatable("ftbquests.task.ftbquests.donation_item.complete"), Color4I.empty()) {
			@Override
			public void onClicked(MouseButton button) {
				playClickSound();
				NetworkManager.sendToServer(new CompleteDonationTaskMessage(task.id));
				onBack();
			}

			@Override
			public WidgetType getWidgetType() {
				return canClick && ClientQuestFile.exists()
						&& task.canCompleteManually(ClientQuestFile.INSTANCE.selfTeamData)
						? super.getWidgetType() : WidgetType.DISABLED;
			}
		};
	}

	@Override
	public void addWidgets() {
		setSize(270, 100);
		backButton.setPosAndSize(10, 72, 75, 20);
		donateButton.setPosAndSize(95, 72, 80, 20);
		completeButton.setPosAndSize(185, 72, 75, 20);
		add(backButton);
		add(donateButton);
		add(completeButton);
	}

	@Override
	public Theme getTheme() {
		return FTBQuestsTheme.INSTANCE;
	}

	@Override
	public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
		super.drawBackground(graphics, theme, x, y, w, h);
		Component progressText = Component.literal("0 / " + task.formatMaxProgress());
		if (ClientQuestFile.exists()) {
			long progress = ClientQuestFile.INSTANCE.selfTeamData.getProgress(task);
			progressText = Component.literal(task.formatProgress(ClientQuestFile.INSTANCE.selfTeamData, progress)
					+ " / " + task.formatMaxProgress());
		}
		theme.drawString(graphics, task.getTitle(), x + w / 2, y + 8, Color4I.WHITE, Theme.CENTERED);
		theme.drawString(graphics, progressText, x + w / 2, y + 30, Color4I.WHITE, Theme.CENTERED);
		theme.drawString(graphics, Component.translatable("ftbquests.task.ftbquests.donation_item.minimum", task.getMinimumCount()),
				x + w / 2, y + 48, Color4I.GRAY, Theme.CENTERED);
	}

	@Override
	public boolean keyPressed(Key key) {
		if (super.keyPressed(key)) return true;
		if (key.esc()) {
			onBack();
			return true;
		}
		return false;
	}
}
