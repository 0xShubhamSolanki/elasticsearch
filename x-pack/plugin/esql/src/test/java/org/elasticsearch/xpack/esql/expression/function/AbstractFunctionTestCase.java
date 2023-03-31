/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function;

import org.apache.lucene.sandbox.document.HalfFloatPoint;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockUtils;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.planner.EvalMapper;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.ql.expression.Expression;
import org.elasticsearch.xpack.ql.expression.FieldAttribute;
import org.elasticsearch.xpack.ql.expression.Literal;
import org.elasticsearch.xpack.ql.tree.Source;
import org.elasticsearch.xpack.ql.type.DataType;
import org.elasticsearch.xpack.ql.type.EsField;
import org.hamcrest.Matcher;

import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.elasticsearch.xpack.esql.SerializationTestUtils.assertSerialization;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Base class for function tests.
 */
public abstract class AbstractFunctionTestCase extends ESTestCase {
    /**
     * Generate a random value of the appropriate type to fit into blocks of {@code e}.
     */
    public static Literal randomLiteral(DataType type) {
        return new Literal(Source.EMPTY, switch (type.typeName()) {
            case "boolean" -> randomBoolean();
            case "byte" -> randomByte();
            case "short" -> randomShort();
            case "integer" -> randomInt();
            case "long" -> randomLong();
            case "date_period" -> Period.ofDays(randomInt(10));
            case "datetime" -> randomMillisUpToYear9999();
            case "double", "scaled_float" -> randomDouble();
            case "float" -> randomFloat();
            case "half_float" -> HalfFloatPoint.sortableShortToHalfFloat(HalfFloatPoint.halfFloatToSortableShort(randomFloat()));
            case "keyword" -> new BytesRef(randomAlphaOfLength(5));
            case "time_duration" -> Duration.ofMillis(randomNonNegativeLong());
            case "null" -> null;
            default -> throw new IllegalArgumentException("can't make random values for [" + type.typeName() + "]");
        }, type);
    }

    protected abstract List<Object> simpleData();

    protected abstract Expression expressionForSimpleData();

    protected abstract DataType expressionForSimpleDataType();

    protected abstract Matcher<Object> resultMatcher(List<Object> data);

    protected abstract String expectedEvaluatorSimpleToString();

    protected abstract Expression constantFoldable(List<Object> data);

    protected abstract Expression build(Source source, List<Literal> args);

    protected final Supplier<EvalOperator.ExpressionEvaluator> evaluator(Expression e) {
        Layout.Builder builder = new Layout.Builder();
        // Hack together a layout by scanning for Fields.
        // Those will show up in the layout in whatever order a depth first traversal finds them.
        buildLayout(builder, e);
        return EvalMapper.toEvaluator(e, builder.build());
    }

    protected final Page row(List<Object> values) {
        return new Page(BlockUtils.fromListRow(values));
    }

    private void buildLayout(Layout.Builder builder, Expression e) {
        if (e instanceof FieldAttribute f) {
            builder.appendChannel(f.id());
            return;
        }
        for (Expression c : e.children()) {
            buildLayout(builder, c);
        }
    }

    protected final FieldAttribute field(String name, DataType type) {
        return new FieldAttribute(Source.EMPTY, name, new EsField(name, type, Map.of(), true));
    }

    protected final void assertResolveTypeValid(Expression expression, DataType expectedType) {
        assertTrue(expression.typeResolved().resolved());
        assertThat(expressionForSimpleData().dataType(), equalTo(expectedType));
    }

    public final void testSimple() {
        List<Object> simpleData = simpleData();
        Object result = evaluator(expressionForSimpleData()).get().computeRow(row(simpleData), 0);
        assertThat(result, resultMatcher(simpleData));
    }

    public final void testSimpleWithNulls() {
        List<Object> simpleData = simpleData();
        EvalOperator.ExpressionEvaluator eval = evaluator(expressionForSimpleData()).get();
        Block[] orig = BlockUtils.fromListRow(simpleData);
        for (int i = 0; i < orig.length; i++) {
            List<Object> data = new ArrayList<>();
            Block[] blocks = new Block[orig.length];
            for (int b = 0; b < blocks.length; b++) {
                if (b == i) {
                    blocks[b] = orig[b].elementType().newBlockBuilder(1).appendNull().build();
                    data.add(null);
                } else {
                    blocks[b] = orig[b];
                    data.add(simpleData.get(b));
                }
            }
            assertSimpleWithNulls(data, eval.computeRow(new Page(blocks), 0), i);
        }
    }

    protected void assertSimpleWithNulls(List<Object> data, Object value, int nullBlock) {
        assertThat(value, nullValue());
    }

    public final void testSimpleInManyThreads() throws ExecutionException, InterruptedException {
        int count = 10_000;
        int threads = 5;
        Supplier<EvalOperator.ExpressionEvaluator> evalSupplier = evaluator(expressionForSimpleData());
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                List<Object> simpleData = simpleData();
                Page page = row(simpleData);
                Matcher<Object> resultMatcher = resultMatcher(simpleData);

                futures.add(exec.submit(() -> {
                    EvalOperator.ExpressionEvaluator eval = evalSupplier.get();
                    for (int c = 0; c < count; c++) {
                        assertThat(eval.computeRow(page, 0), resultMatcher);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            exec.shutdown();
        }
    }

    public final void testEvaluatorSimpleToString() {
        assertThat(evaluator(expressionForSimpleData()).get().toString(), equalTo(expectedEvaluatorSimpleToString()));
    }

    public final void testSimpleConstantFolding() {
        List<Object> simpleData = simpleData();
        Expression e = constantFoldable(simpleData);
        assertTrue(e.foldable());
        assertThat(e.fold(), resultMatcher(simpleData));
    }

    public void testSerializationOfSimple() {
        assertSerialization(expressionForSimpleData());
    }
}
