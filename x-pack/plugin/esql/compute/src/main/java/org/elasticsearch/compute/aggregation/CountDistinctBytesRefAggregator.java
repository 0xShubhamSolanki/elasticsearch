/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBlock;

public class CountDistinctBytesRefAggregator {
    public static AggregatorFunctionSupplier supplier(BigArrays bigArrays, int channel, int precision) {
        return new AggregatorFunctionSupplier() {
            @Override
            public AggregatorFunction aggregator() {
                return CountDistinctBytesRefAggregatorFunction.create(bigArrays, channel, new Object[] { precision });
            }

            @Override
            public GroupingAggregatorFunction groupingAggregator() {
                return CountDistinctBytesRefGroupingAggregatorFunction.create(bigArrays, channel, new Object[] { precision });
            }

            @Override
            public String describe() {
                return "count_distinct of bytes";
            }
        };
    }

    public static HllStates.SingleState initSingle(BigArrays bigArrays, Object[] parameters) {
        return new HllStates.SingleState(bigArrays, parameters);
    }

    public static void combine(HllStates.SingleState current, BytesRef v) {
        current.collect(v);
    }

    public static void combineStates(HllStates.SingleState current, HllStates.SingleState state) {
        current.merge(0, state.hll, 0);
    }

    public static Block evaluateFinal(HllStates.SingleState state) {
        long result = state.cardinality();
        return LongBlock.newConstantBlockWith(result, 1);
    }

    public static HllStates.GroupingState initGrouping(BigArrays bigArrays, Object[] parameters) {
        return new HllStates.GroupingState(bigArrays, parameters);
    }

    public static void combine(HllStates.GroupingState current, int groupId, BytesRef v) {
        current.collect(groupId, v);
    }

    public static void combineStates(
        HllStates.GroupingState current,
        int currentGroupId,
        HllStates.GroupingState state,
        int statePosition
    ) {
        current.merge(currentGroupId, state.hll, currentGroupId);
    }

    public static Block evaluateFinal(HllStates.GroupingState state, IntVector selected) {
        LongBlock.Builder builder = LongBlock.newBlockBuilder(selected.getPositionCount());
        for (int i = 0; i < selected.getPositionCount(); i++) {
            int group = selected.getInt(i);
            long count = state.cardinality(group);
            builder.appendLong(count);
        }
        return builder.build();
    }
}
