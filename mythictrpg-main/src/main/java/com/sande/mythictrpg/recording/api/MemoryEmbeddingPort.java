package com.sande.mythictrpg.recording.api;

import java.util.concurrent.CompletionStage;
import com.sande.mythictrpg.recording.api.EmbeddingRecords.*;

/** Game-issued, bounded native embedding work. No SQL, file paths, model-created source identities or game actions. */
public interface MemoryEmbeddingPort {
    CompletionStage<ClaimResult> claimWork(EmbeddingWorkerCapability capability,int leaseSeconds);
    CompletionStage<Result> commitEmbedding(EmbeddingWorkToken token,float[] values);
    CompletionStage<Result> finishWork(EmbeddingWorkToken token,WorkOutcome outcome,String reasonCode);
    void revokeWorker(EmbeddingWorkerCapability capability);
}
