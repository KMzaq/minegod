package com.sande.mythictrpg.quest;

/** Read-only result used before an AI quest offer is allowed to execute. */
public record QuestAssignmentValidation(boolean allowed, QuestOperationResult.Status rejectionStatus,
        String reason) {
    public QuestAssignmentValidation {
        reason = reason == null ? "" : reason.trim();
        if (!allowed && rejectionStatus == null) {
            throw new IllegalArgumentException("Rejected assignment validation requires a status");
        }
        if (!allowed && reason.isBlank()) {
            throw new IllegalArgumentException("Rejected assignment validation requires a reason");
        }
    }

    public static QuestAssignmentValidation allow() {
        return new QuestAssignmentValidation(true, null, "");
    }

    public static QuestAssignmentValidation reject(QuestOperationResult.Status status, String reason) {
        return new QuestAssignmentValidation(false, status, reason);
    }
}
