package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.compile;
import static com.imdomestic.chorus.test.ResourceProjectionGameTest.refused;
import static com.imdomestic.chorus.test.ResourceRuntimeGameTest.domain;

import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.projectile.ShotGroups;
import com.imdomestic.chorus.platform.minecraft.EffectProjectile;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Actual transport moves future launches, while every receipt continues the original shot ledger. */
public class ShotGroupTransferGameTest {
    private static final String PERK="test:arrival_perk";
    private static final Vec3 ARRIVAL=new Vec3(8,200,8);

    private static JsonArray member(int pellet,boolean projectile){
        var steps=JsonParser.parseString("""
            [{"action":{"type":"chorus:capture_position","target":"self","anchor":"eyes"},"as":"muzzleINDEX"},
             {"action":{"type":"chorus:capture_direction"},"as":"aimINDEX"}]
            """.replace("INDEX",Integer.toString(pellet))).getAsJsonArray();
        var step=JsonParser.parseString("""
            {"as":"impactINDEX","shot":{"binding":"shot","pellet":{"type":"chorus:constant","value":INDEX,"unit":"count"}},
             "do":[{"for_each":"impactINDEX","as":"victimINDEX","do":[
               {"type":"chorus:damage_snapshot","snapshot":"attack","target":{"binding":"victimINDEX"},"pellet":"impactINDEX"}]}]}
            """.replace("INDEX",Integer.toString(pellet))).getAsJsonObject();
        step.add(projectile?"projectile":"hitscan",JsonParser.parseString((projectile?"""
            {"position":"muzzleINDEX","direction":"aimINDEX",
             "speed":{"type":"chorus:constant","value":20,"unit":"meter_per_second"},
             "gravity":{"type":"chorus:constant","value":0,"unit":"meter_per_second_squared"},
             "drag":{"type":"chorus:constant","value":1,"unit":"multiplier"},
             "lifetime":{"type":"chorus:constant","value":1,"unit":"second"}}
            """:"""
            {"position":"muzzleINDEX","direction":"aimINDEX","range":{"type":"chorus:constant","value":6,"unit":"meter"}}
            """).replace("INDEX",Integer.toString(pellet))));
        steps.add(step);return steps;
    }
    private static JsonObject after(double seconds,JsonArray body){
        var value=new JsonObject();value.add("after",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":"+seconds+",\"unit\":\"second\"}"));
        value.add("do",body);return value;
    }
    private static CompiledEffects program(boolean buffOwned){
        var data=NativeCharacterTransferGameTest.data();
        data.add("profiles",JsonParser.parseString("""
            [{"id":"test:shot_damage","version":"native-character-transfer","input_unit":"damage","steps":[]}]
            """));
        var body=JsonParser.parseString("""
            [{"action":{"type":"chorus:capture_damage","amount":{"type":"chorus:constant","value":3,"unit":"damage"},
               "damage_type":"minecraft:generic","scaling_profile":"test:shot_damage"},"as":"attack"},
             {"action":{"type":"chorus:begin_shot","pellets":{"type":"chorus:constant","value":3,"unit":"count"},
               "lifetime":{"type":"chorus:constant","value":5,"unit":"second"}},"as":"shot"}]
            """).getAsJsonArray();
        member(0,false).forEach(body::add);
        var second=member(1,false);second.add(after(.6,member(2,true)));body.add(after(.2,second));
        var rules=data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules");
        var start=JsonParser.parseString("{\"id\":\"shot\",\"on\":\"test:shot\",\"do\":[]}").getAsJsonObject();
        if(buffOwned){
            data.getAsJsonArray("buffs").add(JsonParser.parseString("""
                {"definition":{"id":"test:shot_carrier","version":"native-character-transfer","duration":"permanent"},"bundle":"test:shot_carrier"}
                """));
            start.getAsJsonArray("do").add(JsonParser.parseString("{\"type\":\"chorus:grant_buff\",\"buff\":\"test:shot_carrier\"}"));
            var carrier=JsonParser.parseString("""
                {"id":"test:shot_carrier","scope":"buff","rules":[
                  {"id":"shot","on":"chorus:buff_gained","if":{"type":"chorus:own_buff"},"do":[]}]}
                """).getAsJsonObject();
            carrier.getAsJsonArray("rules").get(0).getAsJsonObject().add("do",body);data.getAsJsonArray("bundles").add(carrier);
        }else start.add("do",body);
        rules.add(start);
        rules.add(JsonParser.parseString("""
            {"id":"arrival","on":"test:arrival_perk","do":[{"type":"chorus:grant_buff","buff":"test:arrival_perk"}]}
            """));
        for(String kind:List.of("hit","shot_progress","shot_resolved")){
            var rule=JsonParser.parseString("""
            {"id":"origin_KIND","on":"chorus:KIND",
             "if":{"type":"chorus:source_is","source":"owner"},
             "do":[{"type":"chorus:play_cue","cue":"test:origin_KIND"}]}
            """.replace("KIND",kind)).getAsJsonObject();
            if(kind.equals("hit"))rule.addProperty("binding","origin_bundle");
            rules.add(rule);
        }
        data.getAsJsonArray("buffs").add(JsonParser.parseString("""
            {"definition":{"id":"test:arrival_perk","version":"native-character-transfer","duration":"permanent",
              "components":{"numbers":{"progress":{"initial":0,"unit":"count"},"shots":{"initial":0,"unit":"count"},
                "resolved":{"initial":0,"unit":"count"},"total":{"initial":0,"unit":"count"}},"sets":["seen"]}},"bundle":"test:arrival_perk"}
            """));
        data.getAsJsonArray("bundles").add(JsonParser.parseString("""
            {"id":"test:arrival_perk","scope":"buff","rules":[
              {"id":"progress","on":"chorus:shot_progress","if":{"type":"chorus:target_is","left":"event_actor","right":"self"},"do":[
                {"type":"chorus:update_component","buff":"test:arrival_perk","component":"progress","op":"add","value":{"type":"chorus:constant","value":1,"unit":"count"}},
                {"type":"chorus:update_component","buff":"test:arrival_perk","component":"shots","op":"add","value":{"type":"chorus:constant","value":1,"unit":"count"},
                 "once_set":"seen","event_reference":"shot"}]},
              {"id":"resolved","on":"chorus:shot_resolved","if":{"type":"chorus:event_flag","name":"all_hit"},"do":[
                {"type":"chorus:update_component","buff":"test:arrival_perk","component":"resolved","op":"add","value":{"type":"chorus:constant","value":1,"unit":"count"}},
                {"type":"chorus:update_component","buff":"test:arrival_perk","component":"total","op":"add","value":{"type":"chorus:event_number","name":"pellets_hit","unit":"count"}},
                {"type":"chorus:heal","amount":{"type":"chorus:constant","value":2,"unit":"damage"}},
                {"type":"chorus:play_cue","cue":"test:arrival_resolved"}]}]}
            """));
        return compile(data);
    }
    private static RuleEngine.Signal signal(NativeCharacterTransferGameTest.Fixture f,String type){
        return new RuleEngine.Signal(type,new EffectEvent(f.holder,f.holder,new BuffInstance.Origin(f.holder,"","",""),Set.of(),Map.of()));
    }
    private static List<EffectContinuations.Pending> pending(EffectState state,ShotGroups.Handle handle){
        return state.timers().values().stream().map(timer->timer.signal().payload()).filter(EffectContinuations.Pending.class::isInstance)
                .map(EffectContinuations.Pending.class::cast).filter(value->value.bindings().containsValue(handle)).toList();
    }
    private static long cues(NativeCharacterTransferGameTest.Fixture f,String cue){return f.cues.stream().filter(cue::equals).count();}
    private static final class Harness implements AutoCloseable {
        final NativeCharacterTransferGameTest.Fixture f;final LivingEntity firstTarget,secondTarget;boolean closed;
        Harness(GameTestHelper h,boolean restore){
            f=new NativeCharacterTransferGameTest.Fixture(h,program(restore));
            LivingEntity first=null,second=null;
            try{
                f.player.setHealth(10);f.player.setYRot(0);f.player.setXRot(0);
                first=target(f.local,f.home.add(0,1,4));second=target(f.remote,ARRIVAL.add(0,1,4));
                firstTarget=first;secondTarget=second;
            }catch(RuntimeException|Error error){if(first!=null)first.discard();if(second!=null)second.discard();f.close();throw error;}
        }
        private static LivingEntity target(ServerLevel level,Vec3 position){
            level.getChunk(BlockPos.containing(position));
            var target=Objects.requireNonNull(EntityTypes.COW.create(level,EntitySpawnReason.COMMAND));
            target.setPos(position);target.setNoAi(true);target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);target.setHealth(20);
            if(!level.addFreshEntity(target)||level.getEntity(target.getUUID())!=target){target.discard();throw new IllegalStateException("Native pellet target was not indexed");}
            return target;
        }
        List<EffectProjectile> projectiles(){return f.remote.getEntitiesOfClass(EffectProjectile.class,new AABB(ARRIVAL,ARRIVAL.add(0,2,6)).inflate(2));}
        void perk(GameTestHelper h,ShotGroups.Handle handle,int progress,boolean resolved){
            var buff=f.state(f.remote).buffs().instances().values().stream().filter(b->b.definition().id().equals(PERK)).findFirst().orElseThrow();
            var values=buff.components().numbers();
            near(h,values.get("progress"),progress,"current arrival perk missed or repeated issuer progress");
            near(h,values.get("shots"),progress==0?0:1,"shot reference did not deduplicate successive members");
            near(h,values.get("resolved"),resolved?1:0,"current arrival perk missed or repeated final summary");
            near(h,values.get("total"),resolved?3:0,"resolved perk did not see all original issuer members");
            h.assertValueEqual(buff.components().sets().get("seen"),progress==0?Set.of():Set.of(handle.reference()),"arrival perk observed a different shot identity");
            h.assertTrue(f.state(f.local).buffs().instances().values().stream().noneMatch(b->b.definition().id().equals(PERK)),"arrival-only perk leaked into issuer");
        }
        void second(GameTestHelper h,ShotGroups.Handle handle){
            f.departure.prepare();f.arrival.prepare();f.healthy();f.located(f.remote);
            near(h,firstTarget.getHealth(),17,"first physical victim changed after transport");
            near(h,secondTarget.getHealth(),17,"remaining hitscan member did not hit in the destination");
            h.assertValueEqual(f.damageCalls,2,"remaining ray damage replayed or was skipped");
            h.assertTrue(f.state(f.remote).shotGroups().isEmpty(),"arrival copied its issuer ledger");
            var group=f.state(f.local).shotGroups().get(handle.id());h.assertValueEqual(group.handle(),handle,"issuer handle changed");
            h.assertValueEqual(group.pellets().keySet(),Set.of(0,1),"delayed third member launched early");
            h.assertValueEqual(group.pellets().get(0).hits(),Set.of(firstTarget.getUUID().toString()),"original member lost its actual victim");
            h.assertValueEqual(group.pellets().get(1).effective(),Set.of(secondTarget.getUUID().toString()),"destination receipt did not update issuer progress");
            h.assertValueEqual(cues(f,"test:origin_hit"),2L,"migrated origin-bundle hit rules did not consume both actual hits");
            h.assertValueEqual(cues(f,"test:origin_shot_progress"),2L,"migrated current source rules missed issuer progress");
            h.assertTrue(projectiles().isEmpty(),"fixture must restore before the physical projectile exists");
            perk(h,handle,1,false);
        }
        void complete(GameTestHelper h,ShotGroups.Handle handle){
            f.departure.prepare();f.arrival.prepare();f.healthy();f.located(f.remote);
            near(h,firstTarget.getHealth(),17,"completion changed the original physical victim");
            near(h,secondTarget.getHealth(),14,"third physical member did not apply its actual destination damage");
            near(h,f.player.getHealth(),12,"arrival perk did not apply one actual summary heal");
            h.assertValueEqual(f.damageCalls,3,"confirmed physical damage was replayed");
            h.assertValueEqual(f.heals,1,"summary heal was missing or repeated");
            h.assertValueEqual(cues(f,"test:origin_hit"),3L,"migrated source lost a member hit");
            h.assertValueEqual(cues(f,"test:origin_shot_progress"),3L,"migrated source lost or duplicated member progress");
            h.assertValueEqual(cues(f,"test:origin_shot_resolved"),1L,"migrated source missed the original issuer summary");
            h.assertValueEqual(cues(f,"test:arrival_resolved"),1L,"arrival perk payoff was lost or replayed");
            for(var level:List.of(f.local,f.remote)){
                h.assertTrue(f.state(level).shotGroups().isEmpty(),"completed shot left a ghost ledger");
                h.assertTrue(!f.state(level).timers().containsKey(handle.timerId()),"completed shot retained its expiry timer");
                h.assertTrue(pending(f.state(level),handle).isEmpty(),"completed shot left a continuation");
            }
            h.assertTrue(projectiles().isEmpty(),"completed shot retained its physical projectile");perk(h,handle,2,true);
        }
        @Override public void close(){if(closed)return;closed=true;try{f.close();}finally{firstTarget.discard();secondTarget.discard();}}
    }
    private static void ready(GameTestHelper h,boolean restore){
        var t=new Harness(h,restore);var f=t.f;h.runBeforeTestEnd(t::close);
        try{
            f.departure.start(signal(f,"test:shot"));f.healthy();
            var handle=f.state(f.local).shotGroups().values().iterator().next().handle();
            h.assertValueEqual(handle.issuer(),Optional.of(domain(f.local)),"begin did not retain the original issuer");
            h.assertValueEqual(handle.referenceFormat(),ShotGroups.ReferenceFormat.ISSUED_V1,"coordinated shot did not qualify event identity");
            var continuation=pending(f.state(f.local),handle).getFirst();
            h.assertTrue(restore?continuation.scope() instanceof BuffRules.Scope:continuation.scope() instanceof EffectSource,"fixture used the wrong continuation lifetime");
            near(h,t.firstTarget.getHealth(),17,"first native ray did not hit before transport");
            h.assertValueEqual(f.damageCalls,1,"first ray did not execute once");
            h.assertValueEqual(cues(f,"test:origin_shot_progress"),1L,"original source did not consume first-member progress");
            h.assertTrue(f.move(f.remote,_->{})==f.player,"native teleport replaced the same live player");
            NativeMotionGameTest.acknowledge(f.player);f.player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            h.assertTrue(!f.player.isChangingDimension()&&f.player.connection.hasClientLoaded(),"native arrival has not been acknowledged");
            // Above the Nether roof, loaded geometry remains empty without mutating terrain or damage protection.
            f.player.setPos(ARRIVAL);f.player.setYRot(0);f.player.setXRot(0);f.located(f.remote);f.healthy();
            h.assertValueEqual(pending(f.state(f.remote),handle).getFirst().bindings(),continuation.bindings(),"transport changed captured membership or attack");
            h.assertTrue(pending(f.state(f.local),handle).isEmpty()&&f.state(f.remote).shotGroups().isEmpty(),"transport copied continuation or issuer ledger");
            h.assertValueEqual(f.state(f.local).timers().get(handle.timerId()).dueAt(),handle.dueAt(),"transport rebased issuer expiry");
            h.assertTrue(f.arrival.shotActive(new ShotGroups.Member(handle,1)),"native destination could not query its foreign issuer");
            f.arrival.start(signal(f,"test:arrival_perk"));t.perk(h,handle,0,false);
            h.runAfterDelay(5,()->{
                try{
                    t.second(h,handle);
                    if(restore){
                        var group=f.state(f.local).shotGroups().get(handle.id());var remaining=pending(f.state(f.remote),handle).getFirst();
                        f.restoreArchive();
                        h.assertValueEqual(f.state(f.local).shotGroups().get(handle.id()),group,"joint archive changed confirmed issuer members");
                        h.assertValueEqual(pending(f.state(f.remote),handle).getFirst(),remaining,"joint archive changed the remaining Buff-owned launch");
                        t.second(h,handle);
                    }
                }catch(RuntimeException|Error error){t.close();throw error;}
            });
            h.runAfterDelay(17,()->{
                try{
                    f.departure.prepare();f.arrival.prepare();f.healthy();
                    var physical=t.projectiles();h.assertValueEqual(physical.size(),1,"remaining member did not create one native destination projectile");
                    h.assertValueEqual(physical.getFirst().launch().orElseThrow().member().orElseThrow(),new ShotGroups.Member(handle,2),"physical projectile lost its original issuer membership");
                    h.assertValueEqual(f.state(f.local).shotGroups().get(handle.id()).pellets().get(2).phase(),ShotGroups.Phase.FLYING,"launch receipt did not commit to issuer");
                    h.assertTrue(f.state(f.remote).shotGroups().isEmpty(),"projectile launch copied its ledger to the destination");
                    h.assertValueEqual(f.damageCalls,2,"projectile applied speculative damage before contact");
                }catch(RuntimeException|Error error){t.close();throw error;}
            });
            h.runAfterDelay(24,()->{try{t.complete(h,handle);f.host.checkpoint();if(restore)f.restoreArchive();}catch(RuntimeException|Error error){t.close();throw error;}});
            h.runAfterDelay(29,()->{try(t){t.complete(h,handle);f.host.checkpoint();h.succeed();}});
        }catch(Exception|Error error){t.close();throw new IllegalStateException(error);}
    }
    private static void unknown(GameTestHelper h){
        var t=new Harness(h,false);var f=t.f;h.runBeforeTestEnd(t::close);
        try{
            f.departure.start(signal(f,"test:shot"));f.healthy();
            var handle=f.state(f.local).shotGroups().values().iterator().next().handle();
            near(h,t.firstTarget.getHealth(),17,"first ray did not establish its confirmed issuer progress");
            h.assertTrue(f.move(f.remote,_->{})==f.player,"native teleport replaced the live shot owner");
            NativeMotionGameTest.acknowledge(f.player);f.player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            h.assertTrue(!f.player.isChangingDimension()&&f.player.connection.hasClientLoaded(),"native arrival has not been acknowledged");
            f.player.setPos(ARRIVAL);f.player.setYRot(0);f.player.setXRot(0);f.located(f.remote);
            f.arrival.start(signal(f,"test:arrival_perk"));
            int[] lost={0};
            f.afterWorld=(level,request)->{
                if(level==f.remote&&request.command() instanceof DamageCommand){
                    near(h,t.secondTarget.getHealth(),17,"failure hook must run after actual native health loss");
                    lost[0]++;throw new IllegalStateException("Injected lost cross-domain pellet world receipt");
                }
            };
            h.runAfterDelay(6,()->{
                try{
                    assertUnknown(h,t,handle,lost[0]);
                    var source=f.departure.state();var destination=f.arrival.state();var evidence=f.host.combatFailure().orElseThrow();
                    h.runAfterDelay(20,()->{
                        try(t){
                            assertUnknown(h,t,handle,lost[0]);
                            h.assertValueEqual(f.departure.state(),source,"failed issuer changed or replayed after later native ticks");
                            h.assertValueEqual(f.arrival.state(),destination,"unknown destination action was resumed without a receipt");
                            h.assertValueEqual(f.host.combatFailure().orElseThrow(),evidence,"later ticks duplicated committed shot evidence");
                            h.succeed();
                        }
                    });
                }catch(RuntimeException|Error error){t.close();throw error;}
            });
        }catch(Exception|Error error){t.close();throw new IllegalStateException(error);}
    }
    private static void assertUnknown(GameTestHelper h,Harness t,ShotGroups.Handle handle,int lost){
        var f=t.f;
        near(h,t.firstTarget.getHealth(),17,"unknown remote receipt changed the first actual victim");
        near(h,t.secondTarget.getHealth(),17,"unknown callback rolled back or replayed the actual destination hit");
        near(h,f.player.getHealth(),10,"unknown callback fabricated a completed-shot payoff");
        h.assertValueEqual(f.damageCalls,2,"lost callback retried native damage");h.assertValueEqual(lost,1,"world failure hook was replayed");
        h.assertTrue(f.departure.failure().isPresent()&&f.arrival.failure().isPresent(),"unknown callback did not stop both native domains");
        h.assertTrue(f.arrival.state().engine().pending().isPresent(),"unknown world callback received a fabricated completion");
        var evidence=f.host.combatFailure().orElseThrow();h.assertTrue(evidence.published(),"actual remote shot progress was not committed");
        h.assertValueEqual(f.departure.failure().orElseThrow().coordinated(),Optional.of(evidence),"issuer lost complete cross-domain failure evidence");
        h.assertValueEqual(f.arrival.failure().orElseThrow().coordinated(),Optional.of(evidence),"destination lost complete cross-domain failure evidence");
        h.assertTrue(evidence.pending().isEmpty(),"confirmed physical hit became an unknown native hurt operation");
        h.assertValueEqual(evidence.shots().size(),1,"actual native shot settlement was lost or counted twice");
        var settled=evidence.shots().getFirst();
        h.assertValueEqual(settled.pin().handle(),handle,"failure retained a different shot handle");
        h.assertValueEqual(settled.pin().issuer(),domain(f.local),"failure moved its issuer authority");
        h.assertValueEqual(settled.pin().caller(),domain(f.remote),"failure forgot the actual destination caller");
        near(h,settled.receipt().healthLoss(),3,"failure evidence changed actual native health loss");
        var group=f.departure.state().engine().domain().shotGroups().get(handle.id());
        h.assertValueEqual(group,evidence.observed().domain(domain(f.local)).shotGroups().get(handle.id()),"committed issuer differs from observed shot evidence");
        h.assertValueEqual(group.pellets().keySet(),Set.of(0,1),"unknown callback started a later member");
        var contact=group.pellets().get(1);
        h.assertTrue(contact.contactOpen()&&contact.phase()==ShotGroups.Phase.FLYING,"unknown receipt falsely closed its contact");
        h.assertValueEqual(contact.hits(),Set.of(t.secondTarget.getUUID().toString()),"actual hit was discarded at its original issuer");
        h.assertValueEqual(contact.effective(),contact.hits(),"actual effective damage was discarded at its original issuer");
        h.assertTrue(f.arrival.state().engine().domain().shotGroups().isEmpty(),"failure copied the group to its physical destination");
        var progress=evidence.facts().getOrDefault(domain(f.remote),List.of()).stream().filter(s->s.type().equals(ShotGroups.PROGRESS)).toList();
        h.assertValueEqual(progress.size(),1,"unknown receipt lost or duplicated its committed progress fact");
        h.assertValueEqual(((ShotGroups.Progress)progress.getFirst().payload()).event().references().get("shot"),handle.reference(),"retained progress changed shot identity");
        h.assertValueEqual(f.arrival.state().engine().facts().stream().filter(e->e.signal().type().equals(ShotGroups.PROGRESS)).count(),1L,"destination inbox did not retain exactly one progress event");
        h.assertValueEqual(cues(f,"test:origin_shot_progress"),1L,"unknown callback ran queued progress as if the action succeeded");
        h.assertValueEqual(cues(f,"test:origin_shot_resolved"),0L,"unknown callback invented a final summary");
        h.assertValueEqual(cues(f,"test:arrival_resolved"),0L,"unknown callback ran arrival payoff");
        h.assertTrue(t.projectiles().isEmpty(),"unknown callback still launched its delayed third member");
        refused(h,IllegalStateException.class,f.host::checkpoint);
    }
    private static void inReadyChunk(GameTestHelper h,Runnable body){
        var remote=Objects.requireNonNull(h.getLevel().getServer().getLevel(Level.NETHER));var chunk=new ChunkPos(0,0);
        boolean forced=remote.setChunkForced(chunk.x(),chunk.z(),true);
        h.runBeforeTestEnd(()->{if(forced)remote.setChunkForced(chunk.x(),chunk.z(),false);});
        // A terrain ticket is not an entity-load barrier. Use the same managed loading wait as
        // native player spawn, so asynchronous entity I/O does not consume the simulation budget.
        remote.getChunk(chunk.x(),chunk.z());remote.waitForEntities(chunk,0);
        h.startSequence().thenWaitUntil(()->h.assertTrue(remote.areEntitiesActuallyLoadedAndTicking(chunk),
                "remote pellet chunk must be loaded and ticking: forced="+remote.getForceLoadedChunks().contains(chunk.pack())
                        +", entities="+remote.areEntitiesLoaded(chunk.pack())+", terrain="+(remote.getChunkSource().getChunkNow(chunk.x(),chunk.z())!=null)))
                .thenExecute(body);
    }
    @GameCase(environment="chorus_gametest:shot_native_transfer",maxTicks=80)
    public void nativeTransferContinuesIssuerShotWithDestinationRayProjectileAndCurrentPerks(GameTestHelper h){inReadyChunk(h,()->ready(h,false));}
    @GameCase(environment="chorus_gametest:shot_native_restore",maxTicks=80)
    public void jointArchiveRestoresBuffOwnedRemainingMembersWithoutReplayingActualHits(GameTestHelper h){inReadyChunk(h,()->ready(h,true));}
    @GameCase(environment="chorus_gametest:shot_native_unknown",maxTicks=80)
    public void lostDestinationDamageReceiptRetainsIssuerProgressAndCannotReplay(GameTestHelper h){inReadyChunk(h,()->unknown(h));}
}
