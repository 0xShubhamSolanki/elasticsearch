/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.operator.SequenceDoubleBlockSourceOperator;
import org.elasticsearch.compute.operator.SourceOperator;
import org.elasticsearch.test.ESTestCase;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.hamcrest.Matchers.closeTo;

public class AvgDoubleAggregatorTests extends AggregatorTestCase {
    @Override
    protected SourceOperator simpleInput(int size) {
        return new SequenceDoubleBlockSourceOperator(LongStream.range(0, size).mapToDouble(l -> ESTestCase.randomDouble()));
    }

    @Override
    protected AggregatorFunction.Factory aggregatorFunction() {
        return AggregatorFunction.AVG_DOUBLES;
    }

    @Override
    protected String expectedDescriptionOfAggregator() {
        return "avg of doubles";
    }

    @Override
    protected void assertSimpleOutput(List<Block> input, Block result) {
        double avg = input.stream()
            .flatMapToDouble(
                b -> IntStream.range(0, b.getTotalValueCount())
                    .filter(p -> false == b.isNull(p))
                    .mapToDouble(p -> ((DoubleBlock) b).getDouble(p))
            )
            .average()
            .getAsDouble();
        assertThat(((DoubleBlock) result).getDouble(0), closeTo(avg, .0001));
    }
}
