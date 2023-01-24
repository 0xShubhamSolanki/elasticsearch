/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.elasticsearch.compute.Describable;
import org.elasticsearch.compute.ann.Experimental;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.Page;

import java.util.function.Supplier;

@Experimental
public class Aggregator {
    private final AggregatorFunction aggregatorFunction;

    private final AggregatorMode mode;

    private final int intermediateChannel;

    public record AggregatorFactory(AggregationName aggName, AggregationType aggType, AggregatorMode mode, int inputChannel)
        implements
            Supplier<Aggregator>,
            Describable {

        public AggregatorFactory(AggregatorFunction.Factory aggFunctionFactory, AggregatorMode mode, int inputChannel) {
            this(aggFunctionFactory.name(), aggFunctionFactory.type(), mode, inputChannel);
        }

        @Override
        public Aggregator get() {
            return new Aggregator(AggregatorFunction.of(aggName, aggType), mode, inputChannel);
        }

        @Override
        public String describe() {
            return AggregatorFunction.of(aggName, aggType).describe();
        }
    }

    public Aggregator(AggregatorFunction.Factory factory, AggregatorMode mode, int inputChannel) {
        assert mode.isInputPartial() || inputChannel >= 0;
        // input channel is used both to signal the creation of the page (when the input is not partial)
        this.aggregatorFunction = factory.build(mode.isInputPartial() ? -1 : inputChannel);
        // and to indicate the page during the intermediate phase
        this.intermediateChannel = mode.isInputPartial() ? inputChannel : -1;
        this.mode = mode;
    }

    public void processPage(Page page) {
        if (mode.isInputPartial()) {
            aggregatorFunction.addIntermediateInput(page.getBlock(intermediateChannel));
        } else {
            aggregatorFunction.addRawInput(page);
        }
    }

    public Block evaluate() {
        if (mode.isOutputPartial()) {
            return aggregatorFunction.evaluateIntermediate();
        } else {
            return aggregatorFunction.evaluateFinal();
        }
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
