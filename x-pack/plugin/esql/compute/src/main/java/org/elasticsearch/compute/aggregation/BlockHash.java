/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.BytesRefArray;
import org.elasticsearch.common.util.BytesRefHash;
import org.elasticsearch.common.util.LongHash;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefArrayVector;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.DoubleArrayVector;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.IntArrayVector;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.LongArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceType;

import java.io.IOException;
import java.util.Locale;

/**
 * A specialized hash table implementation maps values of a {@link Block} to ids (in longs).
 * This class delegates to {@link LongHash} or {@link BytesRefHash}.
 *
 * @see LongHash
 * @see BytesRefHash
 */
public abstract sealed class BlockHash implements Releasable {

    /**
     * Try to add the value (as the key) at the given position of the Block to the hash.
     * Return its newly allocated id if it wasn't in the hash table yet, or {@code -1}
     * if it was already present in the hash table.
     *
     * @see LongHash#add(long)
     * @see BytesRefHash#add(BytesRef)
     */
    public abstract long add(Block block, int position);

    /**
     * Returns a {@link Block} that contains all the keys that are inserted by {@link #add(Block, int)}.
     */
    public abstract Block getKeys();

    /** Element type that this block hash will accept as input. */
    public enum Type {
        INT,
        LONG,
        DOUBLE,
        BYTES_REF;

        /** Maps an ESQL data type name to a Block hash input element type. */
        public static Type mapFromDataType(String name) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "integer" -> INT;
                case "long" -> LONG;
                case "double" -> DOUBLE;
                case "keyword" -> BYTES_REF;
                default -> throw new UnsupportedOperationException("unknown type: " + name);
            };
        }
    }

    /**
     * Creates a specialized hash table that maps a {@link Block} of the given input element type to ids.
     */
    public static BlockHash newHashForType(Type type, BigArrays bigArrays) {
        return switch (type) {
            case INT -> new IntBlockHash(bigArrays);
            case LONG -> new LongBlockHash(bigArrays);
            case DOUBLE -> new DoubleBlockHash(bigArrays);
            case BYTES_REF -> new BytesRefBlockHash(bigArrays);
        };
    }

    public static BlockHash newHashForType(ValuesSource valuesSource, ValuesSourceType type, BigArrays bigArrays) {
        if (CoreValuesSourceType.NUMERIC.equals(type)) {
            ValuesSource.Numeric numericVS = (ValuesSource.Numeric) valuesSource;
            if (numericVS.isFloatingPoint()) {
                return new DoubleBlockHash(bigArrays);
            } else {
                return new LongBlockHash(bigArrays);
            }
        } else if (CoreValuesSourceType.KEYWORD.equals(type)) {
            return new BytesRefBlockHash(bigArrays);
        }
        throw new UnsupportedOperationException("unknown type: " + valuesSource + ", " + type);
    }

    private static final class LongBlockHash extends BlockHash {
        private final LongHash longHash;

        LongBlockHash(BigArrays bigArrays) {
            this.longHash = new LongHash(1, bigArrays);
        }

        @Override
        public long add(Block block, int position) {
            return longHash.add(((LongBlock) block).getLong(position));
        }

        @Override
        public LongBlock getKeys() {
            final int size = Math.toIntExact(longHash.size());
            final long[] keys = new long[size];
            for (int i = 0; i < size; i++) {
                keys[i] = longHash.get(i);
            }

            // TODO call something like takeKeyOwnership to claim the keys array directly
            return new LongArrayVector(keys, keys.length).asBlock();
        }

        @Override
        public void close() {
            longHash.close();
        }
    }

    private static final class IntBlockHash extends BlockHash {
        private final LongHash longHash;

        IntBlockHash(BigArrays bigArrays) {
            this.longHash = new LongHash(1, bigArrays);
        }

        @Override
        public long add(Block block, int position) {
            return longHash.add(((IntBlock) block).getInt(position));
        }

        @Override
        public IntBlock getKeys() {
            final int size = Math.toIntExact(longHash.size());
            final int[] keys = new int[size];
            for (int i = 0; i < size; i++) {
                keys[i] = (int) longHash.get(i);
            }
            return new IntArrayVector(keys, keys.length, null).asBlock();
        }

        @Override
        public void close() {
            longHash.close();
        }
    }

    private static final class DoubleBlockHash extends BlockHash {
        private final LongHash longHash;

        DoubleBlockHash(BigArrays bigArrays) {
            this.longHash = new LongHash(1, bigArrays);
        }

        @Override
        public long add(Block block, int position) {
            return longHash.add(Double.doubleToLongBits(((DoubleBlock) block).getDouble(position)));
        }

        @Override
        public DoubleBlock getKeys() {
            final int size = Math.toIntExact(longHash.size());
            final double[] keys = new double[size];
            for (int i = 0; i < size; i++) {
                keys[i] = Double.longBitsToDouble(longHash.get(i));
            }
            return new DoubleArrayVector(keys, keys.length).asBlock();
        }

        @Override
        public void close() {
            longHash.close();
        }
    }

    private static final class BytesRefBlockHash extends BlockHash {
        private final BytesRefHash bytesRefHash;
        private BytesRef bytes = new BytesRef();

        BytesRefBlockHash(BigArrays bigArrays) {
            this.bytesRefHash = new BytesRefHash(1, bigArrays);
        }

        @Override
        public long add(Block block, int position) {
            bytes = ((BytesRefBlock) block).getBytesRef(position, bytes);
            return bytesRefHash.add(bytes);
        }

        @Override
        public BytesRefBlock getKeys() {
            final int size = Math.toIntExact(bytesRefHash.size());
            /*
             * Create an un-owned copy of the data so we can close our BytesRefHash
             * without and still read from the block.
             */
            // TODO replace with takeBytesRefsOwnership ?!
            try (BytesStreamOutput out = new BytesStreamOutput()) {
                bytesRefHash.getBytesRefs().writeTo(out);
                try (StreamInput in = out.bytes().streamInput()) {
                    return new BytesRefArrayVector(new BytesRefArray(in, BigArrays.NON_RECYCLING_INSTANCE), size).asBlock();
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            bytesRefHash.close();
        }
    }
}
