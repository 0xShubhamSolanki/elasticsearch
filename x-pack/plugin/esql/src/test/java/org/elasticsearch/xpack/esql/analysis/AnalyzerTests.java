/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.analysis;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.expression.function.EsqlFunctionRegistry;
import org.elasticsearch.xpack.esql.parser.EsqlParser;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.ql.expression.Alias;
import org.elasticsearch.xpack.ql.expression.Attribute;
import org.elasticsearch.xpack.ql.expression.Expressions;
import org.elasticsearch.xpack.ql.expression.FieldAttribute;
import org.elasticsearch.xpack.ql.expression.Literal;
import org.elasticsearch.xpack.ql.expression.ReferenceAttribute;
import org.elasticsearch.xpack.ql.expression.UnresolvedAttribute;
import org.elasticsearch.xpack.ql.index.EsIndex;
import org.elasticsearch.xpack.ql.index.IndexResolution;
import org.elasticsearch.xpack.ql.plan.TableIdentifier;
import org.elasticsearch.xpack.ql.plan.logical.EsRelation;
import org.elasticsearch.xpack.ql.plan.logical.Limit;
import org.elasticsearch.xpack.ql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.ql.plan.logical.Project;
import org.elasticsearch.xpack.ql.plan.logical.UnresolvedRelation;
import org.elasticsearch.xpack.ql.type.DataTypes;
import org.elasticsearch.xpack.ql.type.TypesTests;

import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.as;
import static org.elasticsearch.xpack.ql.tree.Source.EMPTY;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;

public class AnalyzerTests extends ESTestCase {
    public void testIndexResolution() {
        EsIndex idx = new EsIndex("idx", Map.of());
        Analyzer analyzer = newAnalyzer(IndexResolution.valid(idx));
        var plan = analyzer.analyze(new UnresolvedRelation(EMPTY, new TableIdentifier(EMPTY, null, "idx"), null, false));
        var limit = as(plan, Limit.class);
        var project = as(limit.child(), Project.class);

        assertEquals(new EsRelation(EMPTY, idx, false), project.child());
    }

    public void testFailOnUnresolvedIndex() {
        Analyzer analyzer = newAnalyzer(IndexResolution.invalid("Unknown index [idx]"));

        VerificationException e = expectThrows(
            VerificationException.class,
            () -> analyzer.analyze(new UnresolvedRelation(EMPTY, new TableIdentifier(EMPTY, null, "idx"), null, false))
        );

        assertThat(e.getMessage(), containsString("Unknown index [idx]"));
    }

    public void testIndexWithClusterResolution() {
        EsIndex idx = new EsIndex("cluster:idx", Map.of());
        Analyzer analyzer = newAnalyzer(IndexResolution.valid(idx));

        var plan = analyzer.analyze(new UnresolvedRelation(EMPTY, new TableIdentifier(EMPTY, "cluster", "idx"), null, false));
        var limit = as(plan, Limit.class);
        var project = as(limit.child(), Project.class);

        assertEquals(new EsRelation(EMPTY, idx, false), project.child());
    }

    public void testAttributeResolution() {
        EsIndex idx = new EsIndex("idx", TypesTests.loadMapping("mapping-one-field.json"));
        Analyzer analyzer = newAnalyzer(IndexResolution.valid(idx));

        var plan = analyzer.analyze(
            new Eval(
                EMPTY,
                new UnresolvedRelation(EMPTY, new TableIdentifier(EMPTY, null, "idx"), null, false),
                List.of(new Alias(EMPTY, "e", new UnresolvedAttribute(EMPTY, "emp_no")))
            )
        );

        var limit = as(plan, Limit.class);
        var project = as(limit.child(), Project.class);
        var eval = as(project.child(), Eval.class);
        assertEquals(1, eval.fields().size());
        assertEquals(new Alias(EMPTY, "e", new FieldAttribute(EMPTY, "emp_no", idx.mapping().get("emp_no"))), eval.fields().get(0));

        assertEquals(2, eval.output().size());
        Attribute empNo = eval.output().get(0);
        assertEquals("emp_no", empNo.name());
        assertThat(empNo, instanceOf(FieldAttribute.class));
        Attribute e = eval.output().get(1);
        assertEquals("e", e.name());
        assertThat(e, instanceOf(ReferenceAttribute.class));
    }

    public void testAttributeResolutionOfChainedReferences() {
        Analyzer analyzer = newAnalyzer(loadMapping("mapping-one-field.json", "idx"));

        var plan = analyzer.analyze(
            new Eval(
                EMPTY,
                new Eval(
                    EMPTY,
                    new UnresolvedRelation(EMPTY, new TableIdentifier(EMPTY, null, "idx"), null, false),
                    List.of(new Alias(EMPTY, "e", new UnresolvedAttribute(EMPTY, "emp_no")))
                ),
                List.of(new Alias(EMPTY, "ee", new UnresolvedAttribute(EMPTY, "e")))
            )
        );

        var limit = as(plan, Limit.class);
        var project = as(limit.child(), Project.class);
        var eval = as(project.child(), Eval.class);

        assertEquals(1, eval.fields().size());
        Alias eeField = (Alias) eval.fields().get(0);
        assertEquals("ee", eeField.name());
        assertEquals("e", ((ReferenceAttribute) eeField.child()).name());

        assertEquals(3, eval.output().size());
        Attribute empNo = eval.output().get(0);
        assertEquals("emp_no", empNo.name());
        assertThat(empNo, instanceOf(FieldAttribute.class));
        Attribute e = eval.output().get(1);
        assertEquals("e", e.name());
        assertThat(e, instanceOf(ReferenceAttribute.class));
        Attribute ee = eval.output().get(2);
        assertEquals("ee", ee.name());
        assertThat(ee, instanceOf(ReferenceAttribute.class));
    }

    public void testRowAttributeResolution() {
        EsIndex idx = new EsIndex("idx", Map.of());
        Analyzer analyzer = newAnalyzer(IndexResolution.valid(idx));

        var plan = analyzer.analyze(
            new Eval(
                EMPTY,
                new Row(EMPTY, List.of(new Alias(EMPTY, "emp_no", new Literal(EMPTY, 1, DataTypes.INTEGER)))),
                List.of(new Alias(EMPTY, "e", new UnresolvedAttribute(EMPTY, "emp_no")))
            )
        );

        var limit = as(plan, Limit.class);
        var project = as(limit.child(), Project.class);
        var eval = as(project.child(), Eval.class);
        assertEquals(1, eval.fields().size());
        assertEquals(new Alias(EMPTY, "e", new ReferenceAttribute(EMPTY, "emp_no", DataTypes.INTEGER)), eval.fields().get(0));

        assertEquals(2, eval.output().size());
        Attribute empNo = eval.output().get(0);
        assertEquals("emp_no", empNo.name());
        assertThat(empNo, instanceOf(ReferenceAttribute.class));
        Attribute e = eval.output().get(1);
        assertEquals("e", e.name());
        assertThat(e, instanceOf(ReferenceAttribute.class));

        Row row = (Row) eval.child();
        ReferenceAttribute rowEmpNo = (ReferenceAttribute) row.output().get(0);
        assertEquals(rowEmpNo.id(), empNo.id());
    }

    public void testUnresolvableAttribute() {
        Analyzer analyzer = newAnalyzer(loadMapping("mapping-one-field.json", "idx"));

        VerificationException ve = expectThrows(
            VerificationException.class,
            () -> analyzer.analyze(
                new Eval(
                    EMPTY,
                    new UnresolvedRelation(EMPTY, new TableIdentifier(EMPTY, null, "idx"), null, false),
                    List.of(new Alias(EMPTY, "e", new UnresolvedAttribute(EMPTY, "emp_nos")))
                )
            )
        );

        assertThat(ve.getMessage(), containsString("Unknown column [emp_nos], did you mean [emp_no]?"));
    }

    public void testProjectBasic() {
        assertProjection("""
            from test
            | project first_name
            """, "first_name");
    }

    public void testProjectBasicPattern() {
        assertProjection("""
            from test
            | project first*name
            """, "first_name");
    }

    public void testProjectIncludePattern() {
        assertProjection("""
            from test
            | project *name
            """, "first_name", "last_name");
    }

    public void testProjectIncludeMultiStarPattern() {
        assertProjection("""
            from test
            | project *t*name
            """, "first_name", "last_name");
    }

    public void testProjectStar() {
        assertProjection("""
            from test
            | project *
            """, "_meta_field", "emp_no", "first_name", "last_name", "salary");
    }

    public void testNoProjection() {
        assertProjection("""
            from test
            """, "_meta_field", "emp_no", "first_name", "last_name", "salary");
    }

    public void testProjectOrder() {
        assertProjection("""
            from test
            | project first_name, *, last_name
            """, "first_name", "_meta_field", "emp_no", "salary", "last_name");
    }

    public void testProjectExcludeName() {
        assertProjection("""
            from test
            | project *name, -first_name
            """, "last_name");
    }

    public void testProjectKeepAndExcludeName() {
        assertProjection("""
            from test
            | project last_name, -first_name
            """, "last_name");
    }

    public void testProjectExcludePattern() {
        assertProjection("""
            from test
            | project *, -*_name
            """, "_meta_field", "emp_no", "salary");
    }

    public void testProjectExcludeNoStarPattern() {
        assertProjection("""
            from test
            | project -*_name
            """, "_meta_field", "emp_no", "salary");
    }

    public void testProjectOrderPatternWithRest() {
        assertProjection("""
            from test
            | project *name, *, emp_no
            """, "first_name", "last_name", "_meta_field", "salary", "emp_no");
    }

    public void testProjectExcludePatternAndKeepOthers() {
        assertProjection("""
            from test
            | project -l*, first_name, salary
            """, "first_name", "salary");
    }

    public void testErrorOnNoMatchingPatternInclusion() {
        var e = expectThrows(VerificationException.class, () -> analyze("""
            from test
            | project *nonExisting
            """));
        assertThat(e.getMessage(), containsString("No match found for [*nonExisting]"));
    }

    public void testErrorOnNoMatchingPatternExclusion() {
        var e = expectThrows(VerificationException.class, () -> analyze("""
            from test
            | project -*nonExisting
            """));
        assertThat(e.getMessage(), containsString("No match found for [*nonExisting]"));
    }

    public void testIncludeUnsupportedFieldExplicit() {
        verifyUnsupported("""
            from test
            | project unsupported
            """, "Unknown column [unsupported]");
    }

    public void testIncludeUnsupportedFieldPattern() {
        var e = expectThrows(VerificationException.class, () -> analyze("""
            from test
            | project un*
            """));
        assertThat(e.getMessage(), containsString("No match found for [un*]"));
    }

    public void testExcludeUnsupportedFieldExplicit() {
        verifyUnsupported("""
            from test
            | project -unsupported
            """, "Unknown column [unsupported]");
    }

    public void testExcludeMultipleUnsupportedFieldsExplicitly() {
        verifyUnsupported("""
            from test
            | project -languages, -gender
            """, "Unknown column [languages]");
    }

    public void testExcludePatternUnsupportedFields() {
        assertProjection("""
            from test
            | project -*ala*
            """, "_meta_field", "emp_no", "first_name", "last_name");
    }

    public void testExcludeUnsupportedPattern() {
        verifyUnsupported("""
            from test
            | project -un*
            """, "No match found for [un*]");
    }

    public void testUnsupportedFieldUsedExplicitly() {
        verifyUnsupported("""
            from test
            | project foo_type
            """, "Unknown column [foo_type]");
    }

    public void testUnsupportedDottedFieldUsedExplicitly() {
        verifyUnsupported("""
            from test
            | project some.string
            """, "Unknown column [some.string]");
    }

    public void testUnsupportedFieldUsedExplicitly2() {
        verifyUnsupported("""
            from test
            | project keyword, point
            """, "Unknown column [point]");
    }

    public void testCantFilterAfterProjectedAway() {
        verifyUnsupported("""
            from test
            | stats c = avg(float) by int
            | project -int
            | where int > 0
            """, "Unknown column [int]");
    }

    public void testProjectAggGroupsRefs() {
        assertProjection("""
            from test
            | stats c = count(salary) by last_name
            | eval d = c + 1
            | project d, last_name
            """, "d", "last_name");
    }

    public void testExplicitProjectAndLimit() {
        var plan = analyze("""
            from test
            """);
        var limit = as(plan, Limit.class);
        var project = as(limit.child(), Project.class);
        as(project.child(), EsRelation.class);
    }

    private void verifyUnsupported(String query, String errorMessage) {
        var e = expectThrows(VerificationException.class, () -> analyze(query, "mapping-multi-field-variation.json"));
        assertThat(e.getMessage(), containsString(errorMessage));
    }

    private void assertProjection(String query, String... names) {
        var plan = analyze(query);
        var limit = as(plan, Limit.class);
        var project = as(limit.child(), Project.class);
        assertThat(Expressions.names(project.projections()), contains(names));
    }

    private Analyzer newAnalyzer(IndexResolution indexResolution) {
        return new Analyzer(new AnalyzerContext(EsqlTestUtils.TEST_CFG, new EsqlFunctionRegistry(), indexResolution), new Verifier());
    }

    private IndexResolution loadMapping(String resource, String indexName) {
        EsIndex test = new EsIndex(indexName, EsqlTestUtils.loadMapping(resource));
        return IndexResolution.valid(test);
    }

    private LogicalPlan analyze(String query) {
        return analyze(query, "mapping-basic.json");
    }

    private LogicalPlan analyze(String query, String mapping) {
        return newAnalyzer(loadMapping(mapping, "test")).analyze(new EsqlParser().createStatement(query));
    }
}
