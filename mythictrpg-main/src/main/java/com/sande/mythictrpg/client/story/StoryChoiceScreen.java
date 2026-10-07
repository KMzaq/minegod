package com.sande.mythictrpg.client.story;

import com.sande.mythictrpg.network.StoryChoicePagePayload;
import com.sande.mythictrpg.network.StoryChoiceSelectionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Paged Story choices. Closing defers, and /mythstory choices reopens the server's current view. */
public final class StoryChoiceScreen extends Screen {
    private final StoryChoicePagePayload page;
    private int optionPage;
    private int optionsPerPage;
    private boolean submitting;

    public StoryChoiceScreen(StoryChoicePagePayload page) {
        super(Component.literal("스토리 선택"));
        this.page = page;
    }

    @Override protected void init() {
        StoryChoicePagePayload.Offer offer = page.offer().orElseThrow();
        {
            Button details = Button.builder(Component.literal("사건 상세"), button -> {})
                    .bounds(width / 2 - 45, 51, 90, 20).build();
            details.setTooltip(Tooltip.create(Component.literal(offer.displayName()
                    + (offer.description().isEmpty() ? "" : "\n" + offer.description()))));
            addRenderableWidget(details);
        }
        optionsPerPage = Math.max(1, Math.min(5, (height - 145) / 42));
        int optionPages = (offer.options().size() + optionsPerPage - 1) / optionsPerPage;
        optionPage = Math.max(0, Math.min(optionPage, optionPages - 1));
        int from = optionPage * optionsPerPage;
        int to = Math.min(offer.options().size(), from + optionsPerPage);
        for (int index = from; index < to; index++) {
            var option = offer.options().get(index);
            int row = index - from;
            Button choice = Button.builder(Component.literal(option.displayName()), button -> submit(option))
                    .bounds(width / 2 - 145, 76 + row * 42, 290, 20).build();
            choice.setTooltip(Tooltip.create(Component.literal(option.displayName()
                    + (option.description().isEmpty() ? "" : "\n" + option.description()))));
            addRenderableWidget(choice);
        }

        int navigationY = height - 52;
        if (page.page() > 0) addRenderableWidget(Button.builder(Component.literal("이전 사건"),
                button -> StoryChoiceClientController.INSTANCE.browse(page.page() - 1))
                .bounds(width / 2 - 145, navigationY, 90, 20).build());
        if (page.page() + 1 < page.total()) addRenderableWidget(Button.builder(Component.literal("다음 사건"),
                button -> StoryChoiceClientController.INSTANCE.browse(page.page() + 1))
                .bounds(width / 2 + 55, navigationY, 90, 20).build());
        if (optionPage > 0) addRenderableWidget(Button.builder(Component.literal("이전 선택지"), button -> {
            optionPage--; rebuildWidgets();
        }).bounds(width / 2 - 145, height - 27, 90, 20).build());
        if (optionPage + 1 < optionPages) addRenderableWidget(Button.builder(Component.literal("다음 선택지"), button -> {
            optionPage++; rebuildWidgets();
        }).bounds(width / 2 - 45, height - 27, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("나중에"), button -> onClose())
                .bounds(width / 2 + 55, height - 27, 90, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        var offer = page.offer().orElseThrow();
        graphics.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal(offer.displayName()), width / 2, 34, 0xFFD76A);
        int from = optionPage * optionsPerPage;
        int to = Math.min(offer.options().size(), from + optionsPerPage);
        for (int index = from; index < to; index++) {
            var option = offer.options().get(index);
            if (!option.description().isEmpty()) {
                graphics.drawCenteredString(font, font.split(Component.literal(option.description()),
                        Math.min(420, width - 40)).getFirst(), width / 2,
                        98 + (index - from) * 42, 0xB8B8B8);
            }
        }
        graphics.drawCenteredString(font, Component.literal("사건 " + (page.page() + 1) + "/" + page.total()
                + " · 선택지 " + (optionPage + 1) + "/"
                + ((offer.options().size() + optionsPerPage - 1) / optionsPerPage)),
                width / 2, height - 68, 0x999999);
        if (submitting) graphics.drawCenteredString(font, Component.literal("서버 확인 중…"),
                width / 2, height - 82, 0xFFFFAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void submit(StoryChoicePagePayload.Option option) {
        if (submitting) return;
        submitting = true;
        var offer = page.offer().orElseThrow();
        PacketDistributor.sendToServer(new StoryChoiceSelectionPayload(
                offer.instanceId(), offer.revision(), option.id()));
    }

    @Override public void onClose() {
        StoryChoiceClientController.INSTANCE.dismiss();
        super.onClose();
    }
}
