package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.buff.Buffs;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ObservedOperationDomainTest {
    private static final Signal CALL=new Signal("test:call",Empty.INSTANCE);
    private static final Signal OLDER=new Signal("test:older",Empty.INSTANCE);
    private static final Signal CONFIRMED=new Signal("test:confirmed_child",Empty.INSTANCE);
    private static CompiledEffects program(){
        return EffectTestSupport.compile(JsonParser.parseString("""
            {"version":"observed-operation","buffs":[{"definition":{"id":"test:charge","version":"observed-operation","duration":1}}],
             "bundles":[{"id":"test:caller","rules":[{"id":"call","on":"test:call","do":[{"type":"chorus:play_cue","cue":"test:world"}]}]}]}
            """).getAsJsonObject());
    }
    private static EffectSession session(Function<WorldRequest,ActionResult> world){
        var program=program();var source=EffectTestSupport.source("test:caller");
        var initial=EffectState.empty().withSource(source);
        initial=initial.withBuffs(Buffs.grant(initial.buffs(),program.buff("test:charge"),"player","player",source.origin(),1,1,1_000_000).store());
        return new EffectSession(program,EffectCheckpointTest.clock(),1,initial,world);
    }
    private static EffectState state(EffectSession session){return session.state().engine().domain();}
    private static EffectState consume(EffectState state){
        var key=state.buffs().instances().keySet().iterator().next();
        return state.withBuffs(Buffs.consume(state.buffs(),key,1).store());
    }
    private static List<Signal> facts(EffectSession session){return session.state().engine().facts().stream().map(Event::signal).toList();}

    @Test void confirmedChildWritesAndOlderRecipientInboxSurviveAnUnknownParentWithoutReplay(){
        var recipient=session(_->{throw new AssertionError("Observed publication must not run recipient rules");});
        var recipientBefore=recipient.coordinationState();
        EffectSession.commitDomains(List.of(recipient.prepareInboxDomain(recipientBefore,state(recipient),List.of(OLDER))));
        var caller=new AtomicReference<EffectSession>();var token=new AtomicReference<EffectSession.WorldOperation>();
        var pending=new AtomicReference<OperationId>();var writes=new AtomicInteger();
        var unknown=new IllegalStateException("physical parent outcome unknown after confirmed child");
        var session=session(request->{
            writes.incrementAndGet();var owner=caller.get();var operation=owner.operation(request);token.set(operation);pending.set(request.id());
            var before=owner.operationState(operation);var remote=recipient.coordinationState();
            var localUpdate=owner.prepareObservedOperationDomain(operation,before,consume(before.engine().domain()),List.of(CONFIRMED));
            var remoteUpdate=recipient.prepareInboxDomain(remote,consume(remote.engine().domain()),List.of(CONFIRMED));
            assertSame(before,owner.state());assertSame(remote,recipient.state());
            EffectSession.commitDomains(List.of(localUpdate,remoteUpdate));
            assertEquals(before.engine().pending(),owner.state().engine().pending());
            assertEquals(before.engine().frames(),owner.state().engine().frames());
            assertEquals(before.engine().receipts(),owner.state().engine().receipts());assertTrue(operation.committed().isEmpty());
            throw unknown;
        });
        caller.set(session);assertSame(unknown,assertThrows(IllegalStateException.class,()->session.start(0,CALL)));
        assertTrue(state(session).buffs().instances().isEmpty());assertTrue(state(recipient).buffs().instances().isEmpty());
        assertEquals(Optional.of(pending.get()),session.state().engine().pending());assertTrue(token.get().committed().isEmpty());
        assertEquals(List.of(CONFIRMED),facts(session));assertEquals(List.of(OLDER,CONFIRMED),facts(recipient));
        assertFalse(session.running());assertTrue(recipient.inboxPending());assertTrue(session.state().engine().receipts().isEmpty());
        assertThrows(IllegalStateException.class,session::checkpoint);assertThrows(IllegalStateException.class,recipient::checkpoint);
        assertThrows(IllegalStateException.class,()->session.start(0,CALL));assertThrows(IllegalStateException.class,()->session.observe(1,List.of()));
        assertThrows(IllegalStateException.class,session::drain);assertEquals(1,writes.get());
        assertThrows(IllegalStateException.class,()->session.prepareObservedOperationDomain(token.get(),session.state(),state(session),List.of()));
        recipient.drain();assertTrue(recipient.state().idle());assertTrue(state(recipient).buffs().instances().isEmpty());
        assertDoesNotThrow(recipient::checkpoint);assertEquals(1,writes.get());
    }

    @Test void foreignActiveCapabilitiesAndEscapedCallbacksCannotAuthorizeObservedWrites(){
        var caller=new AtomicReference<EffectSession>();var callerToken=new AtomicReference<EffectSession.WorldOperation>();
        var recipientRef=new AtomicReference<EffectSession>();var recipientToken=new AtomicReference<EffectSession.WorldOperation>();
        var recipient=session(request->{
            var current=recipientRef.get();var own=current.operation(request);recipientToken.set(own);
            assertThrows(IllegalStateException.class,()->current.prepareObservedOperationDomain(callerToken.get(),current.state(),consume(state(current)),List.of()));
            var outer=caller.get();assertThrows(IllegalStateException.class,()->outer.prepareObservedOperationDomain(own,outer.state(),consume(state(outer)),List.of()));
            return Empty.INSTANCE;
        });recipientRef.set(recipient);
        var session=session(request->{
            var current=caller.get();callerToken.set(current.operation(request));recipient.start(0,CALL);
            assertThrows(IllegalStateException.class,()->current.prepareObservedOperationDomain(recipientToken.get(),current.state(),consume(state(current)),List.of()));
            return Empty.INSTANCE;
        });caller.set(session);session.start(0,CALL);
        assertThrows(IllegalStateException.class,()->session.prepareObservedOperationDomain(callerToken.get(),session.state(),consume(state(session)),List.of()));
        assertThrows(IllegalStateException.class,()->recipient.prepareObservedOperationDomain(recipientToken.get(),recipient.state(),consume(state(recipient)),List.of()));
        assertEquals(1,state(session).buffs().instances().size());assertEquals(1,state(recipient).buffs().instances().size());
        assertDoesNotThrow(session::checkpoint);assertDoesNotThrow(recipient::checkpoint);
    }

    @Test void staleObservedPreparationCannotEraseLaterWritesAndKnownCompletionStillWorks(){
        var caller=new AtomicReference<EffectSession>();
        var session=session(request->{
            var current=caller.get();var operation=current.operation(request);var before=current.operationState(operation);
            var update=current.prepareObservedOperationDomain(operation,before,consume(before.engine().domain()),List.of(CONFIRMED));
            EffectSession.commitDomains(List.of(update));var observed=current.state();
            assertTrue(operation.committed().isEmpty());assertThrows(IllegalStateException.class,()->EffectSession.commitDomains(List.of(update)));
            assertThrows(IllegalStateException.class,()->current.prepareObservedOperationDomain(operation,before,before.engine().domain(),List.of()));
            assertSame(observed,current.state());
            EffectSession.commitDomains(List.of(current.prepareOperationDomain(operation,observed,observed.engine().domain(),Empty.INSTANCE)));
            assertEquals(Optional.of(Empty.INSTANCE),operation.committed());return Empty.INSTANCE;
        });caller.set(session);session.start(0,CALL);
        assertTrue(session.state().idle());assertTrue(state(session).buffs().instances().isEmpty());
        assertEquals(1,session.state().engine().receipts().size());assertDoesNotThrow(session::checkpoint);
    }

    @Test void observedPublicationCannotUndoOrExtendAnAlreadyConfirmedOperation(){
        var caller=new AtomicReference<EffectSession>();
        var session=session(request->{
            var current=caller.get();var operation=current.operation(request);var before=current.state();
            var observed=current.prepareObservedOperationDomain(operation,before,consume(state(current)),List.of(CONFIRMED));
            EffectSession.commitDomains(List.of(current.prepareOperationDomain(operation,before,state(current),Empty.INSTANCE)));
            var confirmed=current.state();
            assertThrows(IllegalStateException.class,()->current.prepareObservedOperationDomain(operation,confirmed,consume(state(current)),List.of(CONFIRMED)));
            assertThrows(IllegalStateException.class,()->EffectSession.commitDomains(List.of(observed)));
            assertSame(confirmed,current.state());assertEquals(Optional.of(Empty.INSTANCE),operation.committed());return Empty.INSTANCE;
        });caller.set(session);session.start(0,CALL);
        assertEquals(1,state(session).buffs().instances().size());assertTrue(session.state().idle());assertDoesNotThrow(session::checkpoint);
    }

    @Test void staleRecipientRejectsTheEntireObservedPublicationBeforeEitherDomainChanges(){
        var recipient=session(_->Empty.INSTANCE);var caller=new AtomicReference<EffectSession>();
        var session=session(request->{
            var current=caller.get();var operation=current.operation(request);var before=current.state();var remote=recipient.state();
            var localUpdate=current.prepareObservedOperationDomain(operation,before,consume(state(current)),List.of(CONFIRMED));
            var remoteUpdate=recipient.prepareInboxDomain(remote,consume(state(recipient)),List.of(CONFIRMED));
            recipient.observe(0,List.of());var changed=recipient.state();
            assertThrows(IllegalStateException.class,()->EffectSession.commitDomains(List.of(localUpdate,remoteUpdate)));
            assertSame(before,current.state());assertSame(changed,recipient.state());assertTrue(operation.committed().isEmpty());
            assertTrue(facts(current).isEmpty());assertTrue(facts(recipient).isEmpty());return Empty.INSTANCE;
        });caller.set(session);session.start(0,CALL);
        assertEquals(1,state(session).buffs().instances().size());assertEquals(1,state(recipient).buffs().instances().size());
        assertDoesNotThrow(session::checkpoint);assertDoesNotThrow(recipient::checkpoint);
    }

    @Test void observedWritesCannotAdvanceTheCallerClock(){
        var caller=new AtomicReference<EffectSession>();
        var session=session(request->{
            var current=caller.get();var operation=current.operation(request);var before=current.state();var state=state(current);
            var later=state.withBuffs(new BuffStore(1,state.buffs().nextGeneration(),state.buffs().instances()));
            assertThrows(IllegalArgumentException.class,()->current.prepareObservedOperationDomain(operation,before,later,List.of()));
            assertSame(before,current.state());assertTrue(operation.committed().isEmpty());return Empty.INSTANCE;
        });caller.set(session);session.start(0,CALL);assertDoesNotThrow(session::checkpoint);
    }
}
