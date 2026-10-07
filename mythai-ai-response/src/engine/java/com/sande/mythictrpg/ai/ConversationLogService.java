package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Writes one UTF-8 transcript copy for every player who can participate in a conversation. */
final class ConversationLogService implements AutoCloseable {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS")
            .withLocale(Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withLocale(Locale.ROOT).withZone(ZoneId.systemDefault());

    private final Path root = FMLPaths.GAMEDIR.get().resolve("mythictrpg-dialogue-logs");
    private final Map<UUID, SessionLog> logs = new LinkedHashMap<>();

    void open(AiDialogueModels.SessionSnapshot session) {
        close(session.sessionId());
        String gods = session.participants().stream()
                .filter(participant -> participant.kind() == AiDialogueModels.ParticipantKind.DIVINE)
                .map(AiDialogueModels.Participant::displayName).map(ConversationLogService::safeSegment)
                .reduce((left, right) -> left + "_" + right).orElse("unknown-god");
        String fileName = FILE_TIME.format(session.createdAt()) + "_" + gods + ".txt";
        SessionLog log = new SessionLog(fileName);
        logs.put(session.sessionId(), log);
        for (AiDialogueModels.Participant participant : session.participants()) {
            if (participant.kind() != AiDialogueModels.ParticipantKind.PLAYER
                    || participant.state() == AiDialogueModels.ParticipantState.OUTSIDE) {
                continue;
            }
            openPlayerWriter(log, session, participant, false);
        }
    }

    void addPlayer(AiDialogueModels.SessionSnapshot session, AiDialogueModels.Participant participant) {
        if (participant.kind() != AiDialogueModels.ParticipantKind.PLAYER || participant.playerId() == null) {
            return;
        }
        SessionLog log = logs.get(session.sessionId());
        if (log != null) {
            openPlayerWriter(log, session, participant, true);
        }
    }

    void append(UUID sessionId, String kind, String speaker, String text) {
        SessionLog log = logs.get(sessionId);
        if (log == null) {
            return;
        }
        String line = "[" + LINE_TIME.format(Instant.now()) + "] [" + kind + "] " + speaker + ": "
                + text.replace('\r', ' ').replace('\n', ' ').trim();
        log.write(line);
    }

    void appendProposal(UUID sessionId, AiDialogueModels.Proposal proposal, AiProposalGateway.ProposalDecision decision) {
        append(sessionId, "PROPOSAL:" + decision.status(), proposal.type(), proposal.title() + " | "
                + proposal.summary() + " | " + decision.reason());
    }

    void appendRejectedProposal(UUID sessionId, AiDialogueModels.Proposal proposal, String reason) {
        if (proposal == null) {
            append(sessionId, "PROPOSAL:REJECTED", "unknown", reason == null ? "" : reason);
            return;
        }
        append(sessionId, "PROPOSAL:REJECTED", proposal.type(), proposal.title() + " | "
                + (reason == null ? "" : reason));
    }

    void close(UUID sessionId) {
        SessionLog removed = logs.remove(sessionId);
        if (removed != null) {
            removed.close();
        }
    }

    @Override
    public void close() {
        new ArrayList<>(logs.keySet()).forEach(this::close);
    }

    private static String participantSummary(AiDialogueModels.SessionSnapshot session) {
        return session.participants().stream().map(participant -> participant.displayName() + "(" + participant.kind()
                + "," + participant.state() + ")").reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static String safeSegment(String value) {
        String sanitized = value == null ? "unknown" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return sanitized.isBlank() ? "unknown" : sanitized.substring(0, Math.min(80, sanitized.length()));
    }

    private void openPlayerWriter(SessionLog log, AiDialogueModels.SessionSnapshot session,
            AiDialogueModels.Participant participant, boolean joinedAfterStart) {
        if (log.writers.containsKey(participant.playerId())) {
            return;
        }
        try {
            Path playerDirectory = root.resolve(safeSegment(participant.displayName()) + "_" + participant.playerId());
            Files.createDirectories(playerDirectory);
            BufferedWriter writer = Files.newBufferedWriter(playerDirectory.resolve(log.fileName), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            writer.write("Session: " + session.sessionId());
            writer.newLine();
            writer.write("Started: " + session.createdAt());
            writer.newLine();
            writer.write("Location: " + session.location().dimension() + " " + session.location().x() + ","
                    + session.location().y() + "," + session.location().z());
            writer.newLine();
            writer.write("Participants: " + participantSummary(session));
            writer.newLine();
            if (joinedAfterStart) {
                writer.write("Joined transcript: " + Instant.now());
                writer.newLine();
            }
            writer.newLine();
            writer.flush();
            log.writers.put(participant.playerId(), writer);
        } catch (IOException exception) {
            MythicTrpg.LOGGER.warn("Could not open AI dialogue log for {}: {}", participant.displayName(),
                    exception.getMessage());
        }
    }

    private static final class SessionLog {
        private final String fileName;
        private final Map<UUID, BufferedWriter> writers = new LinkedHashMap<>();

        private SessionLog(String fileName) {
            this.fileName = fileName;
        }

        private void write(String line) {
            for (BufferedWriter writer : writers.values()) {
                try {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                } catch (IOException exception) {
                    MythicTrpg.LOGGER.warn("Could not append AI dialogue log: {}", exception.getMessage());
                }
            }
        }

        private void close() {
            for (BufferedWriter writer : writers.values()) {
                try {
                    writer.close();
                } catch (IOException exception) {
                    MythicTrpg.LOGGER.warn("Could not close AI dialogue log: {}", exception.getMessage());
                }
            }
        }
    }
}
