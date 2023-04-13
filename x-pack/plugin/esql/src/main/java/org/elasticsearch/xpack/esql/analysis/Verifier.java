/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.analysis;

import org.elasticsearch.xpack.esql.expression.function.UnsupportedAttribute;
import org.elasticsearch.xpack.esql.plan.logical.RegexExtract;
import org.elasticsearch.xpack.ql.capabilities.Unresolvable;
import org.elasticsearch.xpack.ql.common.Failure;
import org.elasticsearch.xpack.ql.expression.Alias;
import org.elasticsearch.xpack.ql.expression.Expression;
import org.elasticsearch.xpack.ql.expression.FieldAttribute;
import org.elasticsearch.xpack.ql.expression.Literal;
import org.elasticsearch.xpack.ql.expression.ReferenceAttribute;
import org.elasticsearch.xpack.ql.expression.TypeResolutions;
import org.elasticsearch.xpack.ql.expression.function.aggregate.AggregateFunction;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.BinaryComparison;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.NotEquals;
import org.elasticsearch.xpack.ql.plan.logical.Aggregate;
import org.elasticsearch.xpack.ql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.ql.plan.logical.Project;
import org.elasticsearch.xpack.ql.type.DataType;
import org.elasticsearch.xpack.ql.type.DataTypes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.elasticsearch.xpack.ql.common.Failure.fail;
import static org.elasticsearch.xpack.ql.expression.TypeResolutions.ParamOrdinal.FIRST;

public class Verifier {
    Collection<Failure> verify(LogicalPlan plan) {
        Set<Failure> failures = new LinkedHashSet<>();

        // quick verification for unresolved attributes
        plan.forEachUp(p -> {
            // if the children are unresolved, so will this node; counting it will only add noise
            if (p.childrenResolved() == false) {
                return;
            }

            if (p instanceof Unresolvable u) {
                failures.add(fail(p, u.unresolvedMessage()));
            }
            // p is resolved, skip
            else if (p.resolved()) {
                return;
            }
            p.forEachExpression(e -> {
                // everything is fine, skip expression
                if (e.resolved()) {
                    return;
                }

                e.forEachUp(ae -> {
                    // we're only interested in the children
                    if (ae.childrenResolved() == false) {
                        return;
                    }

                    if (ae instanceof Unresolvable u) {
                        // special handling for Project and unsupported types
                        if (p instanceof Project == false || u instanceof UnsupportedAttribute == false) {
                            failures.add(fail(ae, u.unresolvedMessage()));
                        }
                    }
                    if (ae.typeResolved().unresolved()) {
                        failures.add(fail(ae, ae.typeResolved().message()));
                    }
                });
            });
        });

        // in case of failures bail-out as all other checks will be redundant
        if (failures.isEmpty() == false) {
            return failures;
        }

        // Concrete verifications
        plan.forEachDown(p -> {
            if (p instanceof Aggregate agg) {
                agg.aggregates().forEach(e -> {
                    var exp = e instanceof Alias ? ((Alias) e).child() : e;
                    if (exp instanceof AggregateFunction aggFunc) {
                        aggFunc.arguments().forEach(a -> {
                            // TODO: allow an expression?
                            if ((a instanceof FieldAttribute || a instanceof ReferenceAttribute || a instanceof Literal) == false) {
                                failures.add(
                                    fail(
                                        e,
                                        "aggregate function's parameters must be an attribute or literal; found ["
                                            + a.sourceText()
                                            + "] of type ["
                                            + a.nodeName()
                                            + "]"
                                    )
                                );
                            }
                        });
                    } else if (agg.groupings().contains(exp) == false) { // TODO: allow an expression?
                        failures.add(
                            fail(
                                exp,
                                "expected an aggregate function or group but got ["
                                    + exp.sourceText()
                                    + "] of type ["
                                    + exp.nodeName()
                                    + "]"
                            )
                        );
                    }
                });
            }
            if (p instanceof RegexExtract re) {
                Expression expr = re.input();
                DataType type = expr.dataType();
                if (type != DataTypes.KEYWORD) {
                    failures.add(
                        fail(
                            expr,
                            "{} only supports KEYWORD values, found expression [{}] type [{}]",
                            re.getClass().getSimpleName(),
                            expr.sourceText(),
                            type
                        )
                    );
                }
            }
            p.forEachExpression(e -> {
                if (e instanceof BinaryComparison bc) {
                    Failure f = validateBinaryComparison(bc);
                    if (f != null) {
                        failures.add(f);
                    }
                }
            });
        });

        return failures;
    }

    /**
     * Limit QL's comparisons to types we support.
     */
    public static Failure validateBinaryComparison(BinaryComparison bc) {
        if (bc.left().dataType().isNumeric()) {
            if (false == bc.right().dataType().isNumeric()) {
                return fail(
                    bc,
                    "first argument of [{}] is [numeric] so second argument must also be [numeric] but was [{}]",
                    bc.sourceText(),
                    bc.right().dataType().typeName()
                );
            }
            return null;
        }

        List<DataType> allowed = new ArrayList<>();
        allowed.add(DataTypes.KEYWORD);
        allowed.add(DataTypes.IP);
        allowed.add(DataTypes.DATETIME);
        if (bc instanceof Equals || bc instanceof NotEquals) {
            allowed.add(DataTypes.BOOLEAN);
        }
        Expression.TypeResolution r = TypeResolutions.isType(
            bc.left(),
            t -> allowed.contains(t),
            bc.sourceText(),
            FIRST,
            Stream.concat(Stream.of("numeric"), allowed.stream().map(a -> a.typeName())).toArray(String[]::new)
        );
        if (false == r.resolved()) {
            return fail(bc, r.message());
        }
        if (bc.left().dataType() != bc.right().dataType()) {
            return fail(
                bc,
                "first argument of [{}] is [{}] so second argument must also be [{}] but was [{}]",
                bc.sourceText(),
                bc.left().dataType().typeName(),
                bc.left().dataType().typeName(),
                bc.right().dataType().typeName()
            );
        }
        return null;
    }
}
