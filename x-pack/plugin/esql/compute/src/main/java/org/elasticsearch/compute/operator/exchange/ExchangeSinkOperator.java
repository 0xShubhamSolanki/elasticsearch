/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.exchange;

import org.elasticsearch.action.support.ListenableActionFuture;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.compute.ann.Experimental;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.Operator;
import org.elasticsearch.compute.operator.SinkOperator;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;

/**
 * Sink operator implementation that pushes data to an {@link ExchangeSink}
 */
@Experimental
public class ExchangeSinkOperator extends SinkOperator {

    private final ExchangeSink sink;

    private ListenableActionFuture<Void> isBlocked = NOT_BLOCKED;

    private int pagesAccepted;

    public record ExchangeSinkOperatorFactory(Exchange ex) implements SinkOperatorFactory {
        @Override
        public SinkOperator get() {
            return new ExchangeSinkOperator(ex.createSink());
        }

        @Override
        public String describe() {
            return "ExchangeSinkOperator";
        }
    }

    public ExchangeSinkOperator(ExchangeSink sink) {
        this.sink = sink;
    }

    @Override
    public boolean isFinished() {
        return sink.isFinished();
    }

    @Override
    public void finish() {
        sink.finish();
    }

    @Override
    public ListenableActionFuture<Void> isBlocked() {
        if (isBlocked.isDone()) {
            isBlocked = sink.waitForWriting();
            if (isBlocked.isDone()) {
                isBlocked = NOT_BLOCKED;
            }
        }
        return isBlocked;
    }

    @Override
    public boolean needsInput() {
        return isFinished() == false && isBlocked().isDone();
    }

    @Override
    public void addInput(Page page) {
        pagesAccepted++;
        sink.addPage(page);
    }

    @Override
    public void close() {
        finish();
    }

    @Override
    public String toString() {
        return "ExchangeSinkOperator";
    }

    @Override
    public Status status() {
        return new Status(this);
    }

    public static class Status implements Operator.Status {
        public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
            Operator.Status.class,
            "exchange_sink",
            Status::new
        );

        private final int pagesAccepted;

        private Status(ExchangeSinkOperator operator) {
            pagesAccepted = operator.pagesAccepted;
        }

        private Status(StreamInput in) throws IOException {
            pagesAccepted = in.readVInt();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVInt(pagesAccepted);
        }

        @Override
        public String getWriteableName() {
            return ENTRY.name;
        }

        public int pagesAccepted() {
            return pagesAccepted;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("pages_accepted", pagesAccepted);
            return builder.endObject();
        }
    }
}
