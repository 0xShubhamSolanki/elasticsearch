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
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;

/**
 * {@link AggregatorFunction} implementation for {@link MinLongAggregator}.
 * This class is generated. Do not edit it.
 */
public final class MinLongAggregatorFunction implements AggregatorFunction {
  private final LongState state;

  private final int channel;

  public MinLongAggregatorFunction(int channel, LongState state) {
    this.channel = channel;
    this.state = state;
  }

  public static MinLongAggregatorFunction create(int channel) {
    return new MinLongAggregatorFunction(channel, new LongState(MinLongAggregator.init()));
  }

  @Override
  public void addRawInput(Page page) {
    ElementType type = page.getBlock(channel).elementType();
    if (type == ElementType.NULL) {
      return;
    }
    LongBlock block = page.getBlock(channel);
    LongVector vector = block.asVector();
    if (vector != null) {
      addRawVector(vector);
    } else {
      addRawBlock(block);
    }
  }

  private void addRawVector(LongVector vector) {
    for (int i = 0; i < vector.getPositionCount(); i++) {
      state.longValue(MinLongAggregator.combine(state.longValue(), vector.getLong(i)));
    }
  }

  private void addRawBlock(LongBlock block) {
    for (int p = 0; p < block.getPositionCount(); p++) {
      if (block.isNull(p)) {
        continue;
      }
      int start = block.getFirstValueIndex(p);
      int end = start + block.getValueCount(p);
      for (int i = start; i < end; i++) {
        state.longValue(MinLongAggregator.combine(state.longValue(), block.getLong(i)));
      }
    }
  }

  @Override
  public void addIntermediateInput(Block block) {
    Vector vector = block.asVector();
    if (vector == null || vector instanceof AggregatorStateVector == false) {
      throw new RuntimeException("expected AggregatorStateBlock, got:" + block);
    }
    @SuppressWarnings("unchecked") AggregatorStateVector<LongState> blobVector = (AggregatorStateVector<LongState>) vector;
    // TODO exchange big arrays directly without funny serialization - no more copying
    BigArrays bigArrays = BigArrays.NON_RECYCLING_INSTANCE;
    LongState tmpState = new LongState(MinLongAggregator.init());
    for (int i = 0; i < block.getPositionCount(); i++) {
      blobVector.get(i, tmpState);
      state.longValue(MinLongAggregator.combine(state.longValue(), tmpState.longValue()));
    }
    tmpState.close();
  }

  @Override
  public Block evaluateIntermediate() {
    AggregatorStateVector.Builder<AggregatorStateVector<LongState>, LongState> builder =
        AggregatorStateVector.builderOfAggregatorState(LongState.class, state.getEstimatedSize());
    builder.add(state, IntVector.range(0, 1));
    return builder.build().asBlock();
  }

  @Override
  public Block evaluateFinal() {
    return LongBlock.newConstantBlockWith(state.longValue(), 1);
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
