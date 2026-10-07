package com.sande.mythai.response.memory;

import com.sande.mythictrpg.recording.api.EmbeddingRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords;

/** Neutral backend reuse; native identity never uses the legacy raw-text-v1 fingerprint. */
final class RecordedEmbeddingModel {
    private final MemoryIndexSettings settings;
    private final OllamaMemoryBackend backend;
    final ModelSpace space;
    RecordedEmbeddingModel(MemoryIndexSettings settings, OllamaMemoryBackend backend) {
        if (!settings.enabled() || settings.semanticMode() == MemoryIndexSettings.Mode.OFF)
            throw new IllegalArgumentException("EMBEDDING_MODEL_DISABLED");
        this.settings = settings; this.backend = backend;
        space = new ModelSpace(settings.embeddingModel(), settings.modelRevision(), settings.dimensions(), "native-prefix-1600-v1");
    }
    float[] embed(Work work) throws Exception {
        if (!space.equals(work.modelSpace()) || !RecordingRecords.sha256(work.text()).equals(work.inputHash()))
            throw new IllegalArgumentException("EMBEDDING_WORK_SPACE_MISMATCH");
        return values(work.text(), true);
    }
    QueryVector query(String text) throws Exception {
        return new QueryVector(space, RecordingRecords.sha256(text), values(text, false));
    }
    private float[] values(String text, boolean background) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("EMBEDDING_PREEMPTED");
        var vector = backend.embed(text, background);
        if (!settings.fingerprint().equals(vector.modelFingerprint())) throw new IllegalArgumentException("EMBEDDING_BACKEND_SPACE_MISMATCH");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("EMBEDDING_PREEMPTED");
        return vector.values();
    }
}
