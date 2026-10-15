package com.imdomestic.chorus.effect.weapon;

/** Translation of accepted phase coordinates; original acceptance times and receipt identities stay unchanged. */
public final class WeaponPhaseTime {
    private WeaponPhaseTime() {}
    public static long local(long acceptedTime,long offset) {
        long result=Math.addExact(acceptedTime,offset);
        if(result==Long.MAX_VALUE)throw new IllegalArgumentException("Finite weapon phase cannot use the forever sentinel");
        return result;
    }
    public static long relocate(long offset,long sourceNow,long targetNow) {
        if(sourceNow<0||targetNow<0||sourceNow==Long.MAX_VALUE||targetNow==Long.MAX_VALUE)
            throw new IllegalArgumentException("Weapon phase relocation needs finite domain clocks");
        return Math.addExact(offset,Math.subtractExact(targetNow,sourceNow));
    }
    public static long elapsed(long now,long started) {
        if(now<0||now<started)throw new IllegalArgumentException("Weapon phase observation precedes acceptance");
        return Math.subtractExact(now,started);
    }
}
