package com.sande.mythictrpg.client.raid;

import com.sande.mythictrpg.network.RaidPagePayload;
import com.sande.mythictrpg.network.RaidRequestPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Uses server-issued rows and view only; no optimistic membership or local raid state. */
public final class RaidScreen extends Screen {
    private static final int TAB_Y = 25;
    private static final int CONTENT_Y = 54;
    private static final RaidPagePayload.View[] VIEWS = {
            RaidPagePayload.View.RECRUITING, RaidPagePayload.View.IN_PROGRESS, RaidPagePayload.View.HISTORY};
    private static final RaidRequestPayload.Action[] VIEW_ACTIONS = {
            RaidRequestPayload.Action.SHOW_RECRUITING, RaidRequestPayload.Action.SHOW_IN_PROGRESS,
            RaidRequestPayload.Action.SHOW_HISTORY};
    private static final String[] VIEW_LABELS = {"모집 중", "진행 중", "완료 기록"};

    private RaidPagePayload page;
    private int selected;
    private int elapsed;
    private int scroll;
    private int listScroll;
    private boolean waiting;
    private String pending = "";

    public RaidScreen(RaidPagePayload page) {
        super(Component.literal("레이드"));
        this.page = page;
    }

    public void receive(RaidPagePayload next) {
        boolean changedView = page.view() != next.view();
        String key = !changedView && selected < page.rows().size() ? page.rows().get(selected).key() : "";
        page = next;
        selected = 0;
        boolean preserved = false;
        for (int i = 0; i < next.rows().size(); i++) {
            if (next.rows().get(i).key().equals(key)) { selected = i; preserved = true; break; }
        }
        if (changedView || !preserved) { scroll = 0; listScroll = 0; }
        waiting = false;
        elapsed = 0;
        pending = "";
        rebuild();
    }

    @Override protected void init() { rebuild(); }

    private int panelWidth() { return Math.max(1, Math.min(600, width - 20)); }
    private int left() { return (width - panelWidth()) / 2; }
    private int listWidth() { return Math.max(1, panelWidth() * 2 / 5); }
    private int detailX() { return left() + listWidth() + 10; }
    private int detailWidth() { return Math.max(1, panelWidth() - listWidth() - 10); }
    private int actionY() { return height - 70; }
    private int contentBottom() { return Math.max(CONTENT_Y, actionY() - 8); }
    private int contentHeight() { return contentBottom() - CONTENT_Y; }
    private int rowHeight() { return Math.min(34, Math.max(16, contentHeight())); }
    private int visibleRows() { return contentHeight() / rowHeight(); }
    private int maximumListScroll() { return Math.max(0, page.rows().size() - visibleRows()); }
    private int tabWidth() { return Math.max(1, (panelWidth() - 8) / 3); }
    private int navigationWidth() { return Math.min(110, panelWidth() / 2); }

    private void rebuild() {
        clearWidgets();
        for (int i = 0; i < VIEWS.length; i++) {
            int index = i;
            boolean current = page.view() == VIEWS[i];
            Component label = Component.literal((current ? "● " : "") + VIEW_LABELS[i]);
            if (current) label = label.copy().withStyle(ChatFormatting.GOLD);
            Button tab = addRenderableWidget(Button.builder(label, b -> request(VIEW_ACTIONS[index]))
                    .bounds(left() + i * (tabWidth() + 4), TAB_Y, tabWidth(), 20).build());
            tab.active = !waiting && !current;
            tab.setTooltip(Tooltip.create(Component.literal(VIEW_LABELS[i])));
        }

        listScroll = Math.min(listScroll, maximumListScroll());
        int end = Math.min(page.rows().size(), listScroll + visibleRows());
        for (int i = listScroll; i < end; i++) {
            int index = i;
            var row = page.rows().get(i);
            String label = (i == selected ? "› " : "") + row.title();
            if (rowHeight() < 31) label = statusLabel(row.status()) + " · " + label;
            Component title = Component.literal(font.plainSubstrByWidth(label, Math.max(1, listWidth() - 16)));
            if (i == selected) title = title.copy().withStyle(ChatFormatting.GOLD);
            Button rowButton = addRenderableWidget(Button.builder(title, b -> {
                selected = index; scroll = 0; rebuild();
            }).bounds(left(), CONTENT_Y + (i - listScroll) * rowHeight(), Math.max(1, listWidth() - 6), Math.min(20, rowHeight() - 2)).build());
            rowButton.active = !waiting;
            rowButton.setTooltip(Tooltip.create(Component.literal(row.title() + "\n" + statusLabel(row.status()))));
        }

        int gap = 4;
        int buttonWidth = Math.max(1, (panelWidth() - 4 * gap) / 5);
        var actions = new RaidRequestPayload.Action[]{RaidRequestPayload.Action.CREATE, RaidRequestPayload.Action.JOIN,
                RaidRequestPayload.Action.START, RaidRequestPayload.Action.LEAVE, RaidRequestPayload.Action.CANCEL};
        String[] labels = {"모집 생성", "참가", "대기 시작", "모집 탈퇴", "전체 취소"};
        for (int i = 0; i < actions.length; i++) {
            var action = actions[i];
            Button button = addRenderableWidget(Button.builder(
                            Component.literal(font.plainSubstrByWidth(labels[i], Math.max(1, buttonWidth - 6))), b -> request(action))
                    .bounds(left() + i * (buttonWidth + gap), actionY(), buttonWidth, 20).build());
            button.active = !waiting && page.view() != RaidPagePayload.View.HISTORY
                    && selected < page.rows().size() && page.rows().get(selected).allows(action);
            button.setTooltip(Tooltip.create(Component.literal(labels[i])));
        }

        int footerY = height - 27;
        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> request(RaidRequestPayload.Action.PREVIOUS))
                .bounds(left(), footerY, 20, 20).build());
        prev.active = !waiting && page.page() > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> request(RaidRequestPayload.Action.NEXT))
                .bounds(left() + navigationWidth() - 20, footerY, 20, 20).build());
        next.active = !waiting && page.page() + 1 < page.pages();
        int remaining = Math.max(2, panelWidth() - navigationWidth() - 8);
        int refreshWidth = Math.max(1, remaining * 3 / 5);
        int closeWidth = Math.max(1, remaining - refreshWidth);
        Button refresh = addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth("새로고침", Math.max(1, refreshWidth - 6))),
                        b -> request(RaidRequestPayload.Action.REFRESH))
                .bounds(left() + navigationWidth() + 4, footerY, refreshWidth, 20).build());
        refresh.active = !waiting;
        refresh.setTooltip(Tooltip.create(Component.literal("새로고침")));
        addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth("닫기", Math.max(1, closeWidth - 6))), b -> onClose())
                .bounds(left() + navigationWidth() + refreshWidth + 8, footerY, closeWidth, 20).build());
    }

    private void request(RaidRequestPayload.Action action) {
        if (waiting) return;
        PacketDistributor.sendToServer(new RaidRequestPayload(page.token(), action, action.bit() == 0 ? -1 : selected));
        waiting = true;
        elapsed = 0;
        pending = "서버 확인 중…";
        rebuild();
    }

    @Override public void tick() {
        elapsed++;
        if (waiting && elapsed >= 100) {
            waiting = false;
            pending = "응답 지연: 새로고침 또는 다시 열어 주세요.";
            elapsed = 0;
            rebuild();
        } else if (!waiting && elapsed >= 40) request(RaidRequestPayload.Action.REFRESH);
    }

    @Override public void onClose() {
        PacketDistributor.sendToServer(new RaidRequestPayload(page.token(), RaidRequestPayload.Action.CLOSE, -1));
        super.onClose();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y >= CONTENT_Y && y < contentBottom() && x >= left() && x < left() + panelWidth()) {
            int delta = -(int) Math.signum(vertical);
            if (x < left() + listWidth()) {
                listScroll = Math.clamp(listScroll + delta, 0, maximumListScroll());
                rebuild();
            } else scroll = Math.max(0, scroll + delta * 2);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        for (int i = 0; i < VIEWS.length; i++) {
            if (page.view() == VIEWS[i]) g.fill(left() + i * (tabWidth() + 4), TAB_Y - 2,
                    left() + i * (tabWidth() + 4) + tabWidth(), TAB_Y, 0xFFFFD76A);
        }
        if (rowHeight() >= 31) {
            int end = Math.min(page.rows().size(), listScroll + visibleRows());
            for (int i = listScroll; i < end; i++) {
                var row = page.rows().get(i);
                g.drawString(font, statusLabel(row.status()), left() + 5,
                        CONTENT_Y + (i - listScroll) * rowHeight() + 22, statusColor(row.status()));
            }
        }
        if (maximumListScroll() > 0 && visibleRows() > 0) {
            int barX = left() + listWidth() - 2;
            g.fill(barX, CONTENT_Y, barX + 2, contentBottom(), 0xFF454545);
            int thumbHeight = Math.max(8, contentHeight() * visibleRows() / page.rows().size());
            int thumbY = CONTENT_Y + (contentHeight() - thumbHeight) * listScroll / maximumListScroll();
            g.fill(barX, thumbY, barX + 2, thumbY + thumbHeight, 0xFFB6C4CD);
        }
        if (selected < page.rows().size()) {
            var row = page.rows().get(selected);
            String text = row.title() + "\n상태: " + statusLabel(row.status()) + "\n" + row.details();
            var lines = font.split(Component.literal(text), detailWidth());
            int visibleLines = contentHeight() / (font.lineHeight + 2);
            scroll = Math.min(scroll, Math.max(0, lines.size() - Math.max(1, visibleLines)));
            int y = CONTENT_Y;
            for (var line : lines.subList(scroll, lines.size())) {
                if (y + font.lineHeight > contentBottom()) break;
                g.drawString(font, line, detailX(), y, 0xFFFFFF);
                y += font.lineHeight + 2;
            }
        } else if (contentHeight() >= font.lineHeight) {
            g.drawCenteredString(font, font.plainSubstrByWidth(emptyMessage(), panelWidth()),
                    width / 2, CONTENT_Y + contentHeight() / 2 - font.lineHeight / 2, 0xAAAAAA);
        }
        String notice = pending.isEmpty() ? page.notice() : pending;
        g.drawCenteredString(font, font.plainSubstrByWidth(notice, panelWidth()), width / 2, height - 44, 0xFFD76A);
        g.drawCenteredString(font, font.plainSubstrByWidth((page.page() + 1) + "/" + page.pages(),
                        Math.max(1, navigationWidth() - 44)),
                left() + navigationWidth() / 2, height - 21, 0xAAAAAA);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private String emptyMessage() {
        return switch (page.view()) {
            case RECRUITING -> "현재 참가할 모집이 없습니다.";
            case IN_PROGRESS -> "진행 중인 레이드가 없습니다.";
            case HISTORY -> "완료된 레이드 기록이 없습니다.";
        };
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "새 모집" -> "새 모집";
            case "FORMING" -> "모집 중";
            case "QUEUED" -> "대기 중";
            case "STARTING" -> "전투 준비";
            case "ACTIVE" -> "전투 중";
            case "VICTORY_PENDING" -> "승리 정산 중";
            case "CLEANUP_PENDING" -> "정리 중";
            case "SUCCEEDED" -> "클리어";
            case "FAILED" -> "실패";
            case "CANCELLED" -> "취소";
            default -> "상태 확인 필요";
        };
    }

    private static int statusColor(String status) {
        return switch (status) {
            case "SUCCEEDED" -> 0x69D8A6;
            case "FAILED" -> 0xFF9494;
            case "CANCELLED" -> 0xAAAAAA;
            case "새 모집", "FORMING" -> 0xFFD76A;
            default -> 0xA8CFF5;
        };
    }
}
