package com.sande.mythictrpg.client.raid;

import com.sande.mythictrpg.network.RaidPagePayload;
import com.sande.mythictrpg.network.RaidRequestPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Uses server-issued rows only; no optimistic membership or local raid state. */
public final class RaidScreen extends Screen {
    private RaidPagePayload page;
    private int selected;
    private int elapsed;
    private int scroll;
    private boolean waiting;
    private String pending = "";
    public RaidScreen(RaidPagePayload page) { super(Component.literal("레이드")); this.page = page; }
    public void receive(RaidPagePayload next) {
        String key = selected < page.rows().size() ? page.rows().get(selected).key() : "";
        page = next; selected = 0;
        for (int i = 0; i < next.rows().size(); i++) if (next.rows().get(i).key().equals(key)) selected = i;
        waiting = false; elapsed = 0; pending = ""; rebuild();
    }
    @Override protected void init() { rebuild(); }
    private int panelWidth() { return Math.min(600, width - 20); }
    private int left() { return (width - panelWidth()) / 2; }
    private int listWidth() { return Math.max(90, panelWidth() * 2 / 5); }
    private int rowHeight() { return Math.clamp((height - 108) / 6, 18, 28); }
    private void rebuild() {
        clearWidgets();
        for (int i = 0; i < page.rows().size(); i++) {
            int index = i; var row = page.rows().get(i);
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(row.title(), listWidth() - 15)), b -> { selected = index; scroll = 0; rebuild(); })
                    .bounds(left(), 38 + i * rowHeight(), listWidth(), rowHeight() - 2).build());
        }
        int y = height - 65;
        int x = left(), gap = 4, buttonWidth = Math.max(38, (panelWidth() - 4 * gap) / 5);
        var actions = new RaidRequestPayload.Action[]{RaidRequestPayload.Action.CREATE, RaidRequestPayload.Action.JOIN, RaidRequestPayload.Action.START, RaidRequestPayload.Action.LEAVE, RaidRequestPayload.Action.CANCEL};
        String[] labels = {"모집 생성", "참가", "대기 시작", "모집 탈퇴", "전체 취소"};
        for (int i = 0; i < actions.length; i++) {
            var action = actions[i];
            Button button = addRenderableWidget(Button.builder(Component.literal(labels[i]), b -> request(action)).bounds(x + i * (buttonWidth + gap), y, buttonWidth, 20).build());
            button.active = !waiting && selected < page.rows().size() && page.rows().get(selected).allows(action);
        }
        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> request(RaidRequestPayload.Action.PREVIOUS)).bounds(left(), height - 30, 25, 20).build());
        prev.active = !waiting && page.page() > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> request(RaidRequestPayload.Action.NEXT)).bounds(left() + 85, height - 30, 25, 20).build());
        next.active = !waiting && page.page() + 1 < page.pages();
        Button refresh = addRenderableWidget(Button.builder(Component.literal("새로고침"), b -> request(RaidRequestPayload.Action.REFRESH)).bounds(left() + panelWidth() - 150, height - 30, 85, 20).build());
        refresh.active = !waiting;
        addRenderableWidget(Button.builder(Component.literal("닫기"), b -> onClose()).bounds(left() + panelWidth() - 60, height - 30, 60, 20).build());
    }
    private void request(RaidRequestPayload.Action action) {
        if (waiting) return;
        PacketDistributor.sendToServer(new RaidRequestPayload(page.token(), action, action.bit() == 0 ? -1 : selected));
        waiting = true; elapsed = 0; pending = "서버 확인 중…"; rebuild();
    }
    @Override public void tick() {
        elapsed++;
        if (waiting && elapsed >= 100) { waiting = false; pending = "응답 지연: 새로고침 또는 다시 열어 주세요."; elapsed = 0; rebuild(); }
        else if (!waiting && elapsed >= 40) request(RaidRequestPayload.Action.REFRESH);
    }
    @Override public void onClose() {
        PacketDistributor.sendToServer(new RaidRequestPayload(page.token(), RaidRequestPayload.Action.CLOSE, -1));
        super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= left() + listWidth()) { scroll = Math.max(0, scroll - (int)Math.signum(vertical) * 2); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        int detailX = left() + listWidth() + 10, detailWidth = Math.max(40, panelWidth() - listWidth() - 10);
        if (selected < page.rows().size()) {
            var row = page.rows().get(selected);
            int y = 39;
            String text = row.title() + "\n상태: " + row.status() + "\n" + row.details();
            var lines = font.split(Component.literal(text), detailWidth);
            scroll = Math.min(scroll, Math.max(0, lines.size() - 1));
            for (var line : lines.subList(scroll, lines.size())) {
                if (y + font.lineHeight > height - 73) break;
                g.drawString(font, line, detailX, y, 0xFFFFFF); y += font.lineHeight + 2;
            }
        } else g.drawCenteredString(font, "조회 가능한 레이드가 없습니다.", width / 2, height / 2, 0xAAAAAA);
        String notice = pending.isEmpty() ? page.notice() : pending;
        g.drawCenteredString(font, font.plainSubstrByWidth(notice, panelWidth()), width / 2, height - 42, 0xFFD76A);
        g.drawString(font, (page.page() + 1) + "/" + page.pages(), left() + 34, height - 24, 0xAAAAAA);
        super.render(g, mouseX, mouseY, partialTick);
    }
}
