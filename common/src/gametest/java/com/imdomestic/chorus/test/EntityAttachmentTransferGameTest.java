package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ResourceProjectionGameTest.compile;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.refused;
import static com.imdomestic.chorus.test.ResourceRuntimeGameTest.domain;

import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/** The native entity stays in A while a real player's source/Buff continuation resolves it from B. */
public class EntityAttachmentTransferGameTest {
    private static JsonArray resolution(String name) {
        var body=new JsonArray();
        body.add(JsonParser.parseString("""
            {"action":{"type":"chorus:resolve_entity_attachment","attachment":"attached"},"as":"point_%s"}
            """.formatted(name)));
        for(String outcome:List.of("resolved","fixed","waiting","closed","missing","replaced","wrong_dimension","expired"))
            body.add(JsonParser.parseString("""
                {"if":{"type":"chorus:result_flag","binding":"point_%s","field":"%s"},
                 "then":[{"type":"chorus:play_cue","cue":"test:%s_%s"}]}
                """.formatted(name,outcome,name,outcome)));
        body.add(JsonParser.parseString("""
            {"if":{"type":"chorus:result_flag","binding":"point_%s","field":"available"},"then":[
              {"type":"chorus:select_targets","center":{"position":"point_%s"},
               "radius":{"type":"chorus:constant","value":1,"unit":"meter"},"relation":"any"}]}
            """.formatted(name,name)));
        body.add(JsonParser.parseString("""
            {"if":{"type":"chorus:result_flag","binding":"point_%s","field":"alive"},
             "then":[{"type":"chorus:play_cue","cue":"test:%s_alive"}],
             "else":[{"type":"chorus:play_cue","cue":"test:%s_not_alive"}]}
            """.formatted(name,name,name)));
        return body;
    }
    private static CompiledEffects program(boolean buffOwned,boolean end) {
        var data=NativeCharacterTransferGameTest.data();
        var body=JsonParser.parseString("""
            [{"action":{"type":"chorus:capture_entity_anchor","target":"victim","anchor":"feet"},"as":"base"},
             {"action":{"type":"chorus:capture_position","target":"victim","anchor":"eyes"},"as":"contact"},
             {"action":{"type":"chorus:begin_entity_attachment","reference":"base","position":"contact",
               "lifetime":{"type":"chorus:constant","value":5,"unit":"second"},
               "policy":{"death":"%s","destroyed":"fix","removed":"fix","unloaded":"wait","transferred":"end"}},"as":"attached"}]
            """.formatted(end?"end":"follow")).getAsJsonArray();
        var second=resolution("second");
        second.add(JsonParser.parseString("{\"type\":\"chorus:end_entity_attachment\",\"attachment\":\"attached\"}"));
        second.add(JsonParser.parseString("{\"type\":\"chorus:play_cue\",\"cue\":\"test:attachment_ended\"}"));
        var delayed=JsonParser.parseString("{\"after\":{\"type\":\"chorus:constant\",\"value\":0.4,\"unit\":\"second\"}}").getAsJsonObject();
        delayed.add("do",second);
        var first=resolution("first");first.add(delayed);
        var initial=JsonParser.parseString("{\"after\":{\"type\":\"chorus:constant\",\"value\":0.2,\"unit\":\"second\"}}").getAsJsonObject();
        initial.add("do",first);body.add(initial);
        var attach=JsonParser.parseString("{\"id\":\"attachment\",\"on\":\"test:attachment\"}").getAsJsonObject();attach.add("do",body);
        var actor=data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules");
        if(buffOwned){
            data.getAsJsonArray("buffs").add(JsonParser.parseString("""
                {"definition":{"id":"test:attachment_carrier","version":"native-character-transfer","duration":"permanent"},"bundle":"test:attachment_carrier"}
                """));
            actor.add(JsonParser.parseString("""
                {"id":"carrier","on":"test:carrier","do":[{"type":"chorus:grant_buff","buff":"test:attachment_carrier"}]}
                """));
            var bundle=JsonParser.parseString("{\"id\":\"test:attachment_carrier\",\"scope\":\"buff\",\"rules\":[]}").getAsJsonObject();
            bundle.getAsJsonArray("rules").add(attach);data.getAsJsonArray("bundles").add(bundle);
        }else actor.add(attach);
        return compile(data);
    }
    private static List<EffectContinuations.Pending> pending(EffectState state,EntityAttachments.Handle handle){
        return state.timers().values().stream().map(t->t.signal().payload()).filter(EffectContinuations.Pending.class::isInstance)
                .map(EffectContinuations.Pending.class::cast).filter(p->p.bindings().containsValue(handle)).toList();
    }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h;final NativeCharacterTransferGameTest.Fixture f;
        final List<LivingEntity> entities=new ArrayList<>();
        final List<RuleEngine.WorldRequest> queries=new ArrayList<>();final List<WorldPosition> centers=new ArrayList<>();
        Consumer<RuleEngine.WorldRequest> afterQuery=_ -> {};boolean closed;
        Harness(GameTestHelper h,boolean buffOwned,boolean end){
            this.h=h;f=new NativeCharacterTransferGameTest.Fixture(h,program(buffOwned,end));f.player.setHealth(20);
            f.afterWorld=(level,request)->{
                if(request.command() instanceof EntityAttachments.Query query){
                    h.assertTrue(level==f.local,"relocated query did not invoke the original entity world's callback");
                    h.assertValueEqual(query.expected().handle().initial().reference().dimension(),domain(level),"query observer changed immutable entity dimension");
                    queries.add(request);afterQuery.accept(request);
                }else if(request.command() instanceof TargetQuery query&&query.center() instanceof TargetQuery.PositionCenter center){
                    h.assertTrue(level==f.remote,"dependent continuation did not stay in its new native world");
                    centers.add(center.position().orElseThrow());
                }
            };
            if(buffOwned)f.departure.start(signal("test:carrier",f.holder));
        }
        RuleEngine.Signal signal(String type,String target){
            return new RuleEngine.Signal(type,new EffectEvent(f.holder,target,new BuffInstance.Origin(f.holder,"","",""),Set.of(),Map.of()));
        }
        LivingEntity cow(int offset){
            var target=Objects.requireNonNull(EntityTypes.COW.create(f.local,EntitySpawnReason.COMMAND));
            target.setPos(f.home.add(offset,0,4));target.setNoGravity(true);target.setNoAi(true);
            f.local.getChunk(target.blockPosition());h.assertTrue(f.local.addFreshEntity(target),"native attachment target was not indexed");entities.add(target);return target;
        }
        EntityAttachments.Handle attach(LivingEntity target){
            f.departure.start(signal("test:attachment",target.getUUID().toString()));f.healthy();
            var handle=f.state(f.local).attachments().values().stream().map(EntityAttachments.Entry::handle)
                    .filter(value->value.initial().reference().target().equals(target.getUUID().toString())).findFirst().orElseThrow();
            h.assertValueEqual(handle.issuer(),Optional.of(domain(f.local)),"attachment was not qualified at original begin");
            h.assertValueEqual(handle.startedAt(),NativeCharacterTransferGameTest.START,"attachment did not use the high issuer clock");
            return handle;
        }
        void move(List<EntityAttachments.Handle> handles){
            var before=new HashMap<EntityAttachments.Handle,EffectContinuations.Pending>();
            for(var handle:handles)before.put(handle,pending(f.state(f.local),handle).getFirst());
            h.assertTrue(f.move(f.remote,_->{})==f.player,"native teleport replaced the player");
            try { NativeMotionGameTest.acknowledge(f.player); }
            catch (Exception error) { throw new IllegalStateException("Native attachment arrival acknowledgment failed",error); }
            f.player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            h.assertTrue(!f.player.isChangingDimension()&&f.player.connection.hasClientLoaded(),"native arrival was not acknowledged");
            f.player.setPos(8,200,8);f.located(f.remote);
            for(var handle:handles){
                h.assertValueEqual(f.state(f.local).attachments().get(handle.id()).handle(),handle,"transport moved or rewrote the original attachment ledger");
                h.assertTrue(pending(f.state(f.local),handle).isEmpty(),"source retained the moved continuation");
                var moved=pending(f.state(f.remote),handle).getFirst();
                h.assertValueEqual(moved.bindings(),before.get(handle).bindings(),"transport changed historical attachment captures");
                h.assertTrue(moved.owner().isPresent()&&moved.relocatedAt().isPresent(),"continuation lost its active relocated owner");
            }
            h.assertTrue(f.state(f.remote).attachments().isEmpty(),"transport copied attachments into the destination");
            h.assertTrue(f.state(f.remote).buffs().timeMicros()<NativeCharacterTransferGameTest.START,"destination did not have an independent lower clock");
        }
        void settle(){f.departure.prepare();f.arrival.prepare();f.healthy();}
        long cues(String cue){return f.cues.stream().filter(cue::equals).count();}
        void completed(List<EntityAttachments.Handle> handles){
            settle();f.located(f.remote);
            h.assertTrue(f.state(f.local).attachments().isEmpty()&&f.state(f.remote).attachments().isEmpty(),"completion left a ghost attachment ledger");
            for(var handle:handles)for(var level:List.of(f.local,f.remote))h.assertTrue(pending(f.state(level),handle).isEmpty(),"completed attachment left delayed work");
            h.assertValueEqual(cues("test:attachment_ended"),(long)handles.size(),"close continuation ran zero or multiple times");
        }
        void later(long ticks,Runnable body){h.runAfterDelay(ticks,()->{try{body.run();}catch(RuntimeException|Error error){close();throw error;}});}
        @Override public void close(){if(closed)return;closed=true;try{f.close();}finally{entities.forEach(Entity::discard);}}
    }
    private static WorldPosition point(LivingEntity target){return EventPositionObservationGameTest.point(target,TargetQuery.Anchor.EYES);}
    private static void followAndFix(GameTestHelper h){
        var t=new Harness(h,false,false);var f=t.f;h.runBeforeTestEnd(t::close);
        try{
            var target=t.cow(0);var handle=t.attach(target);t.move(List.of(handle));
            target.setPos(target.position().add(2,1,0));var deathPoint=point(target);
            h.assertTrue(target.hurtServer(f.local,f.local.damageSources().genericKill(),1000),"native kill was rejected");
            h.assertTrue(target.isDeadOrDying(),"FOLLOW fixture requires an actual dead native entity");t.settle();
            var dead=f.state(f.local).attachments().get(handle.id());
            h.assertValueEqual(dead.phase(),EntityAttachments.Phase.FOLLOWING,"FOLLOW death incorrectly fixed or ended the issuer attachment");
            h.assertTrue(!dead.last().alive(),"native death did not update the issuer observation");
            WorldPosition[] retired={null};
            t.afterQuery=request->{
                if(t.queries.size()==2){retired[0]=point(target);target.discard();target.setPos(target.position().add(100,0,0));}
            };
            t.later(6,()->{
                t.settle();h.assertValueEqual(t.cues("test:first_resolved"),1L,"B did not resolve the original A corpse");
                h.assertValueEqual(t.cues("test:first_not_alive"),1L,"native corpse became an alive observation");
                h.assertValueEqual(t.centers,List.of(deathPoint),"FOLLOW did not rebase the saved contact onto its moved native body");
                target.setPos(target.position().add(3,1,0));
            });
            t.later(15,()->{
                t.completed(List.of(handle));h.assertTrue(target.isRemoved(),"second query did not cross actual native retirement");
                h.assertValueEqual(t.queries.size(),2,"query forwarding retried or skipped an observation");
                h.assertValueEqual(t.cues("test:second_fixed"),1L,"stale query receipt overwrote the newer FIX retirement");
                h.assertValueEqual(t.centers,List.of(deathPoint,Objects.requireNonNull(retired[0])),"FIX used a later discarded object's position");
                t.close();h.succeed();
            });
        }catch(RuntimeException|Error error){t.close();throw error;}
    }
    private static void waitingRestore(GameTestHelper h){
        var t=new Harness(h,true,false);var f=t.f;h.runBeforeTestEnd(t::close);
        try{
            var target=t.cow(0);var handle=t.attach(target);t.move(List.of(handle));
            h.assertTrue(pending(f.state(f.remote),handle).getFirst().scope() instanceof BuffRules.Scope,"archive case must carry a Buff-owned continuation");
            var saved=EntityAnchorGameTest.save(h,target);target.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);t.settle();
            h.assertValueEqual(f.state(f.local).attachments().get(handle.id()).phase(),EntityAttachments.Phase.WAITING,"native unload did not wait at the issuer");
            WorldPosition[] reloadedPoint={null};
            t.later(6,()->{
                t.settle();h.assertValueEqual(t.cues("test:first_waiting"),1L,"missing unloaded entity lost its WAIT resolution");
                h.assertValueEqual(t.queries.size(),1,"WAIT observation did not run exactly once in A");
                h.assertTrue(t.centers.isEmpty(),"WAIT manufactured a usable physical contact");
                var entry=f.state(f.local).attachments().get(handle.id());var remaining=pending(f.state(f.remote),handle).getFirst();
                f.restoreArchive();
                h.assertValueEqual(f.state(f.local).attachments().get(handle.id()),entry,"joint archive changed the original waiting ledger");
                h.assertValueEqual(pending(f.state(f.remote),handle).getFirst(),remaining,"joint archive rewrote the remaining Buff-owned query");
                h.assertValueEqual(t.queries.size(),1,"restoring the archive repeated an already observed query");
                var loaded=Objects.requireNonNull(EntityTypes.COW.create(f.local,EntitySpawnReason.COMMAND));
                loaded.load(TagValueInput.create(ProblemReporter.DISCARDING,f.local.registryAccess(),saved));
                loaded.setPos(loaded.position().add(3,2,0));loaded.setNoGravity(true);loaded.setNoAi(true);
                h.assertTrue(f.local.addFreshEntity(loaded),"same-life native reload was not indexed");t.entities.add(loaded);
                h.assertValueEqual(MinecraftEntityLife.get(loaded).id(),handle.initial().reference().life(),"native NBT reload changed the tracked life");
                reloadedPoint[0]=point(loaded);
            });
            t.later(15,()->{
                t.completed(List.of(handle));h.assertValueEqual(t.queries.size(),2,"remaining query was lost or replayed by restore");
                h.assertValueEqual(t.cues("test:first_waiting"),1L,"completed WAIT reaction replayed on restore");
                h.assertValueEqual(t.cues("test:second_resolved"),1L,"same persisted life did not resume FOLLOW after WAIT");
                h.assertValueEqual(t.cues("test:second_alive"),1L,"same-life reload did not preserve actual live observation");
                h.assertValueEqual(t.centers,List.of(Objects.requireNonNull(reloadedPoint[0])),"reloaded native position was not observed in the original world");
                var cues=List.copyOf(f.cues);f.restoreArchive();
                h.assertValueEqual(f.cues,cues,"restoring the completed attachment replayed its effects");
                t.later(4,()->{t.completed(List.of(handle));h.assertValueEqual(t.queries.size(),2,"later restored tick retried observation");h.assertValueEqual(f.cues,cues,"later restored tick repeated an attachment reaction");t.close();h.succeed();});
            });
        }catch(RuntimeException|Error error){t.close();throw error;}
    }
    private static void ended(GameTestHelper h){
        var t=new Harness(h,false,true);var f=t.f;h.runBeforeTestEnd(t::close);
        try{
            var killed=t.cow(0);var moved=t.cow(2);var first=t.attach(killed);var second=t.attach(moved);var handles=List.of(first,second);t.move(handles);
            h.assertTrue(killed.hurtServer(f.local,f.local.damageSources().genericKill(),1000),"END fixture native kill was rejected");
            var transferred=(LivingEntity)moved.teleport(new TeleportTransition(f.remote,new Vec3(10,200,10),Vec3.ZERO,0,0,TeleportTransition.DO_NOTHING));
            h.assertTrue(transferred!=null&&moved.isRemoved(),"native tracked entity did not change dimension");t.entities.add(transferred);
            h.assertValueEqual(MinecraftEntityLife.get(transferred).id(),second.initial().reference().life(),"native dimension copy changed entity life");t.settle();
            h.assertTrue(f.state(f.local).attachments().isEmpty(),"death/transfer END did not retire the issuer ledger");
            t.later(15,()->{
                t.completed(handles);h.assertValueEqual(t.cues("test:first_closed"),2L,"first destination query did not observe both retired ledgers");
                h.assertValueEqual(t.cues("test:second_closed"),2L,"later destination query resurrected an ended attachment");
                h.assertTrue(t.queries.isEmpty()&&t.centers.isEmpty(),"ended immutable references still observed a replacement native world");
                t.close();h.succeed();
            });
        }catch(RuntimeException|Error error){t.close();throw error;}
    }
    private static void unknown(GameTestHelper h){
        var t=new Harness(h,false,false);var f=t.f;h.runBeforeTestEnd(t::close);
        try{
            var target=t.cow(0);var handle=t.attach(target);t.move(List.of(handle));var retired=point(target);
            t.afterQuery=request->{target.discard();target.setPos(target.position().add(100,0,0));throw new IllegalStateException("Injected lost forwarded attachment observation after native retirement");};
            t.later(6,()->{
                assertUnknown(t,target,handle,retired);
                var source=f.departure.state();var destination=f.arrival.state();var evidence=f.host.combatFailure().orElseThrow();
                t.later(12,()->{
                    assertUnknown(t,target,handle,retired);
                    h.assertValueEqual(f.departure.state(),source,"failed original issuer replayed native retirement");
                    h.assertValueEqual(f.arrival.state(),destination,"unknown forwarded query resumed without a world receipt");
                    h.assertValueEqual(f.host.combatFailure().orElseThrow(),evidence,"later tick replaced unknown retirement evidence");
                    t.close();h.succeed();
                });
            });
        }catch(RuntimeException|Error error){t.close();throw error;}
    }
    private static void assertUnknown(Harness t,LivingEntity target,EntityAttachments.Handle handle,WorldPosition retired){
        var h=t.h;var f=t.f;
        h.assertTrue(target.isRemoved()&&f.local.getEntity(target.getUUID())==null,"unknown callback rolled back actual native removal");
        h.assertValueEqual(t.queries.size(),1,"unknown query callback was retried");
        h.assertTrue(f.departure.failure().isPresent()&&f.arrival.failure().isPresent(),"unknown callback did not stop both participating runtimes");
        h.assertValueEqual(f.arrival.state().engine().pending(),Optional.of(t.queries.getFirst().id()),"forwarding lost the exact caller world operation identity");
        var entry=f.departure.state().engine().domain().attachments().get(handle.id());
        h.assertValueEqual(entry.phase(),EntityAttachments.Phase.FIXED,"unknown callback discarded known issuer retirement");
        h.assertValueEqual(entry.handle(),handle,"unknown callback changed issuer or attachment identity");
        h.assertValueEqual(handle.position(entry.last()),retired,"unknown callback used a later discarded native object position");
        h.assertTrue(f.arrival.state().engine().domain().attachments().isEmpty(),"unknown callback copied issuer ledger to B");
        var evidence=f.host.combatFailure().orElseThrow();h.assertTrue(evidence.published(),"actual retirement evidence was not committed");
        h.assertValueEqual(evidence.observed().domain(domain(f.local)).attachments().get(handle.id()),entry,"issuer state differs from retained failure evidence");
        h.assertValueEqual(evidence.attachments().size(),1,"known native retirement was lost or admitted repeatedly");
        h.assertValueEqual(evidence.attachments().getFirst().changes().size(),1,"retirement changed an unrelated attachment");
        h.assertValueEqual(evidence.attachments().getFirst().changes().getFirst().issuer(),domain(f.local),"retirement settled against the continuation domain");
        var facts=evidence.facts().getOrDefault(domain(f.local),List.of()).stream().filter(s->s.type().equals(EntityLifecycle.REMOVED)).toList();
        h.assertValueEqual(facts.size(),1,"unknown boundary lost or duplicated actual native removal notification");
        h.assertTrue(t.centers.isEmpty()&&t.cues("test:first_resolved")==0&&t.cues("test:attachment_ended")==0,"unknown receipt fabricated continuation success");
        refused(h,IllegalStateException.class,f.host::checkpoint);
    }
    private static void ready(GameTestHelper h,Runnable body){
        var remote=Objects.requireNonNull(h.getLevel().getServer().getLevel(Level.NETHER));var chunk=new ChunkPos(0,0);
        boolean forced=remote.setChunkForced(chunk.x(),chunk.z(),true);
        h.runBeforeTestEnd(()->{if(forced)remote.setChunkForced(chunk.x(),chunk.z(),false);});
        remote.getChunk(chunk.x(),chunk.z());remote.waitForEntities(chunk,0);
        h.startSequence().thenWaitUntil(()->h.assertTrue(remote.areEntitiesActuallyLoadedAndTicking(chunk),"attachment destination entities must be loaded and ticking")).thenExecute(body);
    }
    @GameCase(environment="chorus_gametest:attachment_native_follow",maxTicks=60)
    public void relocatedSourceObservesNativeCorpseAndConcurrentFixAtOriginalEntityWorld(GameTestHelper h){ready(h,()->followAndFix(h));}
    @GameCase(environment="chorus_gametest:attachment_native_restore",maxTicks=60)
    public void jointArchiveRestoresBuffOwnedWaitAndSameLifeNativeReloadWithoutReplay(GameTestHelper h){ready(h,()->waitingRestore(h));}
    @GameCase(environment="chorus_gametest:attachment_native_end",maxTicks=60)
    public void originalIssuerEndsOnNativeDeathAndEntityDimensionRemoval(GameTestHelper h){ready(h,()->ended(h));}
    @GameCase(environment="chorus_gametest:attachment_native_unknown",maxTicks=60)
    public void lostForwardedQueryReceiptKeepsActualRetirementAndCallerPendingWithoutReplay(GameTestHelper h){ready(h,()->unknown(h));}
}
