// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.compute.aggregation;

import java.lang.Override;
import java.lang.String;
import java.lang.StringBuilder;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.AggregatorStateVector;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;

/**
 * {@link AggregatorFunction} implementation for {@link CountDistinctIntAggregator}.
 * This class is generated. Do not edit it.
 */
public final class CountDistinctIntAggregatorFunction implements AggregatorFunction {
  private final HllStates.SingleState state;

  private final int channel;

  private final BigArrays bigArrays;

  private final int precision;

  public CountDistinctIntAggregatorFunction(int channel, HllStates.SingleState state,
      BigArrays bigArrays, int precision) {
    this.channel = channel;
    this.state = state;
    this.bigArrays = bigArrays;
    this.precision = precision;
  }

  public static CountDistinctIntAggregatorFunction create(int channel, BigArrays bigArrays,
      int precision) {
    return new CountDistinctIntAggregatorFunction(channel, CountDistinctIntAggregator.initSingle(bigArrays, precision), bigArrays, precision);
  }

  @Override
  public void addRawInput(Page page) {
    ElementType type = page.getBlock(channel).elementType();
    if (type == ElementType.NULL) {
      return;
    }
    IntBlock block = page.getBlock(channel);
    IntVector vector = block.asVector();
    if (vector != null) {
      addRawVector(vector);
    } else {
      addRawBlock(block);
    }
  }

  private void addRawVector(IntVector vector) {
    for (int i = 0; i < vector.getPositionCount(); i++) {
      CountDistinctIntAggregator.combine(state, vector.getInt(i));
    }
  }

  private void addRawBlock(IntBlock block) {
    for (int p = 0; p < block.getPositionCount(); p++) {
      if (block.isNull(p)) {
        continue;
      }
      int start = block.getFirstValueIndex(p);
      int end = start + block.getValueCount(p);
      for (int i = start; i < end; i++) {
        CountDistinctIntAggregator.combine(state, block.getInt(i));
      }
    }
  }

  @Override
  public void addIntermediateInput(Block block) {
    Vector vector = block.asVector();
    if (vector == null || vector instanceof AggregatorStateVector == false) {
      throw new RuntimeException("expected AggregatorStateBlock, got:" + block);
    }
    @SuppressWarnings("unchecked") AggregatorStateVector<HllStates.SingleState> blobVector = (AggregatorStateVector<HllStates.SingleState>) vector;
    // TODO exchange big arrays directly without funny serialization - no more copying
    BigArrays bigArrays = BigArrays.NON_RECYCLING_INSTANCE;
    HllStates.SingleState tmpState = CountDistinctIntAggregator.initSingle(bigArrays, precision);
    for (int i = 0; i < block.getPositionCount(); i++) {
      blobVector.get(i, tmpState);
      CountDistinctIntAggregator.combineStates(state, tmpState);
    }
    tmpState.close();
  }

  @Override
  public Block evaluateIntermediate() {
    AggregatorStateVector.Builder<AggregatorStateVector<HllStates.SingleState>, HllStates.SingleState> builder =
        AggregatorStateVector.builderOfAggregatorState(HllStates.SingleState.class, state.getEstimatedSize());
    builder.add(state, IntVector.range(0, 1));
    return builder.build().asBlock();
  }

  @Override
  public Block evaluateFinal() {
    return CountDistinctIntAggregator.evaluateFinal(state);
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
