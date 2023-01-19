/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.aggregation.AggregatorMode;
import org.elasticsearch.compute.aggregation.AvgLongGroupingAggregatorTests;
import org.elasticsearch.compute.aggregation.BlockHash;
import org.elasticsearch.compute.aggregation.GroupingAggregator;
import org.elasticsearch.compute.aggregation.GroupingAggregatorFunction;
import org.elasticsearch.compute.aggregation.MaxLongGroupingAggregatorTests;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.core.Tuple;

import java.util.List;
import java.util.stream.LongStream;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

public class HashAggregationOperatorTests extends ForkingOperatorTestCase {
    @Override
    protected SourceOperator simpleInput(int size) {
        long max = randomLongBetween(1, Long.MAX_VALUE / size);
        return new TupleBlockSourceOperator(LongStream.range(0, size).mapToObj(l -> Tuple.tuple(l % 5, randomLongBetween(-max, max))));
    }

    @Override
    protected Operator.OperatorFactory simpleWithMode(BigArrays bigArrays, AggregatorMode mode) {
        return new HashAggregationOperator.HashAggregationOperatorFactory(
            0,
            List.of(
                new GroupingAggregator.GroupingAggregatorFactory(bigArrays, GroupingAggregatorFunction.AVG_LONGS, mode, 1),
                new GroupingAggregator.GroupingAggregatorFactory(
                    bigArrays,
                    GroupingAggregatorFunction.MAX_LONGS,
                    mode,
                    mode.isInputPartial() ? 2 : 1
                )
            ),
            () -> BlockHash.newLongHash(bigArrays)
        );
    }

    @Override
    protected String expectedDescriptionOfSimple() {
        return "HashAggregationOperator(mode = <not-needed>, aggs = avg of longs, max of longs)";
    }

    @Override
    protected void assertSimpleOutput(List<Page> input, List<Page> results) {
        assertThat(results, hasSize(1));
        assertThat(results.get(0).getBlockCount(), equalTo(3));
        assertThat(results.get(0).getPositionCount(), equalTo(5));

        AvgLongGroupingAggregatorTests avg = new AvgLongGroupingAggregatorTests();
        MaxLongGroupingAggregatorTests max = new MaxLongGroupingAggregatorTests();

        LongBlock groups = results.get(0).getBlock(0);
        Block avgs = results.get(0).getBlock(1);
        Block maxs = results.get(0).getBlock(2);
        for (int i = 0; i < 5; i++) {
            long group = groups.getLong(i);
            avg.assertSimpleGroup(input, avgs, i, group);
            max.assertSimpleGroup(input, maxs, i, group);
        }
    }

    @Override
    protected ByteSizeValue smallEnoughToCircuitBreak() {
        return ByteSizeValue.ofBytes(between(1, 32));
    }
}
