package com.sande.mythictrpg.recording.server;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Independent read selection. NEW is understood but cannot activate without the foreground/proof contract. */
public record RecordingRetrievalSettings(Mode retrievalMode) {
    public enum Mode { LEGACY, SHADOW, NEW }
    public enum Origin { DEFAULT, ARCHIVE_SCHEMA2_COMPAT, EXPLICIT, INVALID }
    public enum State { LEGACY, SHADOW, ARCHIVE_OFF, BLOCKED_CONTRACT_NOT_READY, INVALID_RETRIEVAL_CONFIG }
    public static final RecordingRetrievalSettings LEGACY=new RecordingRetrievalSettings(Mode.LEGACY);
    public RecordingRetrievalSettings { Objects.requireNonNull(retrievalMode); }

    /** Immutable startup policy; diagnostic status is separate from archive health and does not mutate a manifest. */
    public record Policy(Optional<Mode> requestedMode,Origin origin,State state) {
        public Policy {
            Objects.requireNonNull(requestedMode);Objects.requireNonNull(origin);Objects.requireNonNull(state);
            boolean valid=switch(state){
                case LEGACY -> requestedMode.equals(Optional.of(Mode.LEGACY))&&(origin==Origin.DEFAULT||origin==Origin.EXPLICIT);
                case SHADOW,ARCHIVE_OFF -> requestedMode.equals(Optional.of(Mode.SHADOW))&&(origin==Origin.ARCHIVE_SCHEMA2_COMPAT||origin==Origin.EXPLICIT);
                case BLOCKED_CONTRACT_NOT_READY -> requestedMode.equals(Optional.of(Mode.NEW))&&origin==Origin.EXPLICIT;
                case INVALID_RETRIEVAL_CONFIG -> requestedMode.isEmpty()&&origin==Origin.INVALID;
            };
            if(!valid)throw new IllegalArgumentException("INVALID_RETRIEVAL_POLICY");
        }
        public boolean shadowReadsAllowed(){return state==State.SHADOW;}
        public boolean foregroundBlocked(){return state==State.BLOCKED_CONTRACT_NOT_READY||state==State.INVALID_RETRIEVAL_CONFIG;}
    }
    /** Missing new config preserves the prior explicit schema2 SHADOW opt-in, never creates one for RECORD_ONLY. */
    public static Policy resolve(RecordingSettings.Mode archive,Optional<RecordingRetrievalSettings> explicit) {
        Objects.requireNonNull(archive);Objects.requireNonNull(explicit);
        Mode requested=explicit.map(RecordingRetrievalSettings::retrievalMode)
                .orElse(archive==RecordingSettings.Mode.SHADOW?Mode.SHADOW:Mode.LEGACY);
        Origin origin=explicit.isPresent()?Origin.EXPLICIT:archive==RecordingSettings.Mode.SHADOW?Origin.ARCHIVE_SCHEMA2_COMPAT:Origin.DEFAULT;
        State state=switch(requested){case LEGACY->State.LEGACY;case NEW->State.BLOCKED_CONTRACT_NOT_READY;
            case SHADOW->archive==RecordingSettings.Mode.OFF?State.ARCHIVE_OFF:State.SHADOW;};
        return new Policy(Optional.of(requested),origin,state);
    }
    public static Policy invalid(){return new Policy(Optional.empty(),Origin.INVALID,State.INVALID_RETRIEVAL_CONFIG);}
    /** Missing is distinguishable from an explicitly authored LEGACY override. No file/directory is created. */
    public static Optional<RecordingRetrievalSettings> load(Path path)throws IOException {
        if(!Files.exists(path))return Optional.empty();
        if(!Files.isRegularFile(path)||Files.size(path)>4096)throw new IOException("INVALID_RETRIEVAL_CONFIG");
        try {
            var json=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if(!json.keySet().equals(Set.of("schemaVersion","retrievalMode"))
                    ||!json.get("schemaVersion").isJsonPrimitive()||!json.get("schemaVersion").getAsJsonPrimitive().isNumber()
                    ||json.get("schemaVersion").getAsBigDecimal().intValueExact()!=1
                    ||!json.get("retrievalMode").isJsonPrimitive()||!json.get("retrievalMode").getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("INVALID_RETRIEVAL_CONFIG");
            return Optional.of(new RecordingRetrievalSettings(Mode.valueOf(json.get("retrievalMode").getAsString())));
        }catch(RuntimeException invalid){throw new IOException("INVALID_RETRIEVAL_CONFIG",invalid);}
    }
}
