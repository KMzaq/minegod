package com.sande.mythictrpg.rumor;

import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.function.Function;

/** Read-only projection of existing receipts. Knowing a claim is not believing it or permission to publish it. */
public final class RoomRumorAccess {
    private RoomRumorAccess() { }

    public static List<RumorLedger.HeardRumor> heard(MinecraftServer server, UUID subject, String originGod,
            Set<UUID> playerAudience, Set<String> godAudience, boolean publicRoom) {
        return project(server, subject, originGod, playerAudience, godAudience, publicRoom, false);
    }

    /** A past speaker may have left. Its attributed statement still needs its own current proof and every current listener's receipt. */
    public static List<RumorLedger.HeardRumor> referenced(MinecraftServer server, UUID subject, String originGod,
            Set<UUID> playerAudience, Set<String> godAudience, boolean publicRoom) {
        return project(server, subject, originGod, playerAudience, godAudience, publicRoom, true);
    }

    private static List<RumorLedger.HeardRumor> project(MinecraftServer server, UUID subject, String originGod,
            Set<UUID> playerAudience, Set<String> godAudience, boolean publicRoom, boolean historicalReference) {
        if (!server.isSameThread()) throw new IllegalStateException("Room rumor access requires game thread");
        if (MemoryFoundationSettings.mode() != MemoryFoundationSettings.Mode.RUMOR_TEST
                || !scopeAllowed(subject, originGod, playerAudience, godAudience, publicRoom, historicalReference)) return List.of();
        var state = RumorSavedData.get(server);
        // Preserve the old one-God read path; a multi-God audience additionally needs current game receipts.
        return state.heard(server, subject, originGod, playerAudience).stream().filter(row -> godAudience.equals(Set.of(originGod))
                || sharedClaim(row, originGod, godAudience,
                        god -> CourierRumorService.heardOne(server, subject, god, row.rootId(), playerAudience))).toList();
    }

    static boolean scopeAllowed(UUID subject, String originGod, Set<UUID> players, Set<String> gods, boolean publicRoom) {
        return scopeAllowed(subject, originGod, players, gods, publicRoom, false);
    }

    static boolean scopeAllowed(UUID subject, String originGod, Set<UUID> players, Set<String> gods, boolean publicRoom, boolean historical) {
        return !publicRoom && subject != null && players != null && players.contains(subject) && players.size() <= 16
                && originGod != null && !originGod.isBlank() && gods != null && !gods.isEmpty() && gods.size() <= 16
                && (historical || gods.contains(originGod));
    }

    /** Recipient attitudes may differ. Only identical claim content/revision counts as shared knowledge. */
    static boolean sharedClaim(RumorLedger.HeardRumor source, String originGod, Set<String> gods,
            Function<String, Optional<RumorLedger.HeardRumor>> received) {
        if (source == null || originGod == null || gods == null || gods.isEmpty() || gods.size() > 16) return false;
        var required = new LinkedHashSet<>(gods); required.add(originGod);
        for (String god : required) {
            var receipt = received.apply(god);
            if (receipt.isEmpty()) return false;
            var other = receipt.orElseThrow();
            if (!source.rootId().equals(other.rootId()) || source.revision() != other.revision()
                    || !source.text().equals(other.text()) || !source.epithet().equals(other.epithet())) return false;
        }
        return true;
    }
}
