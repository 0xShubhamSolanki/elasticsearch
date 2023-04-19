/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.operator.SequenceDoubleBlockSourceOperator;
import org.elasticsearch.compute.operator.SourceOperator;
import org.elasticsearch.test.ESTestCase;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.hamcrest.Matchers.closeTo;

public class CountDistinctDoubleAggregatorFunctionTests extends AggregatorFunctionTestCase {
    @Override
    protected SourceOperator simpleInput(int size) {
        return new SequenceDoubleBlockSourceOperator(LongStream.range(0, size).mapToDouble(l -> ESTestCase.randomDouble()));
    }

    @Override
    protected AggregatorFunction.Factory aggregatorFunction() {
        return AggregatorFunction.COUNT_DISTINCT_DOUBLES;
    }

    @Override
    protected String expectedDescriptionOfAggregator() {
        return "count_distinct of doubles";
    }

    @Override
    protected void assertSimpleOutput(List<Block> input, Block result) {
        long expected = input.stream()
            .flatMapToDouble(
                b -> IntStream.range(0, b.getTotalValueCount())
                    .filter(p -> false == b.isNull(p))
                    .mapToDouble(p -> ((DoubleBlock) b).getDouble(p))
            )
            .distinct()
            .count();

        long count = ((LongBlock) result).getLong(0);
        // HLL is an approximation algorithm and precision depends on the number of values computed and the precision_threshold param
        // https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-metrics-cardinality-aggregation.html
        // For a number of values close to 10k and precision_threshold=1000, precision should be less than 10%
        double precision = (double) count / (double) expected;
        assertThat(precision, closeTo(1.0, .1));
    }
}
