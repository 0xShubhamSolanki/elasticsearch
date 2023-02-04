/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

import java.util.Arrays;

/**
 * Block build of IntBlocks.
 * This class is generated. Do not edit it.
 */
final class IntBlockBuilder extends AbstractBlockBuilder implements IntBlock.Builder {

    private int[] values;

    IntBlockBuilder(int estimatedSize) {
        values = new int[Math.max(estimatedSize, 2)];
    }

    @Override
    public IntBlockBuilder appendInt(int value) {
        ensureCapacity();
        values[valueCount] = value;
        hasNonNullValue = true;
        valueCount++;
        updatePosition();
        return this;
    }

    @Override
    protected int valuesLength() {
        return values.length;
    }

    @Override
    protected void growValuesArray(int newSize) {
        values = Arrays.copyOf(values, newSize);
    }

    @Override
    public IntBlockBuilder appendNull() {
        super.appendNull();
        return this;
    }

    @Override
    public IntBlockBuilder beginPositionEntry() {
        super.beginPositionEntry();
        return this;
    }

    @Override
    public IntBlockBuilder endPositionEntry() {
        super.endPositionEntry();
        return this;
    }

    @Override
    public IntBlock build() {
        if (positionEntryIsOpen) {
            endPositionEntry();
        }
        if (hasNonNullValue && positionCount == 1 && valueCount == 1) {
            return new ConstantIntVector(values[0], 1).asBlock();
        } else {
            // TODO: may wanna trim the array, if there N% unused tail space
            if (isDense() && singleValued()) {
                return new IntArrayVector(values, positionCount).asBlock();
            } else {
                return new IntArrayBlock(values, positionCount, firstValueIndexes, nullsMask);
            }
        }
    }
}
