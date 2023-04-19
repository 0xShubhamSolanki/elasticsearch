/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.compute.aggregation;

import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.AggregatorStateVector;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;

/**
 * {@link GroupingAggregatorFunction} implementation for {@link CountDistinctBytesRefAggregator}.
 */
public final class CountDistinctBytesRefGroupingAggregatorFunction implements GroupingAggregatorFunction {
    private final HllStates.GroupingState state;

    private final int channel;

    public CountDistinctBytesRefGroupingAggregatorFunction(int channel, HllStates.GroupingState state) {
        this.channel = channel;
        this.state = state;
    }

    public static CountDistinctBytesRefGroupingAggregatorFunction create(BigArrays bigArrays, int channel) {
        return new CountDistinctBytesRefGroupingAggregatorFunction(channel, CountDistinctBytesRefAggregator.initGrouping(bigArrays));
    }

    @Override
    public void addRawInput(LongVector groups, Page page) {
        BytesRefBlock valuesBlock = page.getBlock(channel);
        BytesRefVector valuesVector = valuesBlock.asVector();
        if (valuesVector != null) {
            var scratch = new org.apache.lucene.util.BytesRef();
            int positions = groups.getPositionCount();
            for (int position = 0; position < groups.getPositionCount(); position++) {
                int groupId = Math.toIntExact(groups.getLong(position));
                CountDistinctBytesRefAggregator.combine(state, groupId, valuesVector.getBytesRef(position, scratch));
            }
        } else {
            // move the cold branch out of this method to keep the optimized case vector/vector as small as possible
            addRawInputWithBlockValues(groups, valuesBlock);
        }
    }

    private void addRawInputWithBlockValues(LongVector groups, BytesRefBlock valuesBlock) {
        var scratch = new org.apache.lucene.util.BytesRef();
        int positions = groups.getPositionCount();
        for (int position = 0; position < groups.getPositionCount(); position++) {
            int groupId = Math.toIntExact(groups.getLong(position));
            if (valuesBlock.isNull(position)) {
                state.putNull(groupId);
            } else {
                int i = valuesBlock.getFirstValueIndex(position);
                CountDistinctBytesRefAggregator.combine(state, groupId, valuesBlock.getBytesRef(i, scratch));
            }
        }
    }

    @Override
    public void addRawInput(LongBlock groups, Page page) {
        assert channel >= 0;
        BytesRefBlock valuesBlock = page.getBlock(channel);
        BytesRefVector valuesVector = valuesBlock.asVector();
        int positions = groups.getPositionCount();
        var scratch = new org.apache.lucene.util.BytesRef();
        if (valuesVector != null) {
            for (int position = 0; position < groups.getPositionCount(); position++) {
                if (groups.isNull(position) == false) {
                    int groupId = Math.toIntExact(groups.getLong(position));
                    CountDistinctBytesRefAggregator.combine(state, groupId, valuesVector.getBytesRef(position, scratch));
                }
            }
        } else {
            for (int position = 0; position < groups.getPositionCount(); position++) {
                if (groups.isNull(position)) {
                    continue;
                }
                int groupId = Math.toIntExact(groups.getLong(position));
                if (valuesBlock.isNull(position)) {
                    state.putNull(groupId);
                } else {
                    int i = valuesBlock.getFirstValueIndex(position);
                    CountDistinctBytesRefAggregator.combine(state, groupId, valuesBlock.getBytesRef(position, scratch));
                }
            }
        }
    }

    @Override
    public void addIntermediateInput(LongVector groupIdVector, Block block) {
        assert channel == -1;
        Vector vector = block.asVector();
        if (vector == null || vector instanceof AggregatorStateVector == false) {
            throw new RuntimeException("expected AggregatorStateBlock, got:" + block);
        }
        @SuppressWarnings("unchecked")
        AggregatorStateVector<HllStates.GroupingState> blobVector = (AggregatorStateVector<HllStates.GroupingState>) vector;
        // TODO exchange big arrays directly without funny serialization - no more copying
        BigArrays bigArrays = BigArrays.NON_RECYCLING_INSTANCE;
        HllStates.GroupingState inState = CountDistinctBytesRefAggregator.initGrouping(bigArrays);
        blobVector.get(0, inState);
        for (int position = 0; position < groupIdVector.getPositionCount(); position++) {
            int groupId = Math.toIntExact(groupIdVector.getLong(position));
            CountDistinctBytesRefAggregator.combineStates(state, groupId, inState, position);
        }
    }

    @Override
    public void addIntermediateRowInput(int groupId, GroupingAggregatorFunction input, int position) {
        if (input.getClass() != getClass()) {
            throw new IllegalArgumentException("expected " + getClass() + "; got " + input.getClass());
        }
        HllStates.GroupingState inState = ((CountDistinctBytesRefGroupingAggregatorFunction) input).state;
        CountDistinctBytesRefAggregator.combineStates(state, groupId, inState, position);
    }

    @Override
    public Block evaluateIntermediate(IntVector selected) {
        AggregatorStateVector.Builder<AggregatorStateVector<HllStates.GroupingState>, HllStates.GroupingState> builder =
            AggregatorStateVector.builderOfAggregatorState(HllStates.GroupingState.class, state.getEstimatedSize());
        builder.add(state, selected);
        return builder.build().asBlock();
    }

    @Override
    public Block evaluateFinal(IntVector selected) {
        return CountDistinctBytesRefAggregator.evaluateFinal(state, selected);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName()).append("[");
        sb.append("channel=").append(channel);
        sb.append("]");
        return sb.toString();
    }

    @Override
    public void close() {
        state.close();
    }
}
