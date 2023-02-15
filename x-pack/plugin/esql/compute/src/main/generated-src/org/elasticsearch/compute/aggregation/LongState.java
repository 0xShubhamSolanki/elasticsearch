/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.elasticsearch.compute.ann.Experimental;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Aggregator state for a single long.
 * This class is generated. Do not edit it.
 */
@Experimental
final class LongState implements AggregatorState<LongState> {
    private long value;

    LongState() {
        this(0);
    }

    LongState(long init) {
        this.value = init;
    }

    long longValue() {
        return value;
    }

    void longValue(long value) {
        this.value = value;
    }

    @Override
    public long getEstimatedSize() {
        return Long.BYTES;
    }

    @Override
    public void close() {}

    @Override
    public AggregatorStateSerializer<LongState> serializer() {
        return new LongStateSerializer();
    }

    private static class LongStateSerializer implements AggregatorStateSerializer<LongState> {
        private static final VarHandle handle = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

        @Override
        public int size() {
            return Long.BYTES;
        }

        @Override
        public int serialize(LongState state, byte[] ba, int offset) {
            handle.set(ba, offset, state.value);
            return Long.BYTES; // number of bytes written
        }

        @Override
        public void deserialize(LongState state, byte[] ba, int offset) {
            Objects.requireNonNull(state);
            state.value = (long) handle.get(ba, offset);
        }
    }
}
