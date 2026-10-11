package com.imdomestic.chorus.platform.minecraft;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import java.util.UUID;

/** Native persistent identity for one life; ordinary entity UUID alone also identifies later player lives. */
public final class MinecraftEntityLife {
    public static final String SAVE_KEY = "chorus:entity_life";
    public interface Owner { MinecraftEntityLife chorus$entityLife(); }
    private UUID life = UUID.randomUUID();
    public String id() { return life.toString(); }
    public void save(ValueOutput output) { output.putString(SAVE_KEY, id()); }
    public void load(ValueInput input) { life = input.read(SAVE_KEY, com.mojang.serialization.Codec.PASSTHROUGH).map(value -> UUID.fromString(value.asString().getOrThrow())).orElseGet(UUID::randomUUID); }
    public void restoreFrom(MinecraftEntityLife old, boolean sameLife) { life = sameLife ? old.life : UUID.randomUUID(); }
    public static MinecraftEntityLife get(LivingEntity entity) { return ((Owner) entity).chorus$entityLife(); }
}
