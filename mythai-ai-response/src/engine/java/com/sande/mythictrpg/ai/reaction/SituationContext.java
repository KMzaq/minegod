package com.sande.mythictrpg.ai.reaction;

import com.sande.mythictrpg.ai.relationship.RelationshipTag;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Structured facts available for reaction selection. Missing values remain absent instead of being guessed.
 */
public record SituationContext(PlayerState player, WorldState world, ConversationState conversation,
        RelationshipState relationship, MemoryState memory, QuestState quest, SocialContext social) {
    public SituationContext {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(relationship, "relationship");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(quest, "quest");
        Objects.requireNonNull(social, "social");
    }

    public static SituationContext empty() {
        return new SituationContext(PlayerState.unknown(), WorldState.unknown(), ConversationState.empty(),
                RelationshipState.unknown(), MemoryState.unknown(), QuestState.unknown(), SocialContext.solo());
    }

    public Set<SituationSignal> signals() {
        EnumSet<SituationSignal> signals = EnumSet.noneOf(SituationSignal.class);
        if (conversation.firstMeeting()) signals.add(SituationSignal.FIRST_MEETING);
        if (conversation.greeting() && world.minecraftDayTime().isPresent()) signals.add(SituationSignal.TIME_GREETING);
        if (memory.timeSinceLastConversation().map(duration -> duration.compareTo(Duration.ofHours(24)) >= 0)
                .orElse(false)) signals.add(SituationSignal.LONG_TIME_REUNION);
        if (conversation.repeatedGreeting()) signals.add(SituationSignal.REPEATED_CONVERSATION);
        if (world.weatherChanged()) signals.add(SituationSignal.WEATHER_CHANGE);
        if (player.healthRatio().map(value -> value <= 0.25D).orElse(false)) signals.add(SituationSignal.LOW_HEALTH);
        if (player.recentCombat()) signals.add(SituationSignal.POST_COMBAT);
        if (conversation.questRequest()) signals.add(SituationSignal.QUEST_REQUEST);
        if (quest.incomplete()) signals.add(SituationSignal.QUEST_INCOMPLETE);
        if (quest.completed()) signals.add(SituationSignal.QUEST_COMPLETED);
        if (conversation.preferredGift()) signals.add(SituationSignal.PREFERRED_GIFT);
        if (conversation.dislikedGift()) signals.add(SituationSignal.DISLIKED_GIFT);
        if (memory.repeatedGift()) signals.add(SituationSignal.REPEATED_GIFT);
        if (conversation.compliment()) signals.add(SituationSignal.COMPLIMENT);
        if (conversation.insult()) signals.add(SituationSignal.INSULT);
        if (conversation.apology()) signals.add(SituationSignal.APOLOGY);
        if (conversation.askAboutOtherGod()) signals.add(SituationSignal.ASK_ABOUT_OTHER_GOD);
        if (conversation.unknownInformationRequested()) signals.add(SituationSignal.UNKNOWN_INFORMATION);
        return Set.copyOf(signals);
    }

    public record PlayerState(Optional<Double> healthRatio, boolean recentCombat, Optional<String> usedWeapon,
            Optional<String> combatOutcome) {
        public PlayerState {
            healthRatio = healthRatio == null ? Optional.empty() : healthRatio;
            if (healthRatio.isPresent() && (healthRatio.get() < 0D || healthRatio.get() > 1D)) {
                throw new IllegalArgumentException("healthRatio must be between 0 and 1");
            }
            usedWeapon = normalized(usedWeapon);
            combatOutcome = normalized(combatOutcome);
        }

        public static PlayerState unknown() {
            return new PlayerState(Optional.empty(), false, Optional.empty(), Optional.empty());
        }
    }

    public record WorldState(Optional<Long> minecraftDayTime, MinecraftWeather weather, boolean weatherChanged) {
        public WorldState {
            minecraftDayTime = minecraftDayTime == null ? Optional.empty() : minecraftDayTime;
            Objects.requireNonNull(weather, "weather");
        }

        public static WorldState unknown() {
            return new WorldState(Optional.empty(), MinecraftWeather.UNKNOWN, false);
        }
    }

    public record ConversationState(boolean firstMeeting, boolean greeting, boolean repeatedGreeting,
            boolean questRequest, boolean preferredGift, boolean dislikedGift, boolean compliment, boolean insult,
            boolean apology, boolean askAboutOtherGod, boolean unknownInformationRequested) {
        public static ConversationState empty() {
            return new ConversationState(false, false, false, false, false, false, false, false, false, false, false);
        }
    }

    public record RelationshipState(Set<RelationshipTag> derivedTags) {
        public RelationshipState {
            derivedTags = derivedTags == null ? Set.of() : Set.copyOf(derivedTags);
        }

        public static RelationshipState unknown() {
            return new RelationshipState(Set.of());
        }
    }

    public record MemoryState(Optional<Duration> timeSinceLastConversation, boolean repeatedGift) {
        public MemoryState {
            timeSinceLastConversation = timeSinceLastConversation == null ? Optional.empty() : timeSinceLastConversation;
            if (timeSinceLastConversation.isPresent() && timeSinceLastConversation.get().isNegative()) {
                throw new IllegalArgumentException("timeSinceLastConversation must not be negative");
            }
        }

        public static MemoryState unknown() {
            return new MemoryState(Optional.empty(), false);
        }
    }

    public record QuestState(boolean incomplete, boolean completed) {
        public QuestState {
            if (incomplete && completed) {
                throw new IllegalArgumentException("A quest cannot be both incomplete and completed");
            }
        }

        public static QuestState unknown() {
            return new QuestState(false, false);
        }
    }

    public record SocialContext(int visiblePlayerCount, boolean groupConversation) {
        public SocialContext {
            if (visiblePlayerCount < 0) {
                throw new IllegalArgumentException("visiblePlayerCount must not be negative");
            }
        }

        public static SocialContext solo() {
            return new SocialContext(1, false);
        }
    }

    private static Optional<String> normalized(Optional<String> value) {
        if (value == null || value.isEmpty() || value.get().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.get().trim());
    }
}
