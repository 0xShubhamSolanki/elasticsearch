// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.compute.aggregation;

import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.lang.StringBuilder;
import java.util.List;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.AggregatorStateVector;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;

/**
 * {@link GroupingAggregatorFunction} implementation for {@link CountDistinctIntAggregator}.
 * This class is generated. Do not edit it.
 */
public final class CountDistinctIntGroupingAggregatorFunction implements GroupingAggregatorFunction {
  private static final List<IntermediateStateDesc> INTERMEDIATE_STATE_DESC = List.of(
      new IntermediateStateDesc("aggstate", ElementType.UNKNOWN)  );

  private final HllStates.GroupingState state;

  private final List<Integer> channels;

  private final BigArrays bigArrays;

  private final int precision;

  public CountDistinctIntGroupingAggregatorFunction(List<Integer> channels,
      HllStates.GroupingState state, BigArrays bigArrays, int precision) {
    this.channels = channels;
    this.state = state;
    this.bigArrays = bigArrays;
    this.precision = precision;
  }

  public static CountDistinctIntGroupingAggregatorFunction create(List<Integer> channels,
      BigArrays bigArrays, int precision) {
    return new CountDistinctIntGroupingAggregatorFunction(channels, CountDistinctIntAggregator.initGrouping(bigArrays, precision), bigArrays, precision);
  }

  public static List<IntermediateStateDesc> intermediateStateDesc() {
    return INTERMEDIATE_STATE_DESC;
  }

  @Override
  public int intermediateBlockCount() {
    return INTERMEDIATE_STATE_DESC.size();
  }

  @Override
  public GroupingAggregatorFunction.AddInput prepareProcessPage(Page page) {
    Block uncastValuesBlock = page.getBlock(channels.get(0));
    if (uncastValuesBlock.areAllValuesNull()) {
      return new GroupingAggregatorFunction.AddInput() {
        @Override
        public void add(int positionOffset, LongBlock groupIds) {
          addRawInputAllNulls(positionOffset, groupIds, uncastValuesBlock);
        }

        @Override
        public void add(int positionOffset, LongVector groupIds) {
          addRawInputAllNulls(positionOffset, groupIds, uncastValuesBlock);
        }
      };
    }
    IntBlock valuesBlock = (IntBlock) uncastValuesBlock;
    IntVector valuesVector = valuesBlock.asVector();
    if (valuesVector == null) {
      return new GroupingAggregatorFunction.AddInput() {
        @Override
        public void add(int positionOffset, LongBlock groupIds) {
          addRawInput(positionOffset, groupIds, valuesBlock);
        }

        @Override
        public void add(int positionOffset, LongVector groupIds) {
          addRawInput(positionOffset, groupIds, valuesBlock);
        }
      };
    }
    return new GroupingAggregatorFunction.AddInput() {
      @Override
      public void add(int positionOffset, LongBlock groupIds) {
        addRawInput(positionOffset, groupIds, valuesVector);
      }

      @Override
      public void add(int positionOffset, LongVector groupIds) {
        addRawInput(positionOffset, groupIds, valuesVector);
      }
    };
  }

  private void addRawInput(int positionOffset, LongVector groups, IntBlock values) {
    for (int groupPosition = 0; groupPosition < groups.getPositionCount(); groupPosition++) {
      int groupId = Math.toIntExact(groups.getLong(groupPosition));
      if (values.isNull(groupPosition + positionOffset)) {
        state.putNull(groupId);
        continue;
      }
      int valuesStart = values.getFirstValueIndex(groupPosition + positionOffset);
      int valuesEnd = valuesStart + values.getValueCount(groupPosition + positionOffset);
      for (int v = valuesStart; v < valuesEnd; v++) {
        CountDistinctIntAggregator.combine(state, groupId, values.getInt(v));
      }
    }
  }

  private void addRawInput(int positionOffset, LongVector groups, IntVector values) {
    for (int groupPosition = 0; groupPosition < groups.getPositionCount(); groupPosition++) {
      int groupId = Math.toIntExact(groups.getLong(groupPosition));
      CountDistinctIntAggregator.combine(state, groupId, values.getInt(groupPosition + positionOffset));
    }
  }

  private void addRawInputAllNulls(int positionOffset, LongVector groups, Block values) {
    for (int groupPosition = 0; groupPosition < groups.getPositionCount(); groupPosition++) {
      int groupId = Math.toIntExact(groups.getLong(groupPosition));
      assert values.isNull(groupPosition + positionOffset);
      state.putNull(groupPosition + positionOffset);
    }
  }

  private void addRawInput(int positionOffset, LongBlock groups, IntBlock values) {
    for (int groupPosition = 0; groupPosition < groups.getPositionCount(); groupPosition++) {
      if (groups.isNull(groupPosition)) {
        continue;
      }
      int groupStart = groups.getFirstValueIndex(groupPosition);
      int groupEnd = groupStart + groups.getValueCount(groupPosition);
      for (int g = groupStart; g < groupEnd; g++) {
        int groupId = Math.toIntExact(groups.getLong(g));
        if (values.isNull(groupPosition + positionOffset)) {
          state.putNull(groupId);
          continue;
        }
        int valuesStart = values.getFirstValueIndex(groupPosition + positionOffset);
        int valuesEnd = valuesStart + values.getValueCount(groupPosition + positionOffset);
        for (int v = valuesStart; v < valuesEnd; v++) {
          CountDistinctIntAggregator.combine(state, groupId, values.getInt(v));
        }
      }
    }
  }

  private void addRawInput(int positionOffset, LongBlock groups, IntVector values) {
    for (int groupPosition = 0; groupPosition < groups.getPositionCount(); groupPosition++) {
      if (groups.isNull(groupPosition)) {
        continue;
      }
      int groupStart = groups.getFirstValueIndex(groupPosition);
      int groupEnd = groupStart + groups.getValueCount(groupPosition);
      for (int g = groupStart; g < groupEnd; g++) {
        int groupId = Math.toIntExact(groups.getLong(g));
        CountDistinctIntAggregator.combine(state, groupId, values.getInt(groupPosition + positionOffset));
      }
    }
  }

  private void addRawInputAllNulls(int positionOffset, LongBlock groups, Block values) {
    for (int groupPosition = 0; groupPosition < groups.getPositionCount(); groupPosition++) {
      if (groups.isNull(groupPosition)) {
        continue;
      }
      int groupStart = groups.getFirstValueIndex(groupPosition);
      int groupEnd = groupStart + groups.getValueCount(groupPosition);
      for (int g = groupStart; g < groupEnd; g++) {
        int groupId = Math.toIntExact(groups.getLong(g));
        assert values.isNull(groupPosition + positionOffset);
        state.putNull(groupPosition + positionOffset);
      }
    }
  }

  @Override
  public void addIntermediateInput(LongVector groupIdVector, Page page) {
    Block block = page.getBlock(channels.get(0));
    Vector vector = block.asVector();
    if (vector == null || vector instanceof AggregatorStateVector == false) {
      throw new RuntimeException("expected AggregatorStateBlock, got:" + block);
    }
    @SuppressWarnings("unchecked") AggregatorStateVector<HllStates.GroupingState> blobVector = (AggregatorStateVector<HllStates.GroupingState>) vector;
    // TODO exchange big arrays directly without funny serialization - no more copying
    BigArrays bigArrays = BigArrays.NON_RECYCLING_INSTANCE;
    HllStates.GroupingState inState = CountDistinctIntAggregator.initGrouping(bigArrays, precision);
    blobVector.get(0, inState);
    for (int position = 0; position < groupIdVector.getPositionCount(); position++) {
      int groupId = Math.toIntExact(groupIdVector.getLong(position));
      CountDistinctIntAggregator.combineStates(state, groupId, inState, position);
    }
    inState.close();
  }

  @Override
  public void addIntermediateRowInput(int groupId, GroupingAggregatorFunction input, int position) {
    if (input.getClass() != getClass()) {
      throw new IllegalArgumentException("expected " + getClass() + "; got " + input.getClass());
    }
    HllStates.GroupingState inState = ((CountDistinctIntGroupingAggregatorFunction) input).state;
    CountDistinctIntAggregator.combineStates(state, groupId, inState, position);
  }

  @Override
  public void evaluateIntermediate(Block[] blocks, int offset, IntVector selected) {
    AggregatorStateVector.Builder<AggregatorStateVector<HllStates.GroupingState>, HllStates.GroupingState> builder =
        AggregatorStateVector.builderOfAggregatorState(HllStates.GroupingState.class, state.getEstimatedSize());
    builder.add(state, selected);
    blocks[offset] = builder.build().asBlock();
  }

  @Override
  public void evaluateFinal(Block[] blocks, int offset, IntVector selected) {
    blocks[offset] = CountDistinctIntAggregator.evaluateFinal(state, selected);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getClass().getSimpleName()).append("[");
    sb.append("channels=").append(channels);
    sb.append("]");
    return sb.toString();
  }

  @Override
  public void close() {
    state.close();
  }
}
