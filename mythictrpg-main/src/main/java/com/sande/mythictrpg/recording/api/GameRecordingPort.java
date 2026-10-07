package com.sande.mythictrpg.recording.api;

import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

/** Game-approved producers only. No JDBC, file paths, Minecraft objects, raw SQL, or gameplay operations. */
public interface GameRecordingPort {
    CompletionStage<WriteReceipt> capture(ProducerCapability producer, ConversationEnvelope envelope, RawMessage raw, List<DeliveryReceipt> initialDeliveries);
    CompletionStage<WriteReceipt> recordDeliveries(ProducerCapability producer, DeliveryBatch batch);
    CompletionStage<WriteReceipt> captureSource(ProducerCapability producer, SourceCapture capture);
    CompletionStage<WriteReceipt> invalidate(ProducerCapability producer, SourceInvalidation invalidation);
    default CompletionStage<SourceRegistrationReceipt> registerSource(ProducerCapability producer, SourceRegistration registration) {
        return CompletableFuture.completedFuture(new SourceRegistrationReceipt(WriteReceipt.failed(Status.UNAVAILABLE, registration.owner(), "SOURCE_REGISTRATION_UNSUPPORTED"), java.util.OptionalLong.empty()));
    }
    default CompletionStage<WriteReceipt> appendKnowledge(ProducerCapability producer, SourceKnowledgeCapture capture) {
        return CompletableFuture.completedFuture(WriteReceipt.failed(Status.UNAVAILABLE, capture.source().sourceId(), "INCREMENTAL_KNOWLEDGE_UNSUPPORTED"));
    }
    default CompletionStage<WriteReceipt> invalidateKnowledge(ProducerCapability producer, KnowledgeInvalidation invalidation) {
        return CompletableFuture.completedFuture(WriteReceipt.failed(Status.UNAVAILABLE, invalidation.receiptId().toString(), "KNOWLEDGE_INVALIDATION_UNSUPPORTED"));
    }
}
