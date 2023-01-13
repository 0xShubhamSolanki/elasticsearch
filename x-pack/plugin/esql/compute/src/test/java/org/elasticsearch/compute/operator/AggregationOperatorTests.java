/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.aggregation.Aggregator;
import org.elasticsearch.compute.aggregation.AggregatorFunction;
import org.elasticsearch.compute.aggregation.AggregatorMode;
import org.elasticsearch.compute.aggregation.AvgLongAggregatorTests;
import org.elasticsearch.compute.aggregation.MaxLongAggregatorTests;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.Page;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

public class AggregationOperatorTests extends ForkingOperatorTestCase {
    @Override
    protected Operator.OperatorFactory simpleWithMode(BigArrays bigArrays, AggregatorMode mode) {
        return new AggregationOperator.AggregationOperatorFactory(
            List.of(
                new Aggregator.AggregatorFactory(AggregatorFunction.AVG_LONGS, mode, 0),
                new Aggregator.AggregatorFactory(AggregatorFunction.MAX_LONGS, mode, mode.isInputPartial() ? 1 : 0)
            ),
            mode
        );
    }

    @Override
    protected String expectedDescriptionOfSimple() {
        return "AggregationOperator(mode = SINGLE, aggs = avg of longs, max of longs)";
    }

    @Override
    protected void assertSimpleOutput(int end, List<Page> results) {
        assertThat(results, hasSize(1));
        assertThat(results.get(0).getBlockCount(), equalTo(2));
        assertThat(results.get(0).getPositionCount(), equalTo(1));

        AvgLongAggregatorTests avg = new AvgLongAggregatorTests();
        MaxLongAggregatorTests max = new MaxLongAggregatorTests();

        Block avgs = results.get(0).getBlock(0);
        Block maxs = results.get(0).getBlock(1);
        avg.assertSimpleResult(end, avgs);
        max.assertSimpleResult(end, maxs);
    }

    @Override
    protected ByteSizeValue smallEnoughToCircuitBreak() {
        assumeTrue("doesn't use big array so never breaks", false);
        return null;
    }
}
