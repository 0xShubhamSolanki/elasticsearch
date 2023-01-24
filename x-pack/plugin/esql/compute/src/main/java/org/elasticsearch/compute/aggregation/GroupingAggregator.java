/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.Describable;
import org.elasticsearch.compute.ann.Experimental;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.core.Releasable;

import java.util.function.Supplier;

@Experimental
public class GroupingAggregator implements Releasable {
    private final GroupingAggregatorFunction aggregatorFunction;

    private final AggregatorMode mode;

    private final int intermediateChannel;

    public record GroupingAggregatorFactory(
        BigArrays bigArrays,
        AggregationName aggName,
        AggregationType aggType,
        AggregatorMode mode,
        int inputChannel
    ) implements Supplier<GroupingAggregator>, Describable {

        public GroupingAggregatorFactory(
            BigArrays bigArrays,
            GroupingAggregatorFunction.Factory aggFunctionFactory,
            AggregatorMode mode,
            int inputChannel
        ) {
            this(bigArrays, aggFunctionFactory.name(), aggFunctionFactory.type(), mode, inputChannel);
        }

        @Override
        public GroupingAggregator get() {
            return new GroupingAggregator(bigArrays, GroupingAggregatorFunction.of(aggName, aggType), mode, inputChannel);
        }

        @Override
        public String describe() {
            return GroupingAggregatorFunction.of(aggName, aggType).describe();
        }
    }

    public GroupingAggregator(
        BigArrays bigArrays,
        GroupingAggregatorFunction.Factory aggCreationFunc,
        AggregatorMode mode,
        int inputChannel
    ) {
        this.aggregatorFunction = aggCreationFunc.build(bigArrays, mode, inputChannel);
        this.mode = mode;
        this.intermediateChannel = mode.isInputPartial() ? inputChannel : -1;
    }

    public void processPage(LongBlock groupIdBlock, Page page) {
        final LongVector groupIdVector = groupIdBlock.asVector();
        if (mode.isInputPartial()) {
            if (groupIdVector == null) {
                throw new IllegalStateException("Intermediate group id must not have nulls");
            }
            aggregatorFunction.addIntermediateInput(groupIdVector, page.getBlock(intermediateChannel));
        } else {
            if (groupIdVector != null) {
                aggregatorFunction.addRawInput(groupIdVector, page);
            } else {
                aggregatorFunction.addRawInput(groupIdBlock, page);
            }
        }
    }

    /**
     * Add the position-th row from the intermediate output of the given aggregator to this aggregator at the groupId position
     */
    public void addIntermediateRow(int groupId, GroupingAggregator input, int position) {
        aggregatorFunction.addIntermediateRowInput(groupId, input.aggregatorFunction, position);
    }

    public Block evaluate() {
        if (mode.isOutputPartial()) {
            return aggregatorFunction.evaluateIntermediate();
        } else {
            return aggregatorFunction.evaluateFinal();
        }
    }

    @Override
    public void close() {
        aggregatorFunction.close();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.getClass().getSimpleName()).append("[");
        sb.append("aggregatorFunction=").append(aggregatorFunction).append(", ");
        sb.append("mode=").append(mode);
        sb.append("]");
        return sb.toString();
    }
}
