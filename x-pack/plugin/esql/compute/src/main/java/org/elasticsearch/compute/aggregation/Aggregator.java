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
import org.elasticsearch.core.Releasable;

import java.util.function.Supplier;

@Experimental
public class Aggregator implements Releasable {

    public static final Object[] EMPTY_PARAMS = new Object[] {};
    private static final int UNUSED_CHANNEL = -1;

    private final AggregatorFunction aggregatorFunction;

    private final AggregatorMode mode;

    private final int intermediateChannel;

    public interface Factory extends Supplier<Aggregator>, Describable {}

    public Aggregator(AggregatorFunction aggregatorFunction, AggregatorMode mode, int inputChannel) {
        assert mode.isInputPartial() || inputChannel >= 0;
        // input channel is used both to signal the creation of the page (when the input is not partial)
        this.aggregatorFunction = aggregatorFunction;
        // and to indicate the page during the intermediate phase
        this.intermediateChannel = mode.isInputPartial() ? inputChannel : UNUSED_CHANNEL;
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

    @Override
    public void close() {
        aggregatorFunction.close();
    }
}
