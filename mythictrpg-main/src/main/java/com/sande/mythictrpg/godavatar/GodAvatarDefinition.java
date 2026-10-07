package com.sande.mythictrpg.godavatar;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Author-owned physical presentation and capability for an existing God Definition ID. */
public record GodAvatarDefinition(ResourceLocation godId, Appearance appearance,
        Stats stats, Movement movement, Combat combat, Placement placement, double interactionRange) {
    public GodAvatarDefinition {
        Objects.requireNonNull(godId, "godId");
        Objects.requireNonNull(appearance, "appearance");
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(combat, "combat");
        Objects.requireNonNull(placement, "placement");
        bounded(interactionRange, 1, 16, "interactionRange");
    }

    public enum SkinModel {
        CLASSIC, SLIM;

        public static SkinModel fromJson(String value) {
            return switch (value) {
                case "classic" -> CLASSIC;
                case "slim" -> SLIM;
                default -> throw new IllegalArgumentException("appearance model must be classic or slim");
            };
        }
    }

    public record Appearance(int textureVariant, float scale, SkinModel model) {
        /** Existing authored definitions and API callers default to the standard four-pixel arm. */
        public Appearance(int textureVariant, float scale) {
            this(textureVariant, scale, SkinModel.CLASSIC);
        }

        public Appearance {
            Objects.requireNonNull(model, "appearance model");
            if (textureVariant < 0 || textureVariant > 255) throw new IllegalArgumentException("textureVariant must be 0..255");
            bounded(scale, 0.5, 2, "appearance scale");
        }
    }

    public record Stats(double maxHealth, double movementSpeed, double attackDamage,
            double armor, double followRange) {
        public Stats {
            bounded(maxHealth, 1, 2048, "maxHealth");
            bounded(movementSpeed, 0, 1, "movementSpeed");
            bounded(attackDamage, 0, 512, "attackDamage");
            bounded(armor, 0, 30, "armor");
            bounded(followRange, 1, 128, "followRange");
        }
    }

    public record Movement(boolean enabled, boolean wander, boolean visit,
            double navigationSpeed, double maxCommandDistance, double maxVisitDistance) {
        public Movement {
            bounded(navigationSpeed, 0, 2, "navigationSpeed");
            bounded(maxCommandDistance, 0, 128, "maxCommandDistance");
            bounded(maxVisitDistance, 0, 128, "maxVisitDistance");
            if (!enabled && (wander || visit)) throw new IllegalArgumentException("Disabled movement cannot wander or visit");
            if (enabled && navigationSpeed == 0) throw new IllegalArgumentException("Enabled movement needs navigationSpeed");
            if (visit && maxVisitDistance == 0) throw new IllegalArgumentException("Visit needs maxVisitDistance");
        }
    }

    public record Combat(boolean enabled, boolean damageable, boolean retaliate, boolean raidControl) {
        public Combat {
            if (!enabled && (retaliate || raidControl))
                throw new IllegalArgumentException("Disabled combat cannot retaliate or accept raid control");
        }
    }

    public record Placement(boolean onEncounter, int spawnRadius) {
        public Placement {
            if (spawnRadius < 0 || spawnRadius > 16) throw new IllegalArgumentException("spawnRadius must be 0..16");
        }
    }

    private static void bounded(double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum);
    }
}
