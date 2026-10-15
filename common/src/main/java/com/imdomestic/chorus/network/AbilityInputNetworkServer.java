package com.imdomestic.chorus.network;

import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.effect.ability.AbilityInput;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.platform.minecraft.MinecraftEntityLife;
import com.imdomestic.chorus.platform.Services;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** One monotonic edge stream per connection. Consumed releases cannot replay an uncertain cast. */
public final class AbilityInputNetworkServer {
    public static final int HEARTBEAT_TICKS=10, LEASE_TICKS=40;
    public static final AbilityInputNetworkServer LIVE = new AbilityInputNetworkServer();
    public enum Reply { PROCESSED, DUPLICATE, REJECTED, FAILED }
    public record Result(Reply reply, Optional<AbilityInput.Receipt> input) {}
    private static final class Session {
        final ServerGamePacketListenerImpl connection;ServerPlayer player;ServerLevel level;MinecraftEffectRuntime runtime;
        String life;long sequence,revision;Object transferring;final Map<String,Long> held=new TreeMap<>();final Map<String,Long> epochs=new TreeMap<>();final Map<String,Long> refreshed=new TreeMap<>();
        Session(ServerPlayer player){connection=player.connection;owner(player);}
        void clear(){held.clear();epochs.clear();refreshed.clear();}
        void remove(String slot){held.remove(slot);epochs.remove(slot);refreshed.remove(slot);}
        void owner(ServerPlayer next){player=next;level=next.level();life=MinecraftEntityLife.get(next).id();runtime=null;clear();}
    }
    private final Map<ServerGamePacketListenerImpl,Session> sessions=new IdentityHashMap<>();
    private final BiConsumer<ServerPlayer,InputTransferPayload> sendTransfer;
    public AbilityInputNetworkServer(){this((player,payload)->Services.PLATFORM.sendInputTransfer(player,payload));}
    public AbilityInputNetworkServer(BiConsumer<ServerPlayer,InputTransferPayload> sendTransfer){this.sendTransfer=Objects.requireNonNull(sendTransfer);}
    public Result request(ServerPlayer player, AbilityInputPayload input) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Ability input belongs to server thread");
        var s=sessions.computeIfAbsent(player.connection,_ ->new Session(player));
        if(s.transferring!=null)return new Result(Reply.REJECTED,Optional.empty());
        if (input.sequence() <= s.sequence) return new Result(Reply.DUPLICATE, Optional.empty());
        s.sequence=input.sequence();
        if(s.player!=player||s.level!=player.level()||!s.life.equals(MinecraftEntityLife.get(player).id()))s.owner(player);
        if (player.connection.player!=player || player.isRemoved() || !player.isAlive() || player.isSpectator() || !player.level().dimension().identifier().toString().equals(input.dimension())) return new Result(Reply.REJECTED, Optional.empty());
        var runtime = MinecraftEffectRuntime.installed(player.level()).orElse(null);
        if(s.runtime!=runtime){s.clear();s.runtime=runtime;}
        if (runtime == null) return new Result(Reply.REJECTED, Optional.empty());
        try {
            settle(s); // A late heartbeat or terminal edge cannot renew or cast an expired hold.
            if(input.transferRevision()!=s.epochs.getOrDefault(input.slot(),0L))return new Result(Reply.REJECTED,Optional.empty());
            if(input.edge()==AbilityInputPayload.Edge.HOLD){
                if(!Objects.equals(s.held.get(input.slot()),input.gesture()))return new Result(Reply.REJECTED,Optional.empty());
                s.refreshed.put(input.slot(),s.level.getGameTime());return new Result(Reply.PROCESSED,Optional.empty());
            }
            if(input.edge()!=AbilityInputPayload.Edge.PRESS&&Objects.equals(s.held.get(input.slot()),input.gesture()))s.remove(input.slot());
            var receipt = runtime.abilityInput(player, input.slot(), AbilityInput.Edge.valueOf(input.edge().name()), input.gesture());
            if(receipt.outcome()==AbilityInput.Outcome.PRESSED){s.held.put(input.slot(),input.gesture());s.epochs.put(input.slot(),0L);s.refreshed.put(input.slot(),s.level.getGameTime());}
            if(runtime.failure().isPresent())s.clear();
            return new Result(runtime.failure().isPresent() ? Reply.FAILED : Reply.PROCESSED, Optional.of(receipt));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            if(runtime.failure().isPresent())s.clear();
            return new Result(runtime.failure().isPresent() ? Reply.FAILED : Reply.REJECTED, Optional.empty());
        } catch (RuntimeException failure) {
            s.clear();
            Constants.LOG.error("Ability input failed; consumed input will not replay", failure); return new Result(Reply.FAILED, Optional.empty());
        }
    }
    private static boolean eligible(Session s){return s.connection.player==s.player&&s.player.level()==s.level&&!s.player.isRemoved()&&s.player.isAlive()&&!s.player.isSpectator()&&s.life.equals(MinecraftEntityLife.get(s.player).id());}
    private static void settle(Session s){
        if(!eligible(s)||s.runtime==null||MinecraftEffectRuntime.installed(s.level).orElse(null)!=s.runtime||s.runtime.failure().isPresent()){s.clear();return;}
        for(var entry:Map.copyOf(s.held).entrySet()){
            String slot=entry.getKey();long gesture=entry.getValue();
            if(!s.runtime.hasAbilityInput(s.player,slot,gesture)){s.remove(slot);continue;}
            if(s.level.getGameTime()-s.refreshed.get(slot)>=LEASE_TICKS){
                s.remove(slot); // Retire transport authority before core cancellation can run callbacks.
                s.runtime.abilityInput(s.player,slot,AbilityInput.Edge.CANCEL,gesture);
            }
        }
    }
    /** Retire expired authority before a core transfer proposal is captured; this never renews a lease. */
    public void settleTransfer(ServerPlayer player){
        if(!player.level().getServer().isSameThread())throw new IllegalStateException("Ability input belongs to server thread");
        var s=sessions.get(player.connection);if(s==null)return;
        if(s.transferring!=null)throw new IllegalStateException("Ability transfer is already prepared");settle(s);
    }
    public void tick(ServerLevel level){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Ability input belongs to server thread");
        for(var s:List.copyOf(sessions.values()))if(s.level==level&&s.transferring==null){
            try{settle(s);}catch(RuntimeException failure){s.clear();Constants.LOG.error("Ability input lease failed; gesture retired without replay",failure);}
        }
    }
    public InputTransfer prepareTransfer(ServerPlayer player,MinecraftEffectRuntime source,MinecraftEffectRuntime target) {
        if(!player.level().getServer().isSameThread())throw new IllegalStateException("Ability input belongs to server thread");
        var s=sessions.get(player.connection);if(s==null||s.held.isEmpty())return InputTransfer.NONE;
        if(s.transferring!=null)throw new IllegalStateException("Ability transfer is already prepared");
        // A respawn can replace the connection's player before another ability packet arrives.
        // Retire only ephemeral metadata; an old body's gesture never authorizes its replacement.
        if(s.connection.player!=s.player||s.player.level()!=s.level||s.player.isRemoved()||!s.player.isAlive()||s.player.isSpectator()
                ||!s.life.equals(MinecraftEntityLife.get(s.player).id())||s.runtime==null||MinecraftEffectRuntime.installed(s.level).orElse(null)!=s.runtime){
            s.clear();return InputTransfer.NONE;
        }
        s.held.entrySet().removeIf(entry->!s.runtime.hasAbilityInput(s.player,entry.getKey(),entry.getValue()));
        s.epochs.keySet().retainAll(s.held.keySet());s.refreshed.keySet().retainAll(s.held.keySet());if(s.held.isEmpty())return InputTransfer.NONE;
        if(s.player!=player||s.connection.player!=player||s.runtime!=source||s.level!=source.level()
                ||player.level()!=s.level||!player.isAlive()||player.isRemoved()||player.isSpectator()||!s.life.equals(MinecraftEntityLife.get(player).id())
                ||source==target||target.level().getServer()!=player.level().getServer()
                ||MinecraftEffectRuntime.installed(target.level()).orElse(null)!=target)throw new IllegalStateException("Ability gesture cannot transfer");
        var held=new TreeMap<>(s.held);
        var refreshed=new TreeMap<String,Long>();
        held.keySet().forEach(slot->{long elapsed=Math.subtractExact(s.level.getGameTime(),s.refreshed.get(slot));
            if(elapsed<0||elapsed>=LEASE_TICKS)throw new IllegalStateException("Ability lease must settle before transfer");
            refreshed.put(slot,Math.subtractExact(target.level().getGameTime(),elapsed));});
        var previousRevisions=Map.copyOf(s.epochs);long revision=Math.incrementExact(s.revision);String from=s.level.dimension().identifier().toString();var marker=new Object();s.transferring=marker;
        return new InputTransfer(){boolean committed,finished,aborted;
            private void current(){if(finished||s.transferring!=marker||sessions.get(s.connection)!=s)throw new IllegalStateException("Stale ability transfer");}
            public void commit(){current();if(committed||s.connection.player!=player||player.level()!=target.level()||!s.life.equals(MinecraftEntityLife.get(player).id()))throw new IllegalStateException("Ability transfer lost native owner");s.level=target.level();s.runtime=target;s.revision=revision;s.clear();s.held.putAll(held);s.refreshed.putAll(refreshed);held.keySet().forEach(slot->s.epochs.put(slot,revision));committed=true;}
            public void publish(){current();if(!committed)throw new IllegalStateException("Ability transfer not committed");s.transferring=null;finished=true;
                held.forEach((slot,gesture)->sendTransfer.accept(player,new InputTransferPayload(InputTransferPayload.Kind.ABILITY,slot,player.getUUID(),player.getId(),from,s.level.dimension().identifier().toString(),s.sequence,gesture,revision,previousRevisions.getOrDefault(slot,0L))));}
            public void fail(){if(aborted)return;if(finished&&s.transferring!=null&&s.transferring!=marker)return;s.clear();if(s.transferring==marker)s.transferring=null;finished=true;}
            public void abort(){current();if(committed)throw new IllegalStateException("Committed ability transfer cannot abort");s.transferring=null;finished=true;aborted=true;}
        };
    }
    public long transferRevision(ServerPlayer player,String slot){if(!player.level().getServer().isSameThread())throw new IllegalStateException("Ability input belongs to server thread");var s=sessions.get(player.connection);return s==null||s.player!=player?0:s.epochs.getOrDefault(slot,0L);}
    public void disconnected(ServerGamePacketListenerImpl connection) { var s=sessions.remove(connection);if(s==null||s.transferring==null)MinecraftEffectRuntime.cancelAbilityInputs(connection);else s.clear(); }
    public void stop(MinecraftServer server) { sessions.keySet().removeIf(c -> c.player.level().getServer() == server); }
}
