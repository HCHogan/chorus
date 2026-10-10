package com.imdomestic.chorus.effect.attribute;

import com.imdomestic.chorus.stat.*;
import java.util.Objects;

/** A reduced Chorus contribution, not an already-computed final native attribute value. */
public record NativeAttributeBinding(String id, String attribute, Operation operation, String profile, Measure input, Unit outputUnit) {
    public enum Operation { ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL }
    public NativeAttributeBinding {
        for(String value:new String[]{id,attribute,profile})if(value==null||!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid native attribute binding identifier");
        Objects.requireNonNull(operation);Objects.requireNonNull(input);Objects.requireNonNull(outputUnit);
        if(operation!=Operation.ADD_VALUE&&!outputUnit.equals(Unit.DELTA))throw new IllegalArgumentException("Native multiplier contribution must output delta");
    }
    public record Calculated(NativeAttributeBinding binding,CalculationProfile.Result calculation) {
        public Calculated {
            Objects.requireNonNull(binding);Objects.requireNonNull(calculation);
            if(!calculation.output().unit().equals(binding.outputUnit()))throw new IllegalArgumentException("Native attribute output unit mismatch");
        }
        public double amount(){return calculation.output().value();}
    }
}
