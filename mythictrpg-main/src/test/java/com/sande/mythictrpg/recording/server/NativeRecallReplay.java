package com.sande.mythictrpg.recording.server;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import com.sande.mythictrpg.recording.api.NativeMemoryEvidence;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only diagnostic for the synthetic NativeContentEvidenceGameTests archive, never live authority. */
public final class NativeRecallReplay {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]).toRealPath();
        if (!file.startsWith(Path.of("build").toRealPath())) throw new IllegalArgumentException("Build fixture only");
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file.toUri() + "?mode=ro")) {
            UUID id, dataset; String speaker, context;
            try (var q = db.createStatement(); var r = q.executeQuery("SELECT m.id,m.dataset_id,m.actor_id,x.context_json FROM messages m"
                    + " JOIN message_parts p ON p.message_id=m.id JOIN message_contexts x ON x.message_id=m.id"
                    + " WHERE p.body='native_content_reply_marker' AND m.actor_kind='GOD'")) {
                if (!r.next()) throw new AssertionError("Missing synthetic fixture reply");
                id = UUID.fromString(r.getString(1)); dataset = UUID.fromString(r.getString(2));
                speaker = r.getString(3); context = r.getString(4);
            }
            var json = JsonParser.parseString(context).getAsJsonObject();
            var audience = new HashSet<ActorRef>();
            for (var value : json.getAsJsonArray("fullAudience")) {
                var key = value.getAsString(); int separator = key.indexOf(':');
                audience.add(new ActorRef(ActorKind.valueOf(key.substring(0, separator)), key.substring(separator + 1)));
            }
            var scope = new RecordedRoomSearch.Scope(dataset, speaker, audience, false,
                    json.get("recordingPolicy").getAsString(), json.get("memoryMode").getAsString());
            var query = new MemoryReadSession.Query("native_content_reply_marker", Optional.empty(), Optional.empty());
            var found = RecordedRoomSearch.query(db, scope, query, new MemoryReadSession.Budget(4, 8192), Long.MAX_VALUE, Long.MAX_VALUE);
            System.out.println("raw SQL candidates=" + found.candidates().size());
            var reader = new RecordedRoomSearch(db, scope, Long.MAX_VALUE, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
            var nodeMethod = RecordedRoomSearch.class.getDeclaredMethod("node", UUID.class); nodeMethod.setAccessible(true);
            @SuppressWarnings("unchecked")
            var node = (Optional<RecordedRoomSearch.Node>) nodeMethod.invoke(reader, id);
            System.out.println("reply node=" + node.isPresent());
            for (var value : json.getAsJsonArray("evidence")) {
                var e = value.getAsJsonObject(); var ref = NativeMemoryEvidence.decode(new RoomEvidenceReference(e.get("kind").getAsString(), e.get("payload").getAsString()));
                var stored = RecordedNativeEvidenceStore.load(db, ref.worldId(), scope, ref, Long.MAX_VALUE, Long.MAX_VALUE, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
                System.out.println("stored native proof=" + stored.isPresent());
                if (stored.isPresent()) {
                    var manifest = stored.orElseThrow().manifest();
                    var closure = reader.contentAwareClosure(manifest.roots());
                    System.out.println("original closure=" + closure.isPresent());
                    if (closure.isPresent()) System.out.println("original bindings match=" + RecordedNativeEvidenceStore.matches(db, scope, manifest, closure.orElseThrow().nodes(), System.nanoTime() + TimeUnit.SECONDS.toNanos(30)));
                }
            }
            System.out.println("reply closure=" + reader.contentAwareClosure(Set.of(id)).isPresent());
            if (found.candidates().size() != 1 || !found.candidates().getFirst().entry().messageId().equals(id))
                throw new AssertionError("Persisted fixture reply excluded by SQL provenance path");
            System.out.println("PASS persisted fixture SQL recall (not a live owner authorization check)");
        }
    }
}
