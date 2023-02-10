/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

import org.apache.lucene.util.BytesRef;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.elasticsearch.compute.data.Block.constantNullBlock;

public final class BlockUtils {

    public static final Block[] NO_BLOCKS = new Block[0];

    private BlockUtils() {}

    public record BuilderWrapper(Block.Builder builder, Consumer<Object> append) {
        public BuilderWrapper(Block.Builder builder, Consumer<Object> append) {
            this.builder = builder;
            this.append = o -> {
                if (o == null) {
                    builder.appendNull();
                } else {
                    append.accept(o);
                }
            };
        }
    }

    public static Block[] fromArrayRow(Object... row) {
        return fromListRow(Arrays.asList(row));
    }

    public static Block[] fromListRow(List<Object> row) {
        return fromListRow(row, 1);
    }

    public static Block[] fromListRow(List<Object> row, int blockSize) {
        if (row.isEmpty()) {
            return NO_BLOCKS;
        }

        var size = row.size();
        Block[] blocks = new Block[size];
        for (int i = 0; i < size; i++) {
            Object object = row.get(i);
            if (object instanceof Integer intVal) {
                blocks[i] = IntBlock.newConstantBlockWith(intVal, blockSize);
            } else if (object instanceof Long longVal) {
                blocks[i] = LongBlock.newConstantBlockWith(longVal, blockSize);
            } else if (object instanceof Double doubleVal) {
                blocks[i] = DoubleBlock.newConstantBlockWith(doubleVal, blockSize);
            } else if (object instanceof BytesRef bytesRefVal) {
                blocks[i] = BytesRefBlock.newConstantBlockWith(bytesRefVal, blockSize);
            } else if (object instanceof Boolean booleanVal) {
                blocks[i] = BooleanBlock.newConstantBlockWith(booleanVal, blockSize);
            } else if (object == null) {
                blocks[i] = constantNullBlock(blockSize);
            } else {
                throw new UnsupportedOperationException();
            }
        }
        return blocks;
    }

    public static Block[] fromList(List<List<Object>> list) {
        var size = list.size();
        if (size == 0) {
            return NO_BLOCKS;
        }
        if (size == 1) {
            return fromListRow(list.get(0));
        }

        var wrappers = new BuilderWrapper[size];
        var types = list.get(0);

        for (int i = 0, tSize = types.size(); i < tSize; i++) {
            wrappers[i] = from(types.get(i).getClass(), size);
        }
        for (List<Object> values : list) {
            for (int j = 0, vSize = values.size(); j < vSize; j++) {
                wrappers[j].append.accept(values.get(j));
            }
        }
        return Arrays.stream(wrappers).map(b -> b.builder.build()).toArray(Block[]::new);
    }

    private static BuilderWrapper from(Class<?> type, int size) {
        BuilderWrapper builder;
        if (type == Integer.class) {
            var b = IntBlock.newBlockBuilder(size);
            builder = new BuilderWrapper(b, o -> b.appendInt((int) o));
        } else if (type == Long.class) {
            var b = LongBlock.newBlockBuilder(size);
            builder = new BuilderWrapper(b, o -> b.appendLong((long) o));
        } else if (type == Double.class) {
            var b = DoubleBlock.newBlockBuilder(size);
            builder = new BuilderWrapper(b, o -> b.appendDouble((double) o));
        } else if (type == BytesRef.class) {
            var b = BytesRefBlock.newBlockBuilder(size);
            builder = new BuilderWrapper(b, o -> b.appendBytesRef((BytesRef) o));
        } else if (type == Boolean.class) {
            var b = BooleanBlock.newBlockBuilder(size);
            builder = new BuilderWrapper(b, o -> b.appendBoolean((boolean) o));
        } else if (type == null) {
            var b = new Block.Builder() {
                @Override
                public Block.Builder appendNull() {
                    return this;
                }

                @Override
                public Block.Builder beginPositionEntry() {
                    return this;
                }

                @Override
                public Block.Builder endPositionEntry() {
                    return this;
                }

                @Override
                public Block build() {
                    return constantNullBlock(size);
                }
            };
            builder = new BuilderWrapper(b, o -> {});
        } else {
            throw new UnsupportedOperationException();
        }
        return builder;
    }
}
