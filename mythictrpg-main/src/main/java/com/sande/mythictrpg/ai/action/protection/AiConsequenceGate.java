package com.sande.mythictrpg.ai.action.protection;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Reusable fail-closed boundary for future property-loss executors. It is not itself a block/item/curse executor.
 * A model saying "I warned them" cannot issue a receipt. Restart/disconnect discards pending receipts.
 */
public final class AiConsequenceGate {
    private static final int MAX_PENDING = 256;
    private final Map<UUID, Notice> notices = new LinkedHashMap<>();

    public record LossScope(UUID ownerId, ResourceLocation godId, String targetKey, String targetFingerprint,
            UUID interactionId, long revision, String consequence) {
        public LossScope {
            Objects.requireNonNull(ownerId); Objects.requireNonNull(godId); Objects.requireNonNull(interactionId);
            targetKey = text(targetKey, 256); targetFingerprint = text(targetFingerprint, 256); consequence = text(consequence, 512);
            if (revision < 0) throw new IllegalArgumentException("Negative consequence revision");
        }
    }
    public record ResponseOption(String id, String description) {
        public ResponseOption { id = text(id, 64); description = text(description, 256); }
    }
    public enum ResponseResult { PREVENTED_LOSS, CONSEQUENCE_STILL_VALID, UNRESOLVED }
    @FunctionalInterface public interface ResponseResolver {
        /** Execute/revalidate the actual response, not an AI opinion about whether the player had a chance. */
        ResponseResult resolve(ServerPlayer player, LossScope scope, String selectedOption);
    }
    private static final class Notice {
        private final ServerPlayer recipient;
        private final LossScope scope;
        private final List<ResponseOption> options;
        private final ResponseResolver resolver;
        private boolean responseResolved;
        private Notice(ServerPlayer recipient, LossScope scope, List<ResponseOption> options, ResponseResolver resolver) {
            this.recipient = recipient; this.scope = scope; this.options = options; this.resolver = resolver;
        }
    }

    /** Only the game constructs the scope/options/resolver. Call when a real response UI/command route is installed. */
    public UUID warn(ServerPlayer owner, LossScope scope, List<ResponseOption> options,
            ResponseResolver resolver, BooleanSupplier responseRouteAvailable) {
        requireLive(owner);
        if (!owner.getUUID().equals(scope.ownerId()) || options == null || options.size() < 2 || options.size() > 8
                || options.stream().map(ResponseOption::id).distinct().count() != options.size()
                || notices.size() >= MAX_PENDING || !responseRouteAvailable.getAsBoolean())
            throw new IllegalArgumentException("A delivered warning needs its owner and usable response routes");
        Notice notice = new Notice(owner, scope, List.copyOf(options), Objects.requireNonNull(resolver));
        UUID token = UUID.randomUUID();
        String choices = options.stream().map(option -> option.id() + ": " + option.description()).collect(java.util.stream.Collectors.joining(" / "));
        owner.sendSystemMessage(Component.literal("[영구 손실 경고] " + scope.consequence() + "\n대응 방법: " + choices));
        notices.put(token, notice);
        return token;
    }

    /** A player's explicit server-side response is necessary. Silence and elapsed time alone are never enough. */
    public ResponseResult respond(ServerPlayer owner, UUID token, String choice, LossScope currentScope) {
        requireLive(owner);
        Notice notice = notices.get(token);
        if (!current(notice, owner, currentScope) || notice.responseResolved
                || notice.options.stream().noneMatch(option -> option.id().equals(choice))) return ResponseResult.UNRESOLVED;
        ResponseResult result = Objects.requireNonNull(notice.resolver.resolve(owner, currentScope, choice));
        if (result == ResponseResult.PREVENTED_LOSS) notices.remove(token);
        else if (result == ResponseResult.CONSEQUENCE_STILL_VALID) notice.responseResolved = true;
        return result;
    }

    /** One-shot authorization directly before a game-thread commit. All touched items, including container contents, are required. */
    public boolean consumeAuthorization(ServerPlayer owner, UUID token, LossScope currentScope,
            List<ItemStack> allAffectedItems, BooleanSupplier targetAndResponseStillValid) {
        requireLive(owner);
        Notice notice = notices.get(token);
        if (!current(notice, owner, currentScope) || !notice.responseResolved || allAffectedItems == null
                || allAffectedItems.stream().anyMatch(item -> AiProtectedItems.rejection(owner, item).isPresent())
                || !targetAndResponseStillValid.getAsBoolean()) return false;
        notices.remove(token);
        return true;
    }

    public void discardFor(UUID playerId) { notices.values().removeIf(value -> value.scope.ownerId().equals(playerId)); }
    public void clear() { notices.clear(); }

    /** Finite vanilla effects have engine-enforced recovery. Permanent curses need a separate real recovery executor. */
    public static Optional<String> curseRejection(MobEffectInstance effect) {
        if (effect == null || effect.isInfiniteDuration() || effect.getDuration() <= 0)
            return Optional.of("CURSE_REQUIRES_GAME_ENFORCED_RECOVERY");
        return Optional.empty();
    }

    private static boolean current(Notice notice, ServerPlayer owner, LossScope scope) {
        return notice != null && notice.recipient == owner && notice.scope.equals(scope) && owner.getUUID().equals(scope.ownerId());
    }
    private static void requireLive(ServerPlayer player) {
        if (player.server == null || !player.server.isSameThread() || !player.isAlive() || player.connection == null
                || player.server.getPlayerList().getPlayer(player.getUUID()) != player) throw new IllegalStateException("Live game-owned recipient required");
    }
    private static String text(String text, int maximum) {
        if (text == null || text.isBlank() || text.length() > maximum) throw new IllegalArgumentException("Invalid consequence field");
        return text;
    }
}
