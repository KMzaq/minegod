package com.sande.mythictrpg.ai.integration;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.interaction.director.InteractionPlan;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Optional, reflection-only boundary to the separately deployed AI response mod.
 *
 * <p>The AI response implementation is intentionally not a build dependency of MythicTRPG. The only
 * currently available reference JAR also contains another copy of MythicTRPG and therefore cannot be
 * placed on the runtime or compile classpath safely.</p>
 */
public final class AiDialogueEngineBridge {
    public static final AiDialogueEngineBridge INSTANCE = new AiDialogueEngineBridge();

    private static final String SERVICE_CLASS = "com.sande.mythictrpg.ai.GodAiDialogueService";
    private static final String QUEST_PROVIDER_CLASS =
            "com.sande.mythictrpg.ai.proposal.QuestRewardContextProvider";
    private static final String AUTHORITY_PROVIDER_CLASS =
            "com.sande.mythictrpg.ai.tone.SocialAuthorityContextProvider";
    private static final String PROPOSAL_GATEWAY_CLASS = "com.sande.mythictrpg.ai.AiProposalGateway";
    private static final String PROPOSAL_VALIDATOR_CLASS =
            "com.sande.mythictrpg.ai.AiProposalGateway$ProposalValidator";

    private volatile Api api;
    private volatile boolean discoveryAttempted;

    private AiDialogueEngineBridge() {
    }

    public boolean isAvailable() {
        return api() != null;
    }

    /** Called only after MythicTRPG has committed an approved interaction. */
    public void startApprovedInteraction(MinecraftServer server, UUID interactionId,
            InteractionPlan plan) {
        requireServerThread(server);
        Api current = api();
        if (current == null) {
            return;
        }

        List<ServerPlayer> players = new ArrayList<>();
        for (UUID playerId : plan.audience().recipientPlayerIds()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                players.add(player);
            }
        }
        List<ResourceLocation> godIds = new ArrayList<>();
        godIds.add(plan.participants().primaryGodId());
        godIds.addAll(plan.participants().secondaryGodIds());

        try {
            Object result = current.startConversation.invoke(
                    current.service, players, godIds, interactionId);
            Object status = current.startStatus.invoke(result);
            if (!"STARTED".equals(String.valueOf(status))) {
                MythicTrpg.LOGGER.warn("AI dialogue session rejected for interaction {}: {}",
                        interactionId, status);
                return;
            }
            MythicTrpg.LOGGER.info("AI dialogue session {} started for interaction {}",
                    current.sessionId.invoke(result), interactionId);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            MythicTrpg.LOGGER.error("AI dialogue session failed for committed interaction {}",
                    interactionId, unwrap(exception));
        }
    }

    /** Returns true only when the message was consumed by an ACTIVE AI session. */
    public boolean handlePlayerText(ServerPlayer player, String rawText) {
        Api current = api();
        if (current == null || rawText == null || rawText.isBlank() || rawText.startsWith("!")) {
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(current.isActive.invoke(current.service, player))) {
                return false;
            }
            current.handlePlayerText.invoke(current.service, player, rawText);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            MythicTrpg.LOGGER.error("Failed to forward player chat to AI dialogue engine", unwrap(exception));
            return false;
        }
    }

    public void onPlayerLoggedOut(ServerPlayer player) {
        Api current = api();
        if (current == null) {
            return;
        }
        try {
            current.onPlayerLoggedOut.invoke(current.service, player);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            MythicTrpg.LOGGER.error("Failed to detach logged-out player from AI dialogue", unwrap(exception));
        }
    }

    public void stop() {
        Api current = api;
        if (current == null) {
            return;
        }
        try {
            current.stop.invoke(current.service);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            MythicTrpg.LOGGER.error("Failed to stop AI dialogue engine", unwrap(exception));
        }
    }

    private Api api() {
        Api current = api;
        if (current != null || discoveryAttempted) {
            return current;
        }
        synchronized (this) {
            if (api != null || discoveryAttempted) {
                return api;
            }
            discoveryAttempted = true;
            try {
                api = discoverAndSecure();
                MythicTrpg.LOGGER.info("Optional AI dialogue engine detected; safe providers installed");
            } catch (ClassNotFoundException exception) {
                MythicTrpg.LOGGER.info("Optional AI dialogue engine is not installed");
            } catch (ReflectiveOperationException | RuntimeException exception) {
                MythicTrpg.LOGGER.error("AI dialogue engine API is incompatible; integration disabled",
                        unwrap(exception));
            }
            return api;
        }
    }

    private static Api discoverAndSecure() throws ReflectiveOperationException {
        ClassLoader loader = AiDialogueEngineBridge.class.getClassLoader();
        Class<?> serviceType = Class.forName(SERVICE_CLASS, false, loader);
        Object service = singleton(serviceType);

        Class<?> questProviderType = Class.forName(QUEST_PROVIDER_CLASS, false, loader);
        Object noQuestContext = invokeStaticFactory(questProviderType, "none");
        serviceType.getMethod("installQuestRewardContextProvider", questProviderType)
                .invoke(service, noQuestContext);

        Class<?> authorityProviderType = Class.forName(AUTHORITY_PROVIDER_CLASS, false, loader);
        Object noAuthorityContext = invokeStaticFactory(authorityProviderType, "none");
        serviceType.getMethod("installSocialAuthorityContextProvider", authorityProviderType)
                .invoke(service, noAuthorityContext);

        Class<?> gatewayType = Class.forName(PROPOSAL_GATEWAY_CLASS, false, loader);
        Object gateway = singleton(gatewayType);
        Class<?> validatorType = Class.forName(PROPOSAL_VALIDATOR_CLASS, false, loader);
        Object rejectingValidator = invokeStaticFactory(validatorType, "rejecting");
        gatewayType.getMethod("installValidator", validatorType).invoke(gateway, rejectingValidator);

        Method startConversation = serviceType.getMethod("startConversation",
                Collection.class, Collection.class, UUID.class);
        Class<?> resultType = startConversation.getReturnType();
        return new Api(service, startConversation, resultType.getMethod("status"),
                resultType.getMethod("sessionId"),
                serviceType.getMethod("isActive", ServerPlayer.class),
                serviceType.getMethod("handlePlayerText", ServerPlayer.class, String.class),
                serviceType.getMethod("onPlayerLoggedOut", ServerPlayer.class),
                serviceType.getMethod("stop"));
    }

    private static Object singleton(Class<?> type) throws ReflectiveOperationException {
        Field field = type.getField("INSTANCE");
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new NoSuchFieldException(type.getName() + ".INSTANCE is not static");
        }
        return field.get(null);
    }

    private static Object invokeStaticFactory(Class<?> type, String name)
            throws ReflectiveOperationException {
        Method method = type.getMethod(name);
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(type.getName() + "." + name + " is not static");
        }
        return method.invoke(null);
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("AI conversation start must run on the server thread");
        }
    }

    private record Api(Object service, Method startConversation, Method startStatus,
            Method sessionId, Method isActive, Method handlePlayerText,
            Method onPlayerLoggedOut, Method stop) {
    }
}
