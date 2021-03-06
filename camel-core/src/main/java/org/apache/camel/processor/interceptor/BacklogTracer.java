/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.processor.interceptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.camel.CamelContext;
import org.apache.camel.Processor;
import org.apache.camel.api.management.mbean.BacklogTracerEventMessage;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.ProcessorDefinitionHelper;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.RouteDefinitionHelper;
import org.apache.camel.spi.InterceptStrategy;
import org.apache.camel.spi.NodeIdFactory;
import org.apache.camel.support.ServiceSupport;

/**
 * A tracer used for message tracing, storing a copy of the message details in a backlog.
 * <p/>
 * This tracer allows to store message tracers per node in the Camel routes. The tracers
 * is stored in a backlog queue (FIFO based) which allows to pull the traced messages on demand.
 */
public class BacklogTracer extends ServiceSupport implements InterceptStrategy {

    private final CamelContext camelContext;
    private boolean enabled;
    private final AtomicLong traceCounter = new AtomicLong(0);
    // use a queue with a upper limit to avoid storing too many messages
    private final Queue<DefaultBacklogTracerEventMessage> queue = new ArrayBlockingQueue<DefaultBacklogTracerEventMessage>(1000);
    // how many of the last messages per node to keep in the backlog
    private int backlogSize = 10;
    // remember the processors we are tracing, which we need later
    private final Set<ProcessorDefinition<?>> processors = new HashSet<ProcessorDefinition<?>>();

    public BacklogTracer(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    @Override
    public Processor wrapProcessorInInterceptors(CamelContext context, ProcessorDefinition<?> definition, Processor target, Processor nextTarget) throws Exception {
        // is this the first output from a route, as we want to know this so we can do special logic in first
        boolean first = false;
        RouteDefinition route = ProcessorDefinitionHelper.getRoute(definition);
        if (route != null && !route.getOutputs().isEmpty()) {
            first = route.getOutputs().get(0) == definition;
        }

        processors.add(definition);
        return new BacklogTracerInterceptor(queue, target, definition, route, first, this);
    }

    /**
     * Creates a new backlog tracer.
     *
     * @param context Camel context
     * @return a new backlog tracer
     */
    public static BacklogTracer createTracer(CamelContext context) {
        BacklogTracer tracer = new BacklogTracer(context);
        return tracer;
    }

    /**
     * A helper method to return the BacklogTracer instance if one is enabled
     *
     * @return the backlog tracer or null if none can be found
     */
    public static BacklogTracer getBacklogTracer(CamelContext context) {
        List<InterceptStrategy> list = context.getInterceptStrategies();
        for (InterceptStrategy interceptStrategy : list) {
            if (interceptStrategy instanceof BacklogTracer) {
                return (BacklogTracer) interceptStrategy;
            }
        }
        return null;
    }

    /**
     * Whether or not to trace the given processor definition.
     *
     * @param definition the processor definition
     * @return <tt>true</tt> to trace, <tt>false</tt> to skip tracing
     */
    public boolean shouldTrace(ProcessorDefinition<?> definition) {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        // okay tracer is enabled then force auto assigning ids
        if (enabled) {
            forceAutoAssigningIds();
        }
        this.enabled = enabled;
    }

    public int getBacklogSize() {
        return backlogSize;
    }

    public void setBacklogSize(int backlogSize) {
        if (backlogSize <= 0) {
            throw new IllegalArgumentException("The backlog size must be a positive number, was: " + backlogSize);
        }
        this.backlogSize = backlogSize;
    }

    public long getTraceCounter() {
        return traceCounter.get();
    }

    public void resetTraceCounter() {
        traceCounter.set(0);
    }

    public List<BacklogTracerEventMessage> dumpTracedMessages(String nodeId) {
        List<BacklogTracerEventMessage> answer = new ArrayList<BacklogTracerEventMessage>();
        if (nodeId != null) {
            for (DefaultBacklogTracerEventMessage message : queue) {
                if (nodeId.equals(message.getToNode())) {
                    answer.add(message);
                }
            }
        }
        return answer;
    }

    public String dumpTracedMessagesAsXml(String nodeId) {
        List<BacklogTracerEventMessage> events = dumpTracedMessages(nodeId);

        StringBuilder sb = new StringBuilder();
        sb.append("<").append(DefaultBacklogTracerEventMessage.ROOT_TAG).append("s>");
        for (BacklogTracerEventMessage event : events) {
            sb.append("\n").append(event.toXml(2));
        }
        sb.append("\n</").append(DefaultBacklogTracerEventMessage.ROOT_TAG).append("s>");
        return sb.toString();
    }

    public List<BacklogTracerEventMessage> dumpAllTracedMessages() {
        List<BacklogTracerEventMessage> answer = new ArrayList<BacklogTracerEventMessage>();
        answer.addAll(queue);
        queue.clear();
        return answer;
    }

    public String dumpAllTracedMessagesAsXml() {
        List<BacklogTracerEventMessage> events = dumpAllTracedMessages();

        StringBuilder sb = new StringBuilder();
        sb.append("<").append(BacklogTracerEventMessage.ROOT_TAG).append("s>");
        for (BacklogTracerEventMessage event : events) {
            sb.append("\n").append(event.toXml(2));
        }
        sb.append("\n</").append(BacklogTracerEventMessage.ROOT_TAG).append("s>");
        return sb.toString();
    }

    long incrementTraceCounter() {
        return traceCounter.incrementAndGet();
    }

    void stopProcessor(ProcessorDefinition<?> processorDefinition) {
        this.processors.remove(processorDefinition);
    }

    @Override
    protected void doStart() throws Exception {
    }

    @Override
    protected void doStop() throws Exception {
        queue.clear();
    }

    @Override
    protected void doShutdown() throws Exception {
        queue.clear();
        processors.clear();
    }

    private void forceAutoAssigningIds() {
        NodeIdFactory factory = camelContext.getNodeIdFactory();
        if (factory != null) {
            for (ProcessorDefinition<?> child : processors) {
                // ensure also the children get ids assigned
                RouteDefinitionHelper.forceAssignIds(camelContext, child);
            }
        }
    }

}
