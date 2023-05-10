/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation.blockhash;

import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.BytesRefArray;
import org.elasticsearch.common.util.BytesRefHash;
import org.elasticsearch.compute.data.BytesRefArrayVector;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;

import java.io.IOException;

final class BytesRefBlockHash extends BlockHash {
    private final BytesRef bytes = new BytesRef();
    private final int channel;
    private final BytesRefHash bytesRefHash;

    BytesRefBlockHash(int channel, BigArrays bigArrays) {
        this.channel = channel;
        this.bytesRefHash = new BytesRefHash(1, bigArrays);
    }

    @Override
    public LongBlock add(Page page) {
        BytesRefBlock block = page.getBlock(channel);
        BytesRefVector vector = block.asVector();
        if (vector == null) {
            return add(block);
        }
        return add(vector).asBlock();
    }

    private LongVector add(BytesRefVector vector) {
        long[] groups = new long[vector.getPositionCount()];
        for (int i = 0; i < vector.getPositionCount(); i++) {
            groups[i] = hashOrdToGroup(bytesRefHash.add(vector.getBytesRef(i, bytes)));
        }
        return new LongArrayVector(groups, vector.getPositionCount());
    }

    private static final long[] EMPTY = new long[0];

    private LongBlock add(BytesRefBlock block) {
        long[] seen = EMPTY;
        LongBlock.Builder builder = LongBlock.newBlockBuilder(block.getPositionCount());
        for (int p = 0; p < block.getPositionCount(); p++) {
            if (block.isNull(p)) {
                builder.appendNull();
                continue;
            }
            int start = block.getFirstValueIndex(p);
            int count = block.getValueCount(p);
            if (count == 1) {
                builder.appendLong(hashOrdToGroup(bytesRefHash.add(block.getBytesRef(start, bytes))));
                continue;
            }
            if (seen.length < count) {
                seen = new long[ArrayUtil.oversize(count, Long.BYTES)];
            }
            builder.beginPositionEntry();
            // TODO if we know the elements were in sorted order we wouldn't need an array at all.
            // TODO we could also have an assertion that there aren't any duplicates on the block.
            // Lucene has them in ascending order without duplicates
            int end = start + count;
            int i = 0;
            value: for (int offset = start; offset < end; offset++) {
                long ord = bytesRefHash.add(block.getBytesRef(offset, bytes));
                if (ord < 0) { // already seen
                    ord = -1 - ord;
                    /*
                     * Check if we've seen the value before. This is n^2 on the number of
                     * values, but we don't expect many of them in each entry.
                     */
                    for (int j = 0; j < i; j++) {
                        if (seen[j] == ord) {
                            continue value;
                        }
                    }
                }
                seen[i++] = ord;
                builder.appendLong(ord);
            }
            builder.endPositionEntry();
        }
        return builder.build();
    }

    @Override
    public BytesRefBlock[] getKeys() {
        final int size = Math.toIntExact(bytesRefHash.size());
        /*
         * Create an un-owned copy of the data so we can close our BytesRefHash
         * without and still read from the block.
         */
        // TODO replace with takeBytesRefsOwnership ?!
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            bytesRefHash.getBytesRefs().writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                return new BytesRefBlock[] {
                    new BytesRefArrayVector(new BytesRefArray(in, BigArrays.NON_RECYCLING_INSTANCE), size).asBlock() };
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public IntVector nonEmpty() {
        return IntVector.range(0, Math.toIntExact(bytesRefHash.size()));
    }

    @Override
    public void close() {
        bytesRefHash.close();
    }

    @Override
    public String toString() {
        return "BytesRefBlockHash{channel="
            + channel
            + ", entries="
            + bytesRefHash.size()
            + ", size="
            + ByteSizeValue.ofBytes(bytesRefHash.ramBytesUsed())
            + '}';
    }
}
