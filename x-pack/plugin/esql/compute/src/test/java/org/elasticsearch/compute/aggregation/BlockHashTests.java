/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.compute.aggregation.blockhash.BlockHash;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BooleanArrayVector;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.DoubleArrayVector;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.IntArrayVector;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.LongArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.HashAggregationOperator;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.hamcrest.Matcher;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

public class BlockHashTests extends ESTestCase {
    public void testIntHash() {
        int[] values = new int[] { 1, 2, 3, 1, 2, 3, 1, 2, 3 };
        IntBlock block = new IntArrayVector(values, values.length, null).asBlock();

        OrdsAndKeys ordsAndKeys = hash(equalTo("IntBlockHash{channel=0, entries=3}"), block);
        assertOrds(ordsAndKeys.ords, 0L, 1L, 2L, 0L, 1L, 2L, 0L, 1L, 2L);
        assertKeys(ordsAndKeys.keys, 1, 2, 3);
    }

    public void testIntHashWithNulls() {
        IntBlock.Builder builder = IntBlock.newBlockBuilder(4);
        builder.appendInt(0);
        builder.appendNull();
        builder.appendInt(2);
        builder.appendNull();

        OrdsAndKeys ordsAndKeys = hash(equalTo("IntBlockHash{channel=0, entries=2}"), builder.build());
        assertOrds(ordsAndKeys.ords, 0L, null, 1L, null);
        assertKeys(ordsAndKeys.keys, 0, 2);
    }

    public void testLongHash() {
        long[] values = new long[] { 2, 1, 4, 2, 4, 1, 3, 4 };
        LongBlock block = new LongArrayVector(values, values.length).asBlock();

        OrdsAndKeys ordsAndKeys = hash(equalTo("LongBlockHash{channel=0, entries=4}"), block);
        assertOrds(ordsAndKeys.ords, 0L, 1L, 2L, 0L, 2L, 1L, 3L, 2L);
        assertKeys(ordsAndKeys.keys, 2L, 1L, 4L, 3L);
    }

    public void testLongHashWithNulls() {
        LongBlock.Builder builder = LongBlock.newBlockBuilder(4);
        builder.appendLong(0);
        builder.appendNull();
        builder.appendLong(2);
        builder.appendNull();

        OrdsAndKeys ordsAndKeys = hash(equalTo("LongBlockHash{channel=0, entries=2}"), builder.build());
        assertOrds(ordsAndKeys.ords, 0L, null, 1L, null);
        assertKeys(ordsAndKeys.keys, 0L, 2L);
    }

    public void testDoubleHash() {
        double[] values = new double[] { 2.0, 1.0, 4.0, 2.0, 4.0, 1.0, 3.0, 4.0 };
        DoubleBlock block = new DoubleArrayVector(values, values.length).asBlock();
        OrdsAndKeys ordsAndKeys = hash(equalTo("DoubleBlockHash{channel=0, entries=4}"), block);

        assertOrds(ordsAndKeys.ords, 0L, 1L, 2L, 0L, 2L, 1L, 3L, 2L);
        assertKeys(ordsAndKeys.keys, 2.0, 1.0, 4.0, 3.0);
    }

    public void testDoubleHashWithNulls() {
        DoubleBlock.Builder builder = DoubleBlock.newBlockBuilder(4);
        builder.appendDouble(0);
        builder.appendNull();
        builder.appendDouble(2);
        builder.appendNull();

        OrdsAndKeys ordsAndKeys = hash(equalTo("DoubleBlockHash{channel=0, entries=2}"), builder.build());
        assertOrds(ordsAndKeys.ords, 0L, null, 1L, null);
        assertKeys(ordsAndKeys.keys, 0.0, 2.0);
    }

    public void testBasicBytesRefHash() {
        var builder = BytesRefBlock.newBlockBuilder(8);
        builder.appendBytesRef(new BytesRef("item-2"));
        builder.appendBytesRef(new BytesRef("item-1"));
        builder.appendBytesRef(new BytesRef("item-4"));
        builder.appendBytesRef(new BytesRef("item-2"));
        builder.appendBytesRef(new BytesRef("item-4"));
        builder.appendBytesRef(new BytesRef("item-1"));
        builder.appendBytesRef(new BytesRef("item-3"));
        builder.appendBytesRef(new BytesRef("item-4"));
        OrdsAndKeys ordsAndKeys = hash(
            both(startsWith("BytesRefBlockHash{channel=0, entries=4, size=")).and(endsWith("b}")),
            builder.build()
        );

        assertOrds(ordsAndKeys.ords, 0L, 1L, 2L, 0L, 2L, 1L, 3L, 2L);
        assertKeys(ordsAndKeys.keys, "item-2", "item-1", "item-4", "item-3");
    }

    public void testBytesRefHashWithNulls() {
        BytesRefBlock.Builder builder = BytesRefBlock.newBlockBuilder(4);
        builder.appendBytesRef(new BytesRef("cat"));
        builder.appendNull();
        builder.appendBytesRef(new BytesRef("dog"));
        builder.appendNull();

        OrdsAndKeys ordsAndKeys = hash(
            both(startsWith("BytesRefBlockHash{channel=0, entries=2, size=")).and(endsWith("b}")),
            builder.build()
        );
        assertOrds(ordsAndKeys.ords, 0L, null, 1L, null);
        assertKeys(ordsAndKeys.keys, "cat", "dog");
    }

    public void testBooleanHashFalseFirst() {
        boolean[] values = new boolean[] { false, true, true, true, true };
        BooleanBlock block = new BooleanArrayVector(values, values.length).asBlock();

        OrdsAndKeys ordsAndKeys = hash(equalTo("BooleanBlockHash{channel=0, true=1, false=0}"), block);
        assertOrds(ordsAndKeys.ords, 0L, 1L, 1L, 1L, 1L);
        assertKeys(ordsAndKeys.keys, false, true);
    }

    public void testBooleanHashTrueFirst() {
        boolean[] values = new boolean[] { true, false, false, true, true };
        BooleanBlock block = new BooleanArrayVector(values, values.length).asBlock();

        OrdsAndKeys ordsAndKeys = hash(equalTo("BooleanBlockHash{channel=0, true=0, false=1}"), block);
        assertOrds(ordsAndKeys.ords, 0L, 1L, 1L, 0L, 0L);
        assertKeys(ordsAndKeys.keys, true, false);
    }

    public void testBooleanHashTrueOnly() {
        boolean[] values = new boolean[] { true, true, true, true };
        BooleanBlock block = new BooleanArrayVector(values, values.length).asBlock();

        OrdsAndKeys ordsAndKeys = hash(equalTo("BooleanBlockHash{channel=0, true=0}"), block);
        assertOrds(ordsAndKeys.ords, 0L, 0L, 0L, 0L);
        assertKeys(ordsAndKeys.keys, true);
    }

    public void testBooleanHashFalseOnly() {
        boolean[] values = new boolean[] { false, false, false, false };
        BooleanBlock block = new BooleanArrayVector(values, values.length).asBlock();

        OrdsAndKeys ordsAndKeys = hash(equalTo("BooleanBlockHash{channel=0, false=0}"), block);
        assertOrds(ordsAndKeys.ords, 0L, 0L, 0L, 0L);
        assertKeys(ordsAndKeys.keys, false);
    }

    public void testBooleanHashWithNulls() {
        BooleanBlock.Builder builder = BooleanBlock.newBlockBuilder(4);
        builder.appendBoolean(false);
        builder.appendNull();
        builder.appendBoolean(true);
        builder.appendNull();

        OrdsAndKeys ordsAndKeys = hash(equalTo("BooleanBlockHash{channel=0, true=1, false=0}"), builder.build());
        assertOrds(ordsAndKeys.ords, 0L, null, 1L, null);
        assertKeys(ordsAndKeys.keys, false, true);
    }

    public void testLongLongHash() {
        long[] values1 = new long[] { 0, 1, 0, 1, 0, 1 };
        LongBlock block1 = new LongArrayVector(values1, values1.length).asBlock();
        long[] values2 = new long[] { 0, 0, 0, 1, 1, 1 };
        LongBlock block2 = new LongArrayVector(values2, values2.length).asBlock();

        OrdsAndKeys ordsAndKeys = hash(
            both(startsWith("PackedValuesBlockHash{keys=[LongKey[channel=0], LongKey[channel=1]], entries=4, size=")).and(endsWith("b}")),
            block1,
            block2
        );
        assertOrds(ordsAndKeys.ords, 0L, 1L, 0L, 2L, 3L, 2L);
        assertKeys(
            ordsAndKeys.keys,
            new Object[][] { new Object[] { 0L, 0L }, new Object[] { 1L, 0L }, new Object[] { 1L, 1L }, new Object[] { 0L, 1L } }
        );
    }

    public void testLongLongHashWithNull() {
        LongBlock.Builder b1 = LongBlock.newBlockBuilder(2);
        LongBlock.Builder b2 = LongBlock.newBlockBuilder(2);
        b1.appendLong(1);
        b2.appendLong(0);
        b1.appendNull();
        b2.appendNull();
        b1.appendLong(0);
        b2.appendLong(1);
        b1.appendLong(0);
        b2.appendNull();
        b1.appendNull();
        b2.appendLong(0);

        OrdsAndKeys ordsAndKeys = hash(
            both(startsWith("PackedValuesBlockHash{keys=[LongKey[channel=0], LongKey[channel=1]], entries=2, size=")).and(endsWith("b}")),
            b1.build(),
            b2.build()
        );
        assertOrds(ordsAndKeys.ords, 0L, null, 1L, null, null);
        assertKeys(ordsAndKeys.keys, new Object[][] { new Object[] { 1L, 0L }, new Object[] { 0L, 1L } });
    }

    public void testLongBytesRefHash() {
        long[] values1 = new long[] { 0, 1, 0, 1, 0, 1 };
        LongBlock block1 = new LongArrayVector(values1, values1.length).asBlock();
        BytesRefBlock.Builder builder = BytesRefBlock.newBlockBuilder(8);
        builder.appendBytesRef(new BytesRef("cat"));
        builder.appendBytesRef(new BytesRef("cat"));
        builder.appendBytesRef(new BytesRef("cat"));
        builder.appendBytesRef(new BytesRef("dog"));
        builder.appendBytesRef(new BytesRef("dog"));
        builder.appendBytesRef(new BytesRef("dog"));
        BytesRefBlock block2 = builder.build();

        OrdsAndKeys ordsAndKeys = hash(
            both(startsWith("PackedValuesBlockHash{keys=[LongKey[channel=0], BytesRefKey[channel=1]], entries=4, size=")).and(
                endsWith("b}")
            ),
            block1,
            block2
        );
        assertOrds(ordsAndKeys.ords, 0L, 1L, 0L, 2L, 3L, 2L);
        assertKeys(
            ordsAndKeys.keys,
            new Object[][] {
                new Object[] { 0L, "cat" },
                new Object[] { 1L, "cat" },
                new Object[] { 1L, "dog" },
                new Object[] { 0L, "dog" } }
        );
    }

    public void testLongBytesRefHashWithNull() {
        LongBlock.Builder b1 = LongBlock.newBlockBuilder(2);
        BytesRefBlock.Builder b2 = BytesRefBlock.newBlockBuilder(2);
        b1.appendLong(1);
        b2.appendBytesRef(new BytesRef("cat"));
        b1.appendNull();
        b2.appendNull();
        b1.appendLong(0);
        b2.appendBytesRef(new BytesRef("dog"));
        b1.appendLong(0);
        b2.appendNull();
        b1.appendNull();
        b2.appendBytesRef(new BytesRef("vanish"));

        OrdsAndKeys ordsAndKeys = hash(
            both(startsWith("PackedValuesBlockHash{keys=[LongKey[channel=0], BytesRefKey[channel=1]], entries=2, size=")).and(
                endsWith("b}")
            ),
            b1.build(),
            b2.build()
        );
        assertOrds(ordsAndKeys.ords, 0L, null, 1L, null, null);
        assertKeys(ordsAndKeys.keys, new Object[][] { new Object[] { 1L, "cat" }, new Object[] { 0L, "dog" } });
    }

    record OrdsAndKeys(LongBlock ords, Block[] keys) {}

    private OrdsAndKeys hash(Matcher<String> toStringMatcher, Block... values) {
        List<HashAggregationOperator.GroupSpec> specs = new ArrayList<>(values.length);
        for (int c = 0; c < values.length; c++) {
            specs.add(new HashAggregationOperator.GroupSpec(c, values[c].elementType()));
        }
        try (
            BlockHash blockHash = BlockHash.build(
                specs,
                new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, new NoneCircuitBreakerService())
            )
        ) {
            LongBlock ordsBlock = blockHash.add(new Page(values));
            assertThat(blockHash.toString(), toStringMatcher);
            return new OrdsAndKeys(ordsBlock, blockHash.getKeys());
        }
    }

    private void assertOrds(LongBlock ordsBlock, Long... expectedOrds) {
        assertEquals(expectedOrds.length, ordsBlock.getPositionCount());
        for (int i = 0; i < expectedOrds.length; i++) {
            if (expectedOrds[i] == null) {
                assertTrue(ordsBlock.isNull(i));
            } else {
                assertFalse(ordsBlock.isNull(i));
                assertEquals("entry " + i, expectedOrds[i].longValue(), ordsBlock.getLong(i));
            }
        }
    }

    private void assertKeys(Block[] actualKeys, Object... expectedKeys) {
        Object[][] flipped = new Object[expectedKeys.length][];
        for (int r = 0; r < flipped.length; r++) {
            flipped[r] = new Object[] { expectedKeys[r] };
        }
        assertKeys(actualKeys, flipped);
    }

    private void assertKeys(Block[] actualKeys, Object[][] expectedKeys) {
        for (int r = 0; r < expectedKeys.length; r++) {
            assertThat(actualKeys, arrayWithSize(expectedKeys[r].length));
        }
        for (int c = 0; c < actualKeys.length; c++) {
            assertThat("block " + c, actualKeys[c].getPositionCount(), equalTo(expectedKeys.length));
        }
        for (int r = 0; r < expectedKeys.length; r++) {
            for (int c = 0; c < actualKeys.length; c++) {
                if (expectedKeys[r][c]instanceof Integer v) {
                    assertThat(((IntBlock) actualKeys[c]).getInt(r), equalTo(v));
                } else if (expectedKeys[r][c]instanceof Long v) {
                    assertThat(((LongBlock) actualKeys[c]).getLong(r), equalTo(v));
                } else if (expectedKeys[r][c]instanceof Double v) {
                    assertThat(((DoubleBlock) actualKeys[c]).getDouble(r), equalTo(v));
                } else if (expectedKeys[r][c]instanceof String v) {
                    assertThat(((BytesRefBlock) actualKeys[c]).getBytesRef(r, new BytesRef()), equalTo(new BytesRef(v)));
                } else if (expectedKeys[r][c]instanceof Boolean v) {
                    assertThat(((BooleanBlock) actualKeys[c]).getBoolean(r), equalTo(v));
                } else {
                    throw new IllegalArgumentException("unsupported type " + expectedKeys[r][c].getClass());
                }
            }
        }
    }
}
