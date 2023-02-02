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

import java.util.function.BiFunction;

import static org.elasticsearch.compute.aggregation.AggregationName.avg;
import static org.elasticsearch.compute.aggregation.AggregationName.count;
import static org.elasticsearch.compute.aggregation.AggregationName.max;
import static org.elasticsearch.compute.aggregation.AggregationName.median;
import static org.elasticsearch.compute.aggregation.AggregationName.median_absolute_deviation;
import static org.elasticsearch.compute.aggregation.AggregationName.min;
import static org.elasticsearch.compute.aggregation.AggregationName.sum;
import static org.elasticsearch.compute.aggregation.AggregationType.agnostic;
import static org.elasticsearch.compute.aggregation.AggregationType.doubles;
import static org.elasticsearch.compute.aggregation.AggregationType.ints;
import static org.elasticsearch.compute.aggregation.AggregationType.longs;

@Experimental
public interface GroupingAggregatorFunction extends Releasable {

    void addRawInput(LongBlock groupIdBlock, Page page);

    void addRawInput(LongVector groupIdVector, Page page);

    void addIntermediateInput(LongVector groupIdVector, Block block);

    /**
     * Add the position-th row from the intermediate output of the given aggregator function to the groupId
     */
    void addIntermediateRowInput(int groupId, GroupingAggregatorFunction input, int position);

    Block evaluateIntermediate();

    Block evaluateFinal();

    record Factory(AggregationName name, AggregationType type, BiFunction<BigArrays, Integer, GroupingAggregatorFunction> create)
        implements
            Describable {
        public GroupingAggregatorFunction build(BigArrays bigArrays, AggregatorMode mode, int inputChannel) {
            if (mode.isInputPartial()) {
                return create.apply(bigArrays, -1);
            } else {
                return create.apply(bigArrays, inputChannel);
            }
        }

        @Override
        public String describe() {
            return type == agnostic ? name.name() : name + " of " + type;
        }
    }

    static Factory of(AggregationName name, AggregationType type) {
        return switch (type) {
            case agnostic -> switch (name) {
                    case count -> COUNT;
                    default -> throw new IllegalArgumentException("unknown " + name + ", type:" + type);
                };
            case ints -> switch (name) {
                    case avg -> AVG_INTS;
                    case count -> COUNT;
                    case max -> MAX_INTS;
                    case median -> MEDIAN_INTS;
                    case median_absolute_deviation -> MEDIAN_ABSOLUTE_DEVIATION_INTS;
                    case min -> MIN_INTS;
                    case sum -> SUM_INTS;
                };
            case longs -> switch (name) {
                    case avg -> AVG_LONGS;
                    case count -> COUNT;
                    case max -> MAX_LONGS;
                    case median -> MEDIAN_LONGS;
                    case median_absolute_deviation -> MEDIAN_ABSOLUTE_DEVIATION_LONGS;
                    case min -> MIN_LONGS;
                    case sum -> SUM_LONGS;
                };
            case doubles -> switch (name) {
                    case avg -> AVG_DOUBLES;
                    case count -> COUNT;
                    case max -> MAX_DOUBLES;
                    case median -> MEDIAN_DOUBLES;
                    case median_absolute_deviation -> MEDIAN_ABSOLUTE_DEVIATION_DOUBLES;
                    case min -> MIN_DOUBLES;
                    case sum -> SUM_DOUBLES;
                };
        };
    }

    Factory AVG_DOUBLES = new Factory(avg, doubles, AvgDoubleGroupingAggregatorFunction::create);
    Factory AVG_LONGS = new Factory(avg, longs, AvgLongGroupingAggregatorFunction::create);
    Factory AVG_INTS = new Factory(avg, ints, AvgIntGroupingAggregatorFunction::create);

    Factory COUNT = new Factory(count, agnostic, CountGroupingAggregatorFunction::create);

    Factory MIN_DOUBLES = new Factory(min, doubles, MinDoubleGroupingAggregatorFunction::create);
    Factory MIN_LONGS = new Factory(min, longs, MinLongGroupingAggregatorFunction::create);
    Factory MIN_INTS = new Factory(min, ints, MinIntGroupingAggregatorFunction::create);

    Factory MAX_DOUBLES = new Factory(max, doubles, MaxDoubleGroupingAggregatorFunction::create);
    Factory MAX_LONGS = new Factory(max, longs, MaxLongGroupingAggregatorFunction::create);
    Factory MAX_INTS = new Factory(max, ints, MaxIntGroupingAggregatorFunction::create);

    Factory MEDIAN_DOUBLES = new Factory(median, doubles, MedianDoubleGroupingAggregatorFunction::create);
    Factory MEDIAN_LONGS = new Factory(median, longs, MedianLongGroupingAggregatorFunction::create);
    Factory MEDIAN_INTS = new Factory(median, ints, MedianIntGroupingAggregatorFunction::create);

    Factory MEDIAN_ABSOLUTE_DEVIATION_DOUBLES = new Factory(
        median_absolute_deviation,
        doubles,
        MedianAbsoluteDeviationDoubleGroupingAggregatorFunction::create
    );
    Factory MEDIAN_ABSOLUTE_DEVIATION_LONGS = new Factory(
        median_absolute_deviation,
        longs,
        MedianAbsoluteDeviationLongGroupingAggregatorFunction::create
    );
    Factory MEDIAN_ABSOLUTE_DEVIATION_INTS = new Factory(
        median_absolute_deviation,
        ints,
        MedianAbsoluteDeviationIntGroupingAggregatorFunction::create
    );

    Factory SUM_DOUBLES = new Factory(sum, doubles, SumDoubleGroupingAggregatorFunction::create);
    Factory SUM_LONGS = new Factory(sum, longs, SumLongGroupingAggregatorFunction::create);
    Factory SUM_INTS = new Factory(sum, ints, SumIntGroupingAggregatorFunction::create);
}
