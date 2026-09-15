package com.sande.mythictrpg.dialogue.presentation;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Objects;

/** Rebuilds presentation components from an allowlist and strips interactive style data. */
public final class DialogueComponentSanitizer {
    public static final int MAX_DEPTH = 16;
    public static final int MAX_NODES = 128;

    private DialogueComponentSanitizer() {
    }

    public static Component sanitize(Component input, int maxCodePoints, String fieldName) {
        Objects.requireNonNull(input, fieldName);
        Budget budget = new Budget();
        Component sanitized = sanitizeNode(input, 1, budget, fieldName);
        int codePoints = codePointCount(sanitized.getString());
        if (codePoints > maxCodePoints) {
            throw invalid(fieldName, "length " + codePoints + " exceeds " + maxCodePoints + " code points");
        }
        return sanitized;
    }

    public static void requireAlreadySafe(Component input, int maxCodePoints, String fieldName) {
        Component sanitized = sanitize(input, maxCodePoints, fieldName);
        if (!sanitized.equals(input)) {
            throw invalid(fieldName, "contains interactive or unsupported style data");
        }
    }

    private static MutableComponent sanitizeNode(Component input, int depth, Budget budget, String fieldName) {
        if (depth > MAX_DEPTH) {
            throw invalid(fieldName, "component depth exceeds " + MAX_DEPTH);
        }
        if (++budget.nodes > MAX_NODES) {
            throw invalid(fieldName, "component node count exceeds " + MAX_NODES);
        }

        MutableComponent result;
        if (input.getContents() instanceof PlainTextContents plain) {
            result = Component.literal(plain.text());
        } else if (input.getContents() instanceof TranslatableContents translatable) {
            Object[] originalArgs = translatable.getArgs();
            Object[] safeArgs = new Object[originalArgs.length];
            for (int index = 0; index < originalArgs.length; index++) {
                Object argument = originalArgs[index];
                if (argument instanceof Component component) {
                    safeArgs[index] = sanitizeNode(component, depth + 1, budget, fieldName);
                } else if (TranslatableContents.isAllowedPrimitiveArgument(argument)) {
                    safeArgs[index] = argument;
                } else {
                    throw invalid(fieldName, "contains an unsupported translation argument");
                }
            }
            result = Component.translatableWithFallback(
                    translatable.getKey(), translatable.getFallback(), safeArgs);
        } else {
            throw invalid(fieldName, "contains forbidden dynamic component type "
                    + input.getContents().getClass().getSimpleName());
        }

        Style safeStyle = input.getStyle()
                .withClickEvent(null)
                .withHoverEvent(null)
                .withInsertion(null)
                .withFont(null);
        result.setStyle(safeStyle);
        for (Component sibling : input.getSiblings()) {
            result.append(sanitizeNode(sibling, depth + 1, budget, fieldName));
        }
        return result;
    }

    public static int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static DialogueValidationException invalid(String fieldName, String reason) {
        return new DialogueValidationException(fieldName + " " + reason);
    }

    private static final class Budget {
        private int nodes;
    }
}
