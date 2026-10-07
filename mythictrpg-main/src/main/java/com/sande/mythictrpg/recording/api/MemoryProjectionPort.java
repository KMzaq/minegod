package com.sande.mythictrpg.recording.api;

import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Background-only bounded work. Source aliases are data; game-issued tokens are never sent to a model. */
public interface MemoryProjectionPort {
    /** Immediately invalidates in-memory authority; already committed work is unaffected. No disk wait. */
    void revokeWorker(ProjectionWorkerCapability worker);
    CompletionStage<ClaimResult> claimWork(ProjectionWorkerCapability worker, WorkBudget budget);
    CompletionStage<Result> commitProjection(ProjectionWorkToken token, List<Candidate> candidates);
    CompletionStage<Result> finishWork(ProjectionWorkToken token, WorkOutcome outcome, String reasonCode);
}
