package com.imdomestic.chorus.rule;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TimelineClockProposalTest {
    record Domain(long time, int segments) {}
    static final class Clock implements TimelineEngine.Clock<Domain> {
        final long step; final AtomicInteger calls=new AtomicInteger();
        Clock(long step) {this.step=step;}
        public long time(Domain state){return state.time();}
        public long nextDeadline(Domain state){return state.time()+step;}
        public Local<Domain> advance(Domain state,long until){calls.incrementAndGet();return new Local<>(new Domain(until,state.segments()+1),Empty.INSTANCE,List.of(new Signal("elapsed",Empty.INSTANCE)));}
    }
    static TimelineEngine<Domain> engine(Clock clock) {return new TimelineEngine<>("clock-test",List.of(),(_,_) -> List.of(),clock,1);}
    static Start request(long time){return new Start(time,new Signal("requested",Empty.INSTANCE));}
    @Test void preparedClockBoundarySurvivesInterpreterYieldsWithoutReevaluatingTheClock() {
        var fallback=new Clock(1000);var engine=engine(fallback);var state=engine.initial(new Domain(0,0));var clock=new Clock(10);var input=request(25);
        var proposal=engine.prepareAdvance(state,input,clock);assertEquals(1,clock.calls.get());assertEquals(0,state.engine().domain().time());
        var next=engine.transition(state,input,proposal);assertThrows(IllegalStateException.class,()->engine.checkpoint(next.state()));
        var cursor=next;
        while(!cursor.state().engine().idle())cursor=engine.transition(cursor.state(),Pump.INSTANCE);
        assertEquals(10,cursor.state().engine().domain().time());assertEquals(1,clock.calls.get());assertEquals(0,fallback.calls.get());
        assertTrue(engine.needsClock(cursor.state(),Pump.INSTANCE));
        cursor=engine.transition(cursor.state(),Pump.INSTANCE,engine.prepareAdvance(cursor.state(),Pump.INSTANCE,clock));
        while(!cursor.state().engine().idle())cursor=engine.transition(cursor.state(),Pump.INSTANCE);
        cursor=engine.transition(cursor.state(),Pump.INSTANCE,engine.prepareAdvance(cursor.state(),Pump.INSTANCE,clock));
        while(cursor.needsPump())cursor=engine.transition(cursor.state(),Pump.INSTANCE);
        assertTrue(cursor.state().idle());assertEquals(new Domain(25,3),cursor.state().engine().domain());assertEquals(3,clock.calls.get());
    }
    @Test void aProposalCannotBeAppliedToAnotherInputOrEqualButDifferentTimeline() {
        var engine=engine(new Clock(1000));var state=engine.initial(new Domain(0,0));var input=request(25);var proposal=engine.prepareAdvance(state,input,new Clock(10));
        assertThrows(IllegalArgumentException.class,()->engine.transition(state,request(20),proposal));
        assertThrows(IllegalArgumentException.class,()->engine(new Clock(1000)).transition(state,input,proposal));
        var equal=new TimelineEngine.State<>(state.engine(),state.requested(),state.receipts(),state.abandoned());
        assertEquals(state,equal);assertThrows(IllegalArgumentException.class,()->engine.transition(equal,input,proposal));
        var used=engine.transition(state,input,proposal);assertThrows(IllegalArgumentException.class,()->engine.transition(used.state(),input,proposal));
    }
    @Test void invalidCapturedClocksAndSameTimeInputsCannotProduceAdvanceProposals() {
        var engine=engine(new Clock(1000));var state=engine.initial(new Domain(0,0));
        assertFalse(engine.needsClock(state,request(0)));assertThrows(IllegalStateException.class,()->engine.prepareAdvance(state,request(0),new Clock(10)));
        assertThrows(IllegalStateException.class,()->engine.prepareAdvance(state,request(10),new Clock(0)));
        var bad=new TimelineEngine.Clock<Domain>() {
            public long time(Domain d){return d.time();}
            public long nextDeadline(Domain d){return 5;}
            public Local<Domain> advance(Domain d,long until){return new Local<>(d,Empty.INSTANCE,List.of());}
        };
        assertThrows(IllegalArgumentException.class,()->engine.prepareAdvance(state,request(10),bad));
        assertThrows(IllegalArgumentException.class,()->engine.prepareAdvance(state,request(Long.MAX_VALUE),new Clock(10)));
    }
}
