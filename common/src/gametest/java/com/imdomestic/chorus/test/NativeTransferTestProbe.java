package com.imdomestic.chorus.test;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/** One-shot failure injection, available only in the GameTest source set. */
public final class NativeTransferTestProbe {
    private NativeTransferTestProbe() {}
    private static final Map<ServerPlayer,Runnable> AFTER_REMOVAL=new IdentityHashMap<>();
    public interface Scope extends AutoCloseable { @Override void close(); }
    public static Scope afterRemoval(ServerPlayer player,Runnable action) {
        Objects.requireNonNull(action);
        if(AFTER_REMOVAL.putIfAbsent(player,action)!=null)throw new IllegalStateException("Native transfer probe already installed");
        return ()->AFTER_REMOVAL.remove(player,action);
    }
    public static void removed(ServerPlayer player) {
        var action=AFTER_REMOVAL.remove(player);if(action!=null)action.run();
    }
}
