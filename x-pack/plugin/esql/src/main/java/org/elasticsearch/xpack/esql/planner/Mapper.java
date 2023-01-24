/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.planner;

import org.elasticsearch.compute.ann.Experimental;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.esql.plan.physical.AggregateExec;
import org.elasticsearch.xpack.esql.plan.physical.EsQueryExec;
import org.elasticsearch.xpack.esql.plan.physical.EvalExec;
import org.elasticsearch.xpack.esql.plan.physical.FilterExec;
import org.elasticsearch.xpack.esql.plan.physical.LimitExec;
import org.elasticsearch.xpack.esql.plan.physical.OrderExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;
import org.elasticsearch.xpack.esql.plan.physical.ProjectExec;
import org.elasticsearch.xpack.esql.plan.physical.RowExec;
import org.elasticsearch.xpack.esql.plan.physical.TopNExec;
import org.elasticsearch.xpack.ql.plan.logical.Aggregate;
import org.elasticsearch.xpack.ql.plan.logical.EsRelation;
import org.elasticsearch.xpack.ql.plan.logical.Filter;
import org.elasticsearch.xpack.ql.plan.logical.Limit;
import org.elasticsearch.xpack.ql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.ql.plan.logical.OrderBy;
import org.elasticsearch.xpack.ql.plan.logical.Project;

@Experimental
public class Mapper {

    public PhysicalPlan map(LogicalPlan p) {
        if (p instanceof EsRelation esRelation) {
            // TODO: Fold with filter
            return new EsQueryExec(esRelation.source(), esRelation.index(), null);
        }

        if (p instanceof Filter f) {
            return new FilterExec(f.source(), map(f.child()), f.condition());
        }

        if (p instanceof Project pj) {
            return new ProjectExec(pj.source(), map(pj.child()), pj.projections());
        }

        if (p instanceof OrderBy o) {
            return map(o, map(o.child()));
        }

        if (p instanceof Limit limit) {
            return map(limit, map(limit.child()));
        }

        if (p instanceof Aggregate aggregate) {
            return map(aggregate, map(aggregate.child()));
        }

        if (p instanceof Eval eval) {
            return new EvalExec(eval.source(), map(eval.child()), eval.fields());
        }

        if (p instanceof Row row) {
            return new RowExec(row.source(), row.fields());
        }

        throw new UnsupportedOperationException(p.nodeName());
    }

    private PhysicalPlan map(Aggregate aggregate, PhysicalPlan child) {
        var partial = new AggregateExec(
            aggregate.source(),
            child,
            aggregate.groupings(),
            aggregate.aggregates(),
            AggregateExec.Mode.PARTIAL
        );

        return new AggregateExec(aggregate.source(), partial, aggregate.groupings(), aggregate.aggregates(), AggregateExec.Mode.FINAL);
    }

    private PhysicalPlan map(Limit limit, PhysicalPlan child) {
        // typically this would be done in the optimizer however this complicates matching a bit due to limit being in two nodes
        // since it's a simple match, handle this case directly in the mapper
        if (child instanceof OrderExec order) {
            return new TopNExec(limit.source(), order.child(), order.order(), limit.limit());
        }

        return new LimitExec(limit.source(), child, limit.limit());
    }

    private PhysicalPlan map(OrderBy o, PhysicalPlan child) {
        return new OrderExec(o.source(), map(o.child()), o.order());
    }
}
