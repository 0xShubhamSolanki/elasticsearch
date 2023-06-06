// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.compute.aggregation;

import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.StringBuilder;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.AggregatorStateVector;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.BooleanVector;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;

/**
 * {@link GroupingAggregatorFunction} implementation for {@link CountDistinctBooleanAggregator}.
 * This class is generated. Do not edit it.
 */
public final class CountDistinctBooleanGroupingAggregatorFunction implements GroupingAggregatorFunction {
  private final CountDistinctBooleanAggregator.GroupingState state;

  private final int channel;

  private final Object[] parameters;

  public CountDistinctBooleanGroupingAggregatorFunction(int channel,
      CountDistinctBooleanAggregator.GroupingState state, Object[] parameters) {
    this.channel = channel;
    this.state = state;
    this.parameters = parameters;
  }

  public static CountDistinctBooleanGroupingAggregatorFunction create(BigArrays bigArrays,
      int channel, Object[] parameters) {
    return new CountDistinctBooleanGroupingAggregatorFunction(channel, CountDistinctBooleanAggregator.initGrouping(bigArrays), parameters);
  }

  @Override
  public void addRawInput(LongVector groups, Page page) {
    BooleanBlock valuesBlock = page.getBlock(channel);
    assert groups.getPositionCount() == page.getPositionCount();
    BooleanVector valuesVector = valuesBlock.asVector();
    if (valuesVector == null) {
      addRawInput(groups, valuesBlock);
    } else {
      addRawInput(groups, valuesVector);
    }
  }

  private void addRawInput(LongVector groups, BooleanBlock values) {
    for (int position = 0; position < groups.getPositionCount(); position++) {
      int groupId = Math.toIntExact(groups.getLong(position));
      if (values.isNull(position)) {
        state.putNull(groupId);
        continue;
      }
      int valuesStart = values.getFirstValueIndex(position);
      int valuesEnd = valuesStart + values.getValueCount(position);
      for (int v = valuesStart; v < valuesEnd; v++) {
        CountDistinctBooleanAggregator.combine(state, groupId, values.getBoolean(v));
      }
    }
  }

  private void addRawInput(LongVector groups, BooleanVector values) {
    for (int position = 0; position < groups.getPositionCount(); position++) {
      int groupId = Math.toIntExact(groups.getLong(position));
      CountDistinctBooleanAggregator.combine(state, groupId, values.getBoolean(position));
    }
  }

  @Override
  public void addRawInput(LongBlock groups, Page page) {
    BooleanBlock valuesBlock = page.getBlock(channel);
    assert groups.getPositionCount() == page.getPositionCount();
    BooleanVector valuesVector = valuesBlock.asVector();
    if (valuesVector == null) {
      addRawInput(groups, valuesBlock);
    } else {
      addRawInput(groups, valuesVector);
    }
  }

  private void addRawInput(LongBlock groups, BooleanBlock values) {
    for (int position = 0; position < groups.getPositionCount(); position++) {
      if (groups.isNull(position)) {
        continue;
      }
      int groupStart = groups.getFirstValueIndex(position);
      int groupEnd = groupStart + groups.getValueCount(position);
      for (int g = groupStart; g < groupEnd; g++) {
        int groupId = Math.toIntExact(groups.getLong(g));
        if (values.isNull(position)) {
          state.putNull(groupId);
          continue;
        }
        int valuesStart = values.getFirstValueIndex(position);
        int valuesEnd = valuesStart + values.getValueCount(position);
        for (int v = valuesStart; v < valuesEnd; v++) {
          CountDistinctBooleanAggregator.combine(state, groupId, values.getBoolean(v));
        }
      }
    }
  }

  private void addRawInput(LongBlock groups, BooleanVector values) {
    for (int position = 0; position < groups.getPositionCount(); position++) {
      if (groups.isNull(position)) {
        continue;
      }
      int groupStart = groups.getFirstValueIndex(position);
      int groupEnd = groupStart + groups.getValueCount(position);
      for (int g = groupStart; g < groupEnd; g++) {
        int groupId = Math.toIntExact(groups.getLong(g));
        CountDistinctBooleanAggregator.combine(state, groupId, values.getBoolean(position));
      }
    }
  }

  @Override
  public void addIntermediateInput(LongVector groupIdVector, Block block) {
    Vector vector = block.asVector();
    if (vector == null || vector instanceof AggregatorStateVector == false) {
      throw new RuntimeException("expected AggregatorStateBlock, got:" + block);
    }
    @SuppressWarnings("unchecked") AggregatorStateVector<CountDistinctBooleanAggregator.GroupingState> blobVector = (AggregatorStateVector<CountDistinctBooleanAggregator.GroupingState>) vector;
    // TODO exchange big arrays directly without funny serialization - no more copying
    BigArrays bigArrays = BigArrays.NON_RECYCLING_INSTANCE;
    CountDistinctBooleanAggregator.GroupingState inState = CountDistinctBooleanAggregator.initGrouping(bigArrays);
    blobVector.get(0, inState);
    for (int position = 0; position < groupIdVector.getPositionCount(); position++) {
      int groupId = Math.toIntExact(groupIdVector.getLong(position));
      CountDistinctBooleanAggregator.combineStates(state, groupId, inState, position);
    }
    inState.close();
  }

  @Override
  public void addIntermediateRowInput(int groupId, GroupingAggregatorFunction input, int position) {
    if (input.getClass() != getClass()) {
      throw new IllegalArgumentException("expected " + getClass() + "; got " + input.getClass());
    }
    CountDistinctBooleanAggregator.GroupingState inState = ((CountDistinctBooleanGroupingAggregatorFunction) input).state;
    CountDistinctBooleanAggregator.combineStates(state, groupId, inState, position);
  }

  @Override
  public Block evaluateIntermediate(IntVector selected) {
    AggregatorStateVector.Builder<AggregatorStateVector<CountDistinctBooleanAggregator.GroupingState>, CountDistinctBooleanAggregator.GroupingState> builder =
        AggregatorStateVector.builderOfAggregatorState(CountDistinctBooleanAggregator.GroupingState.class, state.getEstimatedSize());
    builder.add(state, selected);
    return builder.build().asBlock();
  }

  @Override
  public Block evaluateFinal(IntVector selected) {
    return CountDistinctBooleanAggregator.evaluateFinal(state, selected);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getClass().getSimpleName()).append("[");
    sb.append("channel=").append(channel);
    sb.append("]");
    return sb.toString();
  }

  @Override
  public void close() {
    state.close();
  }
}
