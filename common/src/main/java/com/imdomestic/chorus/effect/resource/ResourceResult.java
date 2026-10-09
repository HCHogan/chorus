package com.imdomestic.chorus.effect.resource;

/** requested/scaled measure this grant; credited is capacity-clipped, overflow is discarded. */
public record ResourceResult(ResourceState before, ResourceState after,
        double requested, double scaled, double credited, double overflow)
        implements com.imdomestic.chorus.rule.RuleEngine.ActionResult {}
