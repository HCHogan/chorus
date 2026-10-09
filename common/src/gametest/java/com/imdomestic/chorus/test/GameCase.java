package com.imdomestic.chorus.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Loader-neutral metadata for the shared server scenarios. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameCase {
    String environment() default "chorus_gametest:default";
    String dimension() default "minecraft:overworld";
    int maxTicks() default 20;
}
