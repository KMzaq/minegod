package com.sande.mythictrpg.client.understanding;

import com.sande.mythictrpg.understanding.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Explicit self-inspection only. No automatic combat popup or optimistic persistence. */
public final class UnderstandingScreen extends Screen {
    private UnderstandingPayload page;
    private int selected, scroll;
    private EditBox purpose;
    private long pendingSince;
    private long requestSequence;
    public UnderstandingScreen(UnderstandingPayload page) {
        super(Component.literal("나의 플레이 기록과 이해")); this.page = page;
        pendingSince = page.pending() ? System.currentTimeMillis() : 0;
    }
    public void receive(UnderstandingPayload value) {
        if (!page.token().equals(value.token())) return;
        page = value; pendingSince = page.pending() ? System.currentTimeMillis() : 0;
        selected = Math.min(selected, Math.max(0, page.cards().size() - 1)); rebuildWidgets();
    }
    @Override protected void init() {
        int left = Math.max(8, (width - 460) / 2), available = Math.min(460, width - 16);
        String editing = purpose == null ? "" : purpose.getValue();
        purpose = new EditBox(font, left, height - 55, Math.max(70, available - 154), 20, Component.literal("현재 목적 (본인 진술)"));
        purpose.setMaxLength(160); purpose.setValue(editing); purpose.setHint(Component.literal("현재 목적을 직접 적기 · NPC에게 자동 공유 안 됨"));
        purpose.setEditable(!page.pending() && page.active()); addRenderableWidget(purpose);
        int controlWidth=(available-12)/4;
        button(left, 30, controlWidth, page.active() ? "분석 중단" : "분석 시작", () -> send(page.active() ? UnderstandingRequest.Action.PAUSE : UnderstandingRequest.Action.START, "", ""), !page.pending());
        button(left + controlWidth + 4, 30, controlWidth, "새로고침", () -> send(UnderstandingRequest.Action.REFRESH, "", ""), !page.pending());
        button(left + 2*(controlWidth+4), 30, controlWidth, "진행 도움 전환", () -> send(UnderstandingRequest.Action.ADVICE, "", ""), page.active()&&!page.pending());
        button(left + 3*(controlWidth+4), 30, controlWidth, "닫기", this::onClose, true);
        button(left, 55, 35, "<", () -> { selected = Math.max(0, selected - 1); scroll = 0; rebuildWidgets(); }, selected > 0);
        button(left + 40, 55, 35, ">", () -> { selected = Math.min(page.cards().size() - 1, selected + 1); scroll = 0; rebuildWidgets(); }, selected + 1 < page.cards().size());
        if (!page.cards().isEmpty()) {
            var c = page.cards().get(selected);
            if (PlayerUnderstanding.validFeedbackKey(c.key()) && !c.key().equals("purpose")&&!c.key().equals(SupportSharing.KEY))
                button(left + 82, 55, 110, c.disputed() ? "이의 제기 취소" : "이 해석에 이의 제기",
                        () -> send(c.disputed() ? UnderstandingRequest.Action.RESTORE : UnderstandingRequest.Action.DISPUTE, c.key(), ""), !page.pending());
        }
        boolean shared=page.cards().stream().anyMatch(c->c.key().equals(SupportSharing.KEY)&&c.assessment().equals("허용"));
        button(left+200,55,available-200,shared?"NPC 공유 끄기":"NPC 공유 켜기",()->send(UnderstandingRequest.Action.SHARING,"",""),page.active()&&!page.pending());
        button(left + available - 148, height - 55, 70, "목적 저장", () -> send(UnderstandingRequest.Action.DECLARE, "purpose", purpose.getValue()), page.active() && !page.pending());
        button(left + available - 74, height - 55, 74, "목적 철회", () -> send(UnderstandingRequest.Action.RETRACT, "purpose", ""), page.active() && !page.pending());
    }
    private void button(int x, int y, int w, String text, Runnable action, boolean active) {
        var b = Button.builder(Component.literal(text), ignored -> action.run()).bounds(x, y, w, 20).build(); b.active = active; addRenderableWidget(b);
    }
    private void send(UnderstandingRequest.Action action, String key, String text) {
        PacketDistributor.sendToServer(new UnderstandingRequest(page.token(), ++requestSequence, action, key, text));
        pendingSince = System.currentTimeMillis();
        page = new UnderstandingPayload(page.token(), false, page.active(), true, "서버 확인 중… 저장 완료 전에는 반영되지 않습니다.", page.cards());
        rebuildWidgets();
    }
    @Override public void tick() {
        super.tick();
        if (pendingSince != 0 && System.currentTimeMillis() - pendingSince > 10000) {
            pendingSince = 0;
            page = new UnderstandingPayload(page.token(), false, page.active(), false, "확인이 지연됩니다. 새로고침하거나 닫은 뒤 /mythprofile로 다시 여세요.", page.cards());
            rebuildWidgets();
        }
    }
    private List<String> lines() {
        var lines = new ArrayList<String>(); lines.add(page.notice()); lines.add("");
        if (page.cards().isEmpty()) { lines.add("분석 시작 전 기록과 예전 테스트 자료는 자동으로 가져오지 않습니다."); return lines; }
        var c = page.cards().get(selected);
        lines.add((selected + 1) + "/" + page.cards().size() + "  " + c.title()); lines.add(c.assessment()); lines.add(c.explanation()); lines.add("");
        lines.add("근거 (최신 최대 3건)"); lines.addAll(c.evidence());
        if (c.evidence().isEmpty()) lines.add("표시할 근거가 없습니다. 부정적인 성향을 의미하지 않습니다.");
        return lines;
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        int left = Math.max(8, (width - 460) / 2), contentWidth = Math.min(460, width - 16), top = 83, bottom = Math.max(top + 10, height - 65);
        var wrapped = new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for (String line : lines()) wrapped.addAll(font.split(Component.literal(line.isEmpty() ? " " : line), contentWidth));
        int maxScroll = Math.max(0, wrapped.size() * 12 - (bottom - top)); scroll = Math.min(scroll, maxScroll);
        g.enableScissor(left, top, left + contentWidth, bottom);
        int y = top - scroll; for (var line : wrapped) { g.drawString(font, line, left, y, 0xDDDDDD); y += 12; }
        g.disableScissor();
        int footerY = height - 27;
        for (var line : font.split(Component.literal("마우스 휠: 내용 스크롤 · 추정은 게임 수치를 바꾸지 않습니다."), contentWidth)) {
            if (footerY > height - 9) break;
            g.drawString(font, line, left, footerY, 0xAAAAAA); footerY += 10;
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { scroll = Math.max(0, scroll - (int)(vertical * 24)); return true; }
    @Override public void onClose() {
        PacketDistributor.sendToServer(new UnderstandingRequest(page.token(), ++requestSequence, UnderstandingRequest.Action.CLOSE, "", "")); super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
}
