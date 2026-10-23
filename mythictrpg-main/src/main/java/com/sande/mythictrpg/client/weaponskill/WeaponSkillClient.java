package com.sande.mythictrpg.client.weaponskill;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.weaponskill.WeaponSkillRequest;
import com.sande.mythictrpg.weaponskill.WeaponSkillSnapshot;
import com.sande.mythictrpg.weaponskill.PrototypeInteractionPriority;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Locale;

/** Input and rendering only. The client never decides whether mana is spent or a skill succeeds. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID, value = Dist.CLIENT)
public final class WeaponSkillClient {
    private static WeaponSkillSnapshot snapshot;
    private static int elapsedTicks;
    private WeaponSkillClient() { }

    static void receive(WeaponSkillSnapshot incoming) {
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) return;
        snapshot = incoming; elapsedTicks = 0;
        BetterCombatClientBridge.cancelLockedUpswing();
    }

    /** Presentation gate shared by vanilla and Better Combat inputs; the server rechecks every hit. */
    public static boolean attacksLocked() {
        var client = Minecraft.getInstance();
        return snapshot != null && snapshot.actionLocked() && client.player != null && client.level != null;
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn event) { clear(); }
    private static void clear() { snapshot = null; elapsedTicks = 0; }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (snapshot != null && !Minecraft.getInstance().isPaused() && elapsedTicks < Integer.MAX_VALUE) elapsedTicks++;
    }

    @SubscribeEvent public static void use(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft client = Minecraft.getInstance();
        if (!event.isUseItem() || client.screen != null || client.player == null || !client.player.isAlive()
                || client.player.isSpectator() || client.player.isShiftKeyDown() || snapshot == null
                || !snapshot.matches(client.player.getInventory().selected, client.player.getMainHandItem())) return;
        if(PrototypeInteractionPriority.prototypeSkill(snapshot.skillId())&&PrototypeInteractionPriority.directlyTargeted(client.player))return;
        // NeoForge emits this event for both hands even when the main-hand event was canceled.
        // Consume both default uses, but submit only one input intent.
        event.setCanceled(true); event.setSwingHand(false);
        if (event.getHand() == InteractionHand.MAIN_HAND)
            PacketDistributor.sendToServer(new WeaponSkillRequest(snapshot.selectedSlot(), snapshot.skillId()));
    }

    static void render(GuiGraphics graphics, DeltaTracker ignored) {
        Minecraft client = Minecraft.getInstance();
        if (snapshot == null || client.options.hideGui || client.player == null || client.level == null
                || client.player.isSpectator()) return;
        boolean selected = snapshot.matches(client.player.getInventory().selected, client.player.getMainHandItem());
        int left = 8, top = 8, width = Math.min(180, Math.max(40, graphics.guiWidth() - 16));
        int bottom = top + (selected ? 54 : 23);
        graphics.fill(left - 3, top - 3, left + width + 3, bottom, 0xA018202C);
        graphics.drawString(client.font, String.format(Locale.ROOT, "마나 %.1f / %.1f", snapshot.mana(), snapshot.maximumMana()),
                left, top, 0xFFD7EAFF, true);
        graphics.fill(left, top + 12, left + width, top + 16, 0xFF304050);
        graphics.fill(left, top + 12, left + (int) Math.floor(width * snapshot.mana() / snapshot.maximumMana()), top + 16, 0xFF5EA8EF);
        if (!selected) return;
        int remaining = Math.max(0, snapshot.remainingTicks() - elapsedTicks);
        String skill = snapshot.skillName() + " · 마나 " + snapshot.manaCost();
        graphics.drawString(client.font, client.font.plainSubstrByWidth(skill, width), left, top + 22, 0xFFFFFFFF, true);
        String status = remaining > 0 ? String.format(Locale.ROOT, "재사용 %.1f초", remaining / 20.0)
                : snapshot.mana() < snapshot.manaCost() ? "마나 부족" : "우클릭: 스킬";
        graphics.drawString(client.font, status, left, top + 32, remaining > 0 ? 0xFFFFD48A : 0xFFB5D6F4, true);
        graphics.drawString(client.font, "Shift+우클릭: 기본 상호작용", left, top + 42, 0xFFB0B8C4, false);
    }
}
