/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.analysis.Analyzer;
import org.elasticsearch.xpack.esql.analysis.AnalyzerContext;
import org.elasticsearch.xpack.esql.analysis.Verifier;
import org.elasticsearch.xpack.esql.expression.function.EsqlFunctionRegistry;
import org.elasticsearch.xpack.esql.expression.function.scalar.date.DateFormat;
import org.elasticsearch.xpack.esql.expression.function.scalar.date.DateTrunc;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Round;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Substring;
import org.elasticsearch.xpack.esql.optimizer.LogicalPlanOptimizer.FoldNull;
import org.elasticsearch.xpack.esql.parser.EsqlParser;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.ProjectReorderRenameRemove;
import org.elasticsearch.xpack.ql.expression.Alias;
import org.elasticsearch.xpack.ql.expression.Attribute;
import org.elasticsearch.xpack.ql.expression.Expression;
import org.elasticsearch.xpack.ql.expression.Expressions;
import org.elasticsearch.xpack.ql.expression.FieldAttribute;
import org.elasticsearch.xpack.ql.expression.Literal;
import org.elasticsearch.xpack.ql.expression.NamedExpression;
import org.elasticsearch.xpack.ql.expression.Nullability;
import org.elasticsearch.xpack.ql.expression.Order;
import org.elasticsearch.xpack.ql.expression.ReferenceAttribute;
import org.elasticsearch.xpack.ql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.ql.expression.predicate.logical.And;
import org.elasticsearch.xpack.ql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.ql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.ql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.GreaterThanOrEqual;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.ql.index.EsIndex;
import org.elasticsearch.xpack.ql.index.IndexResolution;
import org.elasticsearch.xpack.ql.plan.logical.Aggregate;
import org.elasticsearch.xpack.ql.plan.logical.EsRelation;
import org.elasticsearch.xpack.ql.plan.logical.Filter;
import org.elasticsearch.xpack.ql.plan.logical.Limit;
import org.elasticsearch.xpack.ql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.ql.plan.logical.OrderBy;
import org.elasticsearch.xpack.ql.plan.logical.Project;
import org.elasticsearch.xpack.ql.type.DataType;
import org.elasticsearch.xpack.ql.type.EsField;
import org.junit.BeforeClass;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.L;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.as;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.emptySource;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.loadMapping;
import static org.elasticsearch.xpack.ql.TestUtils.greaterThanOf;
import static org.elasticsearch.xpack.ql.TestUtils.greaterThanOrEqualOf;
import static org.elasticsearch.xpack.ql.TestUtils.lessThanOf;
import static org.elasticsearch.xpack.ql.TestUtils.relation;
import static org.elasticsearch.xpack.ql.tree.Source.EMPTY;
import static org.elasticsearch.xpack.ql.type.DataTypes.INTEGER;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class LogicalPlanOptimizerTests extends ESTestCase {

    private static final Literal ONE = L(1);
    private static final Literal TWO = L(2);
    private static final Literal THREE = L(3);

    private static EsqlParser parser;
    private static Analyzer analyzer;
    private static LogicalPlanOptimizer logicalOptimizer;
    private static Map<String, EsField> mapping;

    @BeforeClass
    public static void init() {
        parser = new EsqlParser();

        mapping = loadMapping("mapping-basic.json");
        EsIndex test = new EsIndex("test", mapping);
        IndexResolution getIndexResult = IndexResolution.valid(test);
        logicalOptimizer = new LogicalPlanOptimizer();
        analyzer = new Analyzer(new AnalyzerContext(EsqlTestUtils.TEST_CFG, new EsqlFunctionRegistry(), getIndexResult), new Verifier());
    }

    public void testCombineProjections() {
        var plan = plan("""
            from test
            | project emp_no, *name, salary
            | project last_name
            """);

        var project = as(plan, Project.class);
        assertThat(Expressions.names(project.projections()), contains("last_name"));
        var limit = as(project.child(), Limit.class);
        var relation = as(limit.child(), EsRelation.class);
    }

    public void testCombineProjectionWithFilterInBetween() {
        var plan = plan("""
            from test
            | project *name, salary
            | where salary > 10
            | project last_name
            """);

        var project = as(plan, Project.class);
        assertThat(Expressions.names(project.projections()), contains("last_name"));
    }

    public void testCombineProjectionWhilePreservingAlias() {
        var plan = plan("""
            from test
            | project x = first_name, salary
            | where salary > 10
            | project y = x
            """);

        var project = as(plan, Project.class);
        assertThat(Expressions.names(project.projections()), contains("y"));
        var p = project.projections().get(0);
        var alias = as(p, Alias.class);
        assertThat(Expressions.name(alias.child()), containsString("first_name"));
    }

    public void testCombineProjectionWithAggregation() {
        var plan = plan("""
            from test
            | stats avg(salary) by last_name, first_name
            """);

        var limit = as(plan, Limit.class);
        var agg = as(limit.child(), Aggregate.class);
        assertThat(Expressions.names(agg.aggregates()), contains("avg(salary)", "last_name", "first_name"));
        assertThat(Expressions.names(agg.groupings()), contains("last_name", "first_name"));
    }

    public void testCombineLimits() {
        var limitValues = new int[] { randomIntBetween(10, 99), randomIntBetween(100, 1000) };
        var firstLimit = randomBoolean() ? 0 : 1;
        var secondLimit = firstLimit == 0 ? 1 : 0;
        var oneLimit = new Limit(EMPTY, L(limitValues[firstLimit]), emptySource());
        var anotherLimit = new Limit(EMPTY, L(limitValues[secondLimit]), oneLimit);
        assertEquals(
            new Limit(EMPTY, L(Math.min(limitValues[0], limitValues[1])), emptySource()),
            new LogicalPlanOptimizer.PushDownAndCombineLimits().rule(anotherLimit)
        );
    }

    public void testMultipleCombineLimits() {
        var numberOfLimits = randomIntBetween(3, 10);
        var minimum = randomIntBetween(10, 99);
        var limitWithMinimum = randomIntBetween(0, numberOfLimits - 1);

        var plan = emptySource();
        for (int i = 0; i < numberOfLimits; i++) {
            var value = i == limitWithMinimum ? minimum : randomIntBetween(100, 1000);
            plan = new Limit(EMPTY, L(value), plan);
        }
        assertEquals(new Limit(EMPTY, L(minimum), emptySource()), new LogicalPlanOptimizer().optimize(plan));
    }

    public void testCombineFilters() {
        EsRelation relation = relation();
        GreaterThan conditionA = greaterThanOf(getFieldAttribute("a"), ONE);
        LessThan conditionB = lessThanOf(getFieldAttribute("b"), TWO);

        Filter fa = new Filter(EMPTY, relation, conditionA);
        Filter fb = new Filter(EMPTY, fa, conditionB);

        assertEquals(
            new Filter(EMPTY, relation, new And(EMPTY, conditionA, conditionB)),
            new LogicalPlanOptimizer.PushDownAndCombineFilters().apply(fb)
        );
    }

    public void testPushDownFilter() {
        EsRelation relation = relation();
        GreaterThan conditionA = greaterThanOf(getFieldAttribute("a"), ONE);
        LessThan conditionB = lessThanOf(getFieldAttribute("b"), TWO);

        Filter fa = new Filter(EMPTY, relation, conditionA);
        List<FieldAttribute> projections = singletonList(getFieldAttribute("b"));
        Project project = new ProjectReorderRenameRemove(EMPTY, fa, projections, emptyList());
        Filter fb = new Filter(EMPTY, project, conditionB);

        Filter combinedFilter = new Filter(EMPTY, relation, new And(EMPTY, conditionA, conditionB));
        assertEquals(
            new ProjectReorderRenameRemove(EMPTY, combinedFilter, projections, emptyList()),
            new LogicalPlanOptimizer.PushDownAndCombineFilters().apply(fb)
        );
    }

    // from ... | where a > 1 | stats count(1) by b | where count(1) >= 3 and b < 2
    // => ... | where a > 1 and b < 2 | stats count(1) by b | where count(1) >= 3
    public void testSelectivelyPushDownFilterPastFunctionAgg() {
        EsRelation relation = relation();
        GreaterThan conditionA = greaterThanOf(getFieldAttribute("a"), ONE);
        LessThan conditionB = lessThanOf(getFieldAttribute("b"), TWO);
        GreaterThanOrEqual aggregateCondition = greaterThanOrEqualOf(new Count(EMPTY, ONE, false), THREE);

        Filter fa = new Filter(EMPTY, relation, conditionA);
        // invalid aggregate but that's fine cause its properties are not used by this rule
        Aggregate aggregate = new Aggregate(EMPTY, fa, singletonList(getFieldAttribute("b")), emptyList());
        Filter fb = new Filter(EMPTY, aggregate, new And(EMPTY, aggregateCondition, conditionB));

        // expected
        Filter expected = new Filter(
            EMPTY,
            new Aggregate(
                EMPTY,
                new Filter(EMPTY, relation, new And(EMPTY, conditionA, conditionB)),
                singletonList(getFieldAttribute("b")),
                emptyList()
            ),
            aggregateCondition
        );
        assertEquals(expected, new LogicalPlanOptimizer.PushDownAndCombineFilters().apply(fb));
    }

    public void testSelectivelyPushDownFilterPastRefAgg() {
        // expected plan: "from test | where emp_no > 1 and emp_no < 3 | stats x = count(1) by emp_no | where x > 7"
        LogicalPlan plan = optimizedPlan("""
            from test
            | where emp_no > 1
            | stats x = count(1) by emp_no
            | where x + 2 > 9
            | where emp_no < 3""");
        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);

        assertTrue(filter.condition() instanceof GreaterThan);
        var gt = (GreaterThan) filter.condition();
        assertTrue(gt.left() instanceof ReferenceAttribute);
        var refAttr = (ReferenceAttribute) gt.left();
        assertEquals("x", refAttr.name());
        assertEquals(L(7), gt.right());

        var agg = as(filter.child(), Aggregate.class);

        filter = as(agg.child(), Filter.class);
        assertTrue(filter.condition() instanceof And);
        var and = (And) filter.condition();
        assertTrue(and.left() instanceof GreaterThan);
        gt = (GreaterThan) and.left();
        assertTrue(gt.left() instanceof FieldAttribute);
        assertEquals("emp_no", ((FieldAttribute) gt.left()).name());
        assertTrue(and.right() instanceof LessThan);
        var lt = (LessThan) and.right();
        assertTrue(lt.left() instanceof FieldAttribute);
        assertEquals("emp_no", ((FieldAttribute) lt.left()).name());

        assertTrue(filter.child() instanceof EsRelation);
    }

    public void testNoPushDownOrFilterPastAgg() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | stats x = count(1) by emp_no
            | where emp_no < 3 or x > 9""");
        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);

        assertTrue(filter.condition() instanceof Or);
        var or = (Or) filter.condition();
        assertTrue(or.left() instanceof LessThan);
        assertTrue(or.right() instanceof GreaterThan);

        var stats = as(filter.child(), Aggregate.class);
        assertTrue(stats.child() instanceof EsRelation);
    }

    public void testSelectivePushDownComplexFilterPastAgg() {
        // expected plan: from test | emp_no > 0 | stats x = count(1) by emp_no | where emp_no < 3 or x > 9
        LogicalPlan plan = optimizedPlan("""
            from test
            | stats x = count(1) by emp_no
            | where (emp_no < 3 or x > 9) and emp_no > 0""");
        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);

        assertTrue(filter.condition() instanceof Or);
        var or = (Or) filter.condition();
        assertTrue(or.left() instanceof LessThan);
        assertTrue(or.right() instanceof GreaterThan);

        var stats = as(filter.child(), Aggregate.class);
        filter = as(stats.child(), Filter.class);
        assertTrue(filter.condition() instanceof GreaterThan);
        var gt = (GreaterThan) filter.condition();
        assertTrue(gt.left() instanceof FieldAttribute);
        assertEquals("emp_no", ((FieldAttribute) gt.left()).name());
        assertEquals(L(0), gt.right());

        assertTrue(filter.child() instanceof EsRelation);
    }

    public void testSelectivelyPushDownFilterPastEval() {
        // expected plan: "from test | where emp_no > 1 and emp_no < 3 | eval x = emp_no + 1 | where x < 7"
        LogicalPlan plan = optimizedPlan("""
            from test
            | where emp_no > 1
            | eval x = emp_no + 1
            | where x + 2 < 9
            | where emp_no < 3""");
        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);

        assertTrue(filter.condition() instanceof LessThan);
        var lt = (LessThan) filter.condition();
        assertTrue(lt.left() instanceof ReferenceAttribute);
        var refAttr = (ReferenceAttribute) lt.left();
        assertEquals("x", refAttr.name());
        assertEquals(L(7), lt.right());

        var eval = as(filter.child(), Eval.class);
        assertEquals(1, eval.fields().size());
        assertTrue(eval.fields().get(0) instanceof Alias);
        assertEquals("x", (eval.fields().get(0)).name());

        filter = as(eval.child(), Filter.class);
        assertTrue(filter.condition() instanceof And);
        var and = (And) filter.condition();
        assertTrue(and.left() instanceof GreaterThan);
        var gt = (GreaterThan) and.left();
        assertTrue(gt.left() instanceof FieldAttribute);
        assertEquals("emp_no", ((FieldAttribute) gt.left()).name());
        assertTrue(and.right() instanceof LessThan);
        lt = (LessThan) and.right();
        assertTrue(lt.left() instanceof FieldAttribute);
        assertEquals("emp_no", ((FieldAttribute) lt.left()).name());

        assertTrue(filter.child() instanceof EsRelation);
    }

    public void testNoPushDownOrFilterPastLimit() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | limit 3
            | where emp_no < 3 or salary > 9""");
        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);

        assertTrue(filter.condition() instanceof Or);
        var or = (Or) filter.condition();
        assertTrue(or.left() instanceof LessThan);
        assertTrue(or.right() instanceof GreaterThan);

        var limit2 = as(filter.child(), Limit.class);
        assertTrue(limit2.child() instanceof EsRelation);
    }

    public void testPushDownFilterPastProject() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | project x = emp_no
            | where x > 10""");

        var project = as(plan, Project.class);
        var limit = as(project.child(), Limit.class);
        var filter = as(limit.child(), Filter.class);
        var attr = filter.condition().collect(Attribute.class::isInstance).stream().findFirst().get();
        assertThat(as(attr, FieldAttribute.class).name(), is("emp_no"));
    }

    public void testPushDownEvalPastProject() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | project x = emp_no
            | eval y = x * 2""");

        var project = as(plan, Project.class);
        var eval = as(project.child(), Eval.class);
        assertThat(
            eval.fields(),
            contains(
                new Alias(
                    EMPTY,
                    "y",
                    new Mul(EMPTY, new FieldAttribute(EMPTY, "emp_no", mapping.get("emp_no")), new Literal(EMPTY, 2, INTEGER))
                )
            )
        );
    }

    public void testPushDownFilterPastProjectUsingEval() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | eval y = emp_no + 1
            | project x = y
            | where x > 10""");

        var project = as(plan, Project.class);
        var limit = as(project.child(), Limit.class);
        var filter = as(limit.child(), Filter.class);
        var attr = filter.condition().collect(Attribute.class::isInstance).stream().findFirst().get();
        assertThat(as(attr, ReferenceAttribute.class).name(), is("y"));
        var eval = as(filter.child(), Eval.class);
        as(eval.child(), EsRelation.class);
    }

    public void testPushDownLimitPastEval() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | eval x = emp_no + 100
            | limit 10""");

        var eval = as(plan, Eval.class);
        as(eval.child(), Limit.class);
    }

    public void testPushDownLimitPastProject() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | project a = emp_no
            | limit 10""");

        var project = as(plan, Project.class);
        as(project.child(), Limit.class);
    }

    public void testDontPushDownLimitPastFilter() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | limit 100
            | where emp_no > 10
            | limit 10""");

        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        as(filter.child(), Limit.class);
    }

    public void testEliminateHigherLimitDueToDescendantLimit() throws Exception {
        LogicalPlan plan = optimizedPlan("""
            from test
            | limit 10
            | sort emp_no
            | where emp_no > 10
            | eval c = emp_no + 2
            | limit 100""");

        var limit = as(plan, Limit.class);
        var order = as(limit.child(), OrderBy.class);
        var eval = as(order.child(), Eval.class);
        var filter = as(eval.child(), Filter.class);
        as(filter.child(), Limit.class);
    }

    public void testDoNotEliminateHigherLimitDueToDescendantLimit() throws Exception {
        LogicalPlan plan = optimizedPlan("""
            from test
            | limit 10
            | where emp_no > 10
            | stats c = count(emp_no) by emp_no
            | limit 100""");

        var limit = as(plan, Limit.class);
        var agg = as(limit.child(), Aggregate.class);
        var filter = as(agg.child(), Filter.class);
        as(filter.child(), Limit.class);
    }

    public void testBasicNullFolding() {
        FoldNull rule = new FoldNull();
        assertNullLiteral(rule.rule(new Add(EMPTY, L(randomInt()), Literal.NULL)));
        assertNullLiteral(rule.rule(new Round(EMPTY, Literal.NULL, null)));
        assertNullLiteral(rule.rule(new DateFormat(EMPTY, Literal.NULL, Literal.NULL)));
        assertNullLiteral(rule.rule(new DateTrunc(EMPTY, Literal.NULL, Literal.NULL)));
        assertNullLiteral(rule.rule(new Substring(EMPTY, Literal.NULL, Literal.NULL, Literal.NULL)));
    }

    public void testPruneSortBeforeStats() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort emp_no
            | where emp_no > 10
            | stats x = avg(salary) by first_name""");

        var limit = as(plan, Limit.class);
        var stats = as(limit.child(), Aggregate.class);
        var filter = as(stats.child(), Filter.class);
        as(filter.child(), EsRelation.class);
    }

    public void testDontPruneSortWithLimitBeforeStats() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort emp_no
            | limit 100
            | stats x = avg(salary) by first_name""");

        var limit = as(plan, Limit.class);
        var stats = as(limit.child(), Aggregate.class);
        var limit2 = as(stats.child(), Limit.class);
        var orderBy = as(limit2.child(), OrderBy.class);
        as(orderBy.child(), EsRelation.class);
    }

    public void testCombineOrderBy() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort emp_no
            | sort salary""");

        var limit = as(plan, Limit.class);
        var orderBy = as(limit.child(), OrderBy.class);
        assertThat(orderBy.order().stream().map(o -> as(o.child(), NamedExpression.class).name()).toList(), contains("salary", "emp_no"));
        as(orderBy.child(), EsRelation.class);
    }

    public void testCombineOrderByThroughEval() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort emp_no
            | eval x = salary + 1
            | sort x""");

        var limit = as(plan, Limit.class);
        var orderBy = as(limit.child(), OrderBy.class);
        assertThat(orderBy.order().stream().map(o -> as(o.child(), NamedExpression.class).name()).toList(), contains("x", "emp_no"));
        var eval = as(orderBy.child(), Eval.class);
        as(eval.child(), EsRelation.class);
    }

    public void testCombineOrderByThroughEvalWithTwoDefs() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort emp_no
            | eval x = salary + 1, y = salary + 2
            | sort x""");

        var limit = as(plan, Limit.class);
        var orderBy = as(limit.child(), OrderBy.class);
        assertThat(orderBy.order().stream().map(o -> as(o.child(), NamedExpression.class).name()).toList(), contains("x", "emp_no"));
        var eval = as(orderBy.child(), Eval.class);
        assertThat(eval.fields().stream().map(NamedExpression::name).toList(), contains("x", "y"));
        as(eval.child(), EsRelation.class);
    }

    public void testCombineOrderByThroughProject() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort emp_no
            | project salary, emp_no
            | sort salary""");

        var project = as(plan, Project.class);
        var limit = as(project.child(), Limit.class);
        var orderBy = as(limit.child(), OrderBy.class);
        assertThat(orderBy.order().stream().map(o -> as(o.child(), NamedExpression.class).name()).toList(), contains("salary", "emp_no"));
        as(orderBy.child(), EsRelation.class);
    }

    public void testCombineOrderByThroughProjectAndEval() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort emp_no
            | project salary, en = emp_no
            | eval e = en * 2
            | sort salary""");

        var project = as(plan, Project.class);
        var limit = as(project.child(), Limit.class);
        var orderBy = as(limit.child(), OrderBy.class);
        assertThat(orderBy.order().stream().map(o -> as(o.child(), NamedExpression.class).name()).toList(), contains("salary", "emp_no"));
        as(orderBy.child(), Eval.class);
    }

    public void testCombineOrderByThroughProjectWithAlias() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort emp_no
            | project l = salary, emp_no
            | sort l""");

        var project = as(plan, Project.class);
        var limit = as(project.child(), Limit.class);
        var orderBy = as(limit.child(), OrderBy.class);
        assertThat(orderBy.order().stream().map(o -> as(o.child(), NamedExpression.class).name()).toList(), contains("salary", "emp_no"));
        as(orderBy.child(), EsRelation.class);
    }

    public void testCombineOrderByThroughFilter() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort emp_no
            | where emp_no > 10
            | sort salary""");

        var limit = as(plan, Limit.class);
        var orderBy = as(limit.child(), OrderBy.class);
        assertThat(orderBy.order().stream().map(o -> as(o.child(), NamedExpression.class).name()).toList(), contains("salary", "emp_no"));
        var filter = as(orderBy.child(), Filter.class);
        as(filter.child(), EsRelation.class);
    }

    public void testCombineLimitWithOrderByThroughFilterAndEval() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort salary
            | eval x = emp_no / 2
            | where x > 20
            | sort x
            | limit 10""");

        var limit = as(plan, Limit.class);
        var orderBy = as(limit.child(), OrderBy.class);
        var filter = as(orderBy.child(), Filter.class);
        var eval = as(filter.child(), Eval.class);
        as(eval.child(), EsRelation.class);
    }

    public void testCombineMultipleOrderByAndLimits() {
        // expected plan:
        // from test
        // | sort salary, emp_no
        // | limit 100
        // | where salary > 1
        // | sort emp_no, first_name
        // | project l = salary, emp_no, first_name
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort emp_no
            | project l = salary, emp_no, first_name
            | sort l
            | limit 100
            | sort first_name
            | where l > 1
            | sort emp_no""");

        var project = as(plan, Project.class);
        var limit = as(project.child(), Limit.class);
        var orderBy = as(limit.child(), OrderBy.class);
        assertThat(
            orderBy.order().stream().map(o -> as(o.child(), NamedExpression.class).name()).toList(),
            contains("emp_no", "first_name")
        );
        var filter = as(orderBy.child(), Filter.class);
        var limit2 = as(filter.child(), Limit.class);
        var orderBy2 = as(limit2.child(), OrderBy.class);
        assertThat(orderBy2.order().stream().map(o -> as(o.child(), NamedExpression.class).name()).toList(), contains("salary", "emp_no"));
        as(orderBy2.child(), EsRelation.class);
    }

    public void testPruneRedundantSortClauses() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | sort salary nulls last, emp_no desc nulls first
            | where salary > 2
            | eval e = emp_no * 2
            | project salary, emp_no, e
            | sort e, emp_no, salary desc, emp_no desc""");

        var project = as(plan, Project.class);
        var limit = as(project.child(), Limit.class);
        var orderBy = as(limit.child(), OrderBy.class);
        assertThat(
            orderBy.order(),
            contains(
                new Order(
                    EMPTY,
                    new ReferenceAttribute(EMPTY, "e", INTEGER, null, Nullability.TRUE, null, false),
                    Order.OrderDirection.ASC,
                    Order.NullsPosition.LAST
                ),
                new Order(
                    EMPTY,
                    new FieldAttribute(EMPTY, "emp_no", mapping.get("emp_no")),
                    Order.OrderDirection.ASC,
                    Order.NullsPosition.LAST
                ),
                new Order(
                    EMPTY,
                    new FieldAttribute(EMPTY, "salary", mapping.get("salary")),
                    Order.OrderDirection.DESC,
                    Order.NullsPosition.FIRST
                )
            )
        );
        assertThat(orderBy.child().collect(OrderBy.class::isInstance), is(emptyList()));
    }

    public void testPruneRedundantSortClausesUsingAlias() {
        LogicalPlan plan = optimizedPlan("""
            from test
            | project e = emp_no, emp_no
            | sort emp_no, e desc""");

        var project = as(plan, Project.class);
        var limit = as(project.child(), Limit.class);
        var orderBy = as(limit.child(), OrderBy.class);
        assertThat(
            orderBy.order(),
            contains(
                new Order(
                    EMPTY,
                    new FieldAttribute(EMPTY, "emp_no", mapping.get("emp_no")),
                    Order.OrderDirection.ASC,
                    Order.NullsPosition.LAST
                )
            )
        );
    }

    private LogicalPlan optimizedPlan(String query) {
        return logicalOptimizer.optimize(analyzer.analyze(parser.createStatement(query)));
    }

    private LogicalPlan plan(String query) {
        return logicalOptimizer.optimize(analyzer.analyze(parser.createStatement(query)));
    }

    private void assertNullLiteral(Expression expression) {
        assertEquals(Literal.class, expression.getClass());
        assertNull(expression.fold());
    }

    // TODO: move these from org.elasticsearch.xpack.ql.optimizer.OptimizerRulesTests to org.elasticsearch.xpack.ql.TestUtils
    private static FieldAttribute getFieldAttribute(String name) {
        return getFieldAttribute(name, INTEGER);
    }

    private static FieldAttribute getFieldAttribute(String name, DataType dataType) {
        return new FieldAttribute(EMPTY, name, new EsField(name + "f", dataType, emptyMap(), true));
    }
}
