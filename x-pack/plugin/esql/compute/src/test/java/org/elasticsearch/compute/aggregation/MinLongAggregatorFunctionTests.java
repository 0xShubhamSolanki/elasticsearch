/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.operator.SequenceLongBlockSourceOperator;
import org.elasticsearch.compute.operator.SourceOperator;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.hamcrest.Matchers.equalTo;

public class MinLongAggregatorFunctionTests extends AggregatorFunctionTestCase {
    @Override
    protected SourceOperator simpleInput(int size) {
        long max = randomLongBetween(1, Long.MAX_VALUE / size);
        return new SequenceLongBlockSourceOperator(LongStream.range(0, size).map(l -> randomLongBetween(-max, max)));
    }

    @Override
    protected AggregatorFunction.Factory aggregatorFunction() {
        return AggregatorFunction.MIN_LONGS;
    }

    @Override
    protected String expectedDescriptionOfAggregator() {
        return "min of longs";
    }

    @Override
    protected void assertSimpleOutput(List<Block> input, Block result) {
        long min = input.stream()
            .flatMapToLong(
                b -> IntStream.range(0, b.getTotalValueCount()).filter(p -> false == b.isNull(p)).mapToLong(p -> ((LongBlock) b).getLong(p))
            )
            .min()
            .getAsLong();
        assertThat(((LongBlock) result).getLong(0), equalTo(min));
    }
}
