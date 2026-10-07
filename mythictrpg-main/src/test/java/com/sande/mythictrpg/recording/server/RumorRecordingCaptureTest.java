package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.recording.api.RecordingRecords;
import com.sande.mythictrpg.rumor.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Confirmed immutable source checkpoints into real SQLite; does not simulate a new gameplay delivery. */
public final class RumorRecordingCaptureTest {
    private static final Gson JSON = new Gson();
    private static final UUID WORLD=UUID.randomUUID(), LINEAGE=UUID.randomUUID(), PLAYER=UUID.randomUUID(),
            COURIER=UUID.randomUUID(), EPOCH=UUID.randomUUID(), ROOT=UUID.randomUUID(), LEGACY=UUID.randomUUID();
    private static final String G="test:demeter", H="test:fortuna";
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path base=Path.of(args[0]).toAbsolutePath().normalize();
        if(!base.toString().replace('\\','/').contains("/build/"))throw new IllegalArgumentException("BUILD_ONLY");
        Path world=Files.createTempDirectory(Files.createDirectories(base),"rumor-");
        var store=open(world);var capture=new RumorRecordingCapture(store);
        check(await(capture.capture(view(0,false,false,LINEAGE))).complete(),"initial confirmed baseline registers cutoff");
        var first=view(3,false,false,LINEAGE);
        check(await(capture.capture(first)).complete(),"actual first recipient checkpoint captured");
        try(var db=db(world,store)) {
            check(count(db,"source_refs")==1&&count(db,"knowledge_receipts")==1,"one original claim and actual recipient only; baseline root excluded");
            try(var query=db.createStatement();var row=query.executeQuery("SELECT projection,audience_json FROM knowledge_receipts")) {
                check(row.next(),"recorded projection exists");var projection=JsonParser.parseString(row.getString(1)).getAsJsonObject();
                check(projection.get("assessmentStatus").getAsString().equals("CURRENT_GAME_LOOKUP_REQUIRED")&&!projection.has("assessment"),
                        "receipt is not fabricated belief or another God's reputation");
                check(!row.getString(2).contains(H)&&row.getString(2).contains(G),"unreceived God not granted audience authority");
                check(!row.getString(1).contains("dimension")&&!row.getString(1).contains("sourceSecret"),"raw event details are not copied into projection");
            }
        }
        var late=view(4,true,false,LINEAGE);
        check(await(capture.capture(late)).complete(),"late independent recipient appended to same source");
        check(await(capture.capture(late)).status()==RecordingRecords.Status.DUPLICATE,"completed identical checkpoint is idempotent");
        try(var db=db(world,store)){check(count(db,"source_refs")==1&&count(db,"knowledge_receipts")==2,"late receipt never duplicates claim or imports baseline root");}
        await(store.closeAsync());store=open(world);capture=new RumorRecordingCapture(store);
        check(await(capture.capture(late)).complete(),"restart replays already durable source receipts idempotently");
        var revoked=view(5,true,true,LINEAGE);
        check(await(capture.capture(revoked)).complete(),"game administrative revocation mirrored after durable checkpoint");
        try(var db=db(world,store)){check(count(db,"invalidations")==1&&scalar(db,"SELECT revoked FROM source_refs")==1
                &&count(db,"knowledge_receipts")==2,"source revoked without deleting audit receipts or game records");}
        check(!await(capture.capture(late)).complete(),"older confirmed checkpoint cannot revive revoked claim");
        check(!await(capture.capture(view(5,true,true,UUID.randomUUID()))).complete(),"downgrade/missing-metadata lineage reset fails closed");
        await(store.closeAsync());store=open(world);capture=new RumorRecordingCapture(store);
        check(await(capture.capture(revoked)).complete(),"revocation reconciles across another real database reopen");
        try(var db=db(world,store)){check(count(db,"invalidations")==1&&count(db,"source_refs")==1,"replay creates neither rumors nor duplicate revocations");}
        await(store.closeAsync());
        independentFailures(Files.createTempDirectory(base,"rumor-partial-"));
        fullStillRevokes(Files.createTempDirectory(base,"rumor-full-"));
        System.out.println("RumorRecordingCaptureTest: "+checks+" checks passed; fixtures="+world);
    }
    private static final UUID BAD = new UUID(0, 1), GOOD = new UUID(-1, -1);
    private static void independentFailures(Path world) throws Exception {
        var store=open(world);var capture=new RumorRecordingCapture(store);
        check(await(capture.capture(view(0,false,false,LINEAGE))).complete(),"partial fixture baseline");
        var mixed=mixed(false,true,false);
        var outcome=await(capture.capture(mixed));
        check(!outcome.complete()&&outcome.reason().equals("RUMOR_SOURCE_PROOF_UNAVAILABLE"),"unproven positive root reports incomplete rather than granting knowledge");
        try(var db=db(world,store)) {
            check(count(db,"source_refs")==2&&count(db,"knowledge_receipts")==2,"invalid first root does not starve both unrelated valid roots");
            check(scalar(db,"SELECT count(*) FROM source_refs WHERE source_id='"+BAD+"'")==0,"unproven root gets no source or acquisition");
        }
        check(!await(capture.capture(mixed)).complete(),"partially successful snapshot never claims completed-cursor shortcut");
        try(var db=db(world,store)){check(count(db,"source_refs")==2&&count(db,"knowledge_receipts")==2,"partial retry keeps already committed roots idempotent");}
        check(await(capture.capture(mixed(false,true,true))).complete(),"negative tombstone remains possible even when original proof absent");
        try(var db=db(world,store)){check(count(db,"invalidations")==1&&count(db,"source_refs")==2,"withdrawal does not fabricate missing source or receipt");}
        await(store.closeAsync());
    }
    private static void fullStillRevokes(Path world) throws Exception {
        var store=await(WorldRecordingService.open(world,WORLD,
                new RecordingSettings(RecordingSettings.Mode.SHADOW,8_000_000,1_000_000,.9,.95),new WorldRecordingService.CutoverBoundary("rumor-full-fixture",Map.of())));
        var capture=new RumorRecordingCapture(store);
        check(await(capture.capture(view(0,false,false,LINEAGE))).complete()&&await(capture.capture(view(3,false,false,LINEAGE))).complete(),"FULL fixture has actual captured source");
        var producer=store.registerProducer("quota-fixture",Set.of("PRIVATE"),Set.of());
        var envelope=new RecordingRecords.ConversationEnvelope(WORLD,store.datasetId().orElseThrow(),UUID.randomUUID(),"PRIVATE","fixture",1,1,true,false,"v1");
        var raw=new RecordingRecords.RawMessage(UUID.randomUUID(),Optional.empty(),0,new RecordingRecords.ActorRef(RecordingRecords.ActorKind.PLAYER,PLAYER.toString()),
                "x".repeat(400_000),java.time.Instant.EPOCH,RecordingRecords.MessageKind.ACCEPTED_INPUT,"force-reservation-full");
        check(await(store.capture(producer,envelope,raw,List.of())).status()==RecordingRecords.Status.FULL
                &&store.health().state()==WorldRecordingService.State.FULL,"real physical-budget admission enters FULL without consuming maintenance headroom");
        var result=await(capture.capture(mixed(true,false,false)));
        check(!result.complete(),"FULL ordinary grants keep overall snapshot incomplete");
        try(var db=db(world,store)) {
            check(count(db,"invalidations")==1&&scalar(db,"SELECT revoked FROM source_refs WHERE source_id='"+ROOT+"'")==1,
                    "maintenance withdrawal commits despite earlier-sorting ordinary roots and FULL state");
            check(count(db,"source_refs")==1&&count(db,"knowledge_receipts")==1,"FULL does not create new positive grants");
        }
        await(store.closeAsync());
    }
    private static RumorRecordingState.DurableView mixed(boolean revokedRoot,boolean missingProof,boolean revokedBad) {
        var base=view(revokedRoot?5:3,false,revokedRoot,LINEAGE);
        var evidence=new ArrayList<>(base.snapshot().evidence());
        var bad=evidence(BAD);
        evidence.add(missingProof?new RumorLedger.Evidence(bad.id(),bad.subject(),bad.observer(),bad.epoch(),bad.excerpt(),bad.receivers(),bad.disclosureAudience()):bad);
        evidence.add(evidence(GOOD));
        var claims=new ArrayList<>(base.snapshot().claims());
        claims.add(new RumorLedger.Claim(BAD,revokedBad?2:1,"unproven allegation","",revokedBad));
        claims.add(new RumorLedger.Claim(GOOD,1,"independent valid allegation","",false));
        var receipts=new ArrayList<>(base.snapshot().receipts());
        receipts.add(new RumorLedger.Receipt(BAD,1,G));receipts.add(new RumorLedger.Receipt(GOOD,1,G));
        var snapshot=new RumorLedger.Snapshot(2,WORLD,base.snapshot().couriers(),evidence,claims,List.of(),receipts);
        var roots=new HashMap<>(base.metadata().roots());
        roots.put(BAD,new RumorRecordingState.RootStamp(BAD,6,revokedBad?2:1,revokedBad?11:7));
        roots.put(GOOD,new RumorRecordingState.RootStamp(GOOD,6,1,7));
        var stamps=new ArrayList<>(base.metadata().receipts());
        stamps.add(new RumorRecordingState.ReceiptStamp(BAD,G,1,8));stamps.add(new RumorRecordingState.ReceiptStamp(GOOD,G,1,8));
        var json=JSON.toJsonTree(snapshot).getAsJsonObject();json.remove("version");
        return new RumorRecordingState.DurableView(new RumorRecordingState.State(1,WORLD,LINEAGE,revokedBad?11:10,
                RumorRecordingState.Status.ACTIVE,roots,stamps,RecordingRecords.sha256(canonical(json).toString())),snapshot);
    }
    private static RumorRecordingState.DurableView view(long cursor,boolean second,boolean revoked,UUID lineage) {
        var old=evidence(LEGACY);var current=evidence(ROOT);
        var evidence=cursor==0?List.of(old):List.of(old,current);
        var claims=new ArrayList<RumorLedger.Claim>();claims.add(new RumorLedger.Claim(LEGACY,1,"old allegation","",false));
        var receipts=new ArrayList<RumorLedger.Receipt>();receipts.add(new RumorLedger.Receipt(LEGACY,1,G));
        if(cursor>0){claims.add(new RumorLedger.Claim(ROOT,revoked?2:1,"allegation not established fact","",revoked));receipts.add(new RumorLedger.Receipt(ROOT,1,G));}
        if(second){receipts.add(new RumorLedger.Receipt(ROOT,1,H));receipts.add(new RumorLedger.Receipt(LEGACY,1,H));}
        var snapshot=new RumorLedger.Snapshot(2,WORLD,List.of(new RumorLedger.Courier(PLAYER,COURIER,EPOCH,false)),evidence,claims,List.of(),receipts);
        var stamps=new ArrayList<RumorRecordingState.ReceiptStamp>();
        if(cursor>0)stamps.add(new RumorRecordingState.ReceiptStamp(ROOT,G,1,3));
        if(second)stamps.add(new RumorRecordingState.ReceiptStamp(ROOT,H,1,4));
        var json=JSON.toJsonTree(snapshot).getAsJsonObject();json.remove("version");
        var metadata=new RumorRecordingState.State(1,WORLD,lineage,cursor,RumorRecordingState.Status.ACTIVE,
                cursor==0?Map.of():Map.of(ROOT,new RumorRecordingState.RootStamp(ROOT,1,revoked?2:1,revoked?5:2)),
                stamps,RecordingRecords.sha256(canonical(json).toString()));
        return new RumorRecordingState.DurableView(metadata,snapshot);
    }
    private static RumorLedger.Evidence evidence(UUID root) {
        String excerpt="sourceSecret permitted rumor evidence";
        var proof=new CourierProof(UUID.randomUUID(),1,"test:rule",RecordingRecords.sha256("rule"),"test:event",
                CourierSettings.Source.GAME_EVENT,1,1,"minecraft:overworld",0,64,0,RecordingRecords.sha256(excerpt));
        // Original source identity must be stable over independently deserialized checkpoints.
        proof=new CourierProof(root,proof.sourceRevision(),proof.ruleId(),proof.ruleFingerprint(),proof.eventType(),proof.source(),
                proof.recordedAt(),proof.gameTick(),proof.dimension(),proof.x(),proof.y(),proof.z(),proof.excerptHash());
        return new RumorLedger.Evidence(root,PLAYER,COURIER,EPOCH,excerpt,Set.of(G,H),Set.of(PLAYER),proof);
    }
    private static JsonElement canonical(JsonElement input) {
        if(input.isJsonObject()){var out=new JsonObject();new TreeSet<>(input.getAsJsonObject().keySet()).forEach(k->out.add(k,canonical(input.getAsJsonObject().get(k))));return out;}
        if(input.isJsonArray()){var list=new ArrayList<JsonElement>();input.getAsJsonArray().forEach(e->list.add(canonical(e)));list.sort(Comparator.comparing(JsonElement::toString));var out=new JsonArray();list.forEach(out::add);return out;}
        return input;
    }
    private static WorldRecordingService open(Path world)throws Exception{return await(WorldRecordingService.open(world,WORLD,
            new RecordingSettings(RecordingSettings.Mode.SHADOW,256_000_000,2_000_000,.9,.95),new WorldRecordingService.CutoverBoundary("rumor-adapter-fixture",Map.of())));}
    private static Connection db(Path root,WorldRecordingService store)throws Exception {var properties=new Properties();properties.setProperty("open_mode","1");return DriverManager.getConnection("jdbc:sqlite:"+root.resolve("mythictrpg-recording-v2").resolve(store.datasetId().orElseThrow().toString()).resolve("recording.sqlite"),properties);}
    private static long count(Connection db,String table)throws Exception{return scalar(db,"SELECT count(*) FROM "+table);}
    private static long scalar(Connection db,String sql)throws Exception{try(var query=db.createStatement();var row=query.executeQuery(sql)){if(!row.next())throw new AssertionError("missing diagnostic row");return row.getLong(1);}}
    private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(10,TimeUnit.SECONDS);}
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);checks++;}
}
