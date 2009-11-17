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
package org.apache.camel.processor;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.Navigate;
import org.apache.camel.Processor;
import org.apache.camel.impl.ServiceSupport;
import org.apache.camel.util.ExchangeHelper;
import org.apache.camel.util.ServiceHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Implements try/catch/finally type processing
 *
 * @version $Revision$
 */
public class TryProcessor extends ServiceSupport implements Processor, Navigate<Processor>, Traceable {
    private static final transient Log LOG = LogFactory.getLog(TryProcessor.class);

    protected final Processor tryProcessor;
    protected final List<CatchProcessor> catchClauses;
    protected final Processor finallyProcessor;

    public TryProcessor(Processor tryProcessor, List<CatchProcessor> catchClauses, Processor finallyProcessor) {
        this.tryProcessor = tryProcessor;
        this.catchClauses = catchClauses;
        this.finallyProcessor = finallyProcessor;
    }

    public String toString() {
        String finallyText = (finallyProcessor == null) ? "" : " Finally {" + finallyProcessor + "}";
        return "Try {" + tryProcessor + "} " + (catchClauses != null ? catchClauses : "") + finallyText;
    }

    public String getTraceLabel() {
        return "doTry";
    }

    public void process(Exchange exchange) throws Exception {
        Exception e;

        // try processor first
        try {
            tryProcessor.process(exchange);
            e = exchange.getException();

            // Ignore it if it was handled by the dead letter channel.
            if (e != null && ExchangeHelper.isFailureHandled(exchange)) {
                e = null;
            }
        } catch (Exception ex) {
            e = ex;
            exchange.setException(e);
        }

        // handle any exception occurred during the try processor
        try {
            if (e != null) {
                handleException(exchange, e);
            }
        } finally {
            // and run finally
            // notice its always executed since we always enter the try block
            processFinally(exchange);
        }
    }

    protected void doStart() throws Exception {
        ServiceHelper.startServices(tryProcessor, catchClauses, finallyProcessor);
    }

    protected void doStop() throws Exception {
        ServiceHelper.stopServices(finallyProcessor, catchClauses, tryProcessor);
    }

    protected void handleException(Exchange exchange, Throwable e) throws Exception {
        if (catchClauses == null) {
            return;
        }

        for (CatchProcessor catchClause : catchClauses) {
            Throwable caught = catchClause.catches(exchange, e);
            if (caught != null) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("This TryProcessor catches the exception: " + caught.getClass().getName() + " caused by: " + e.getMessage());
                }

                // give the rest of the pipeline another chance
                exchange.setProperty(Exchange.EXCEPTION_CAUGHT, caught);
                exchange.setException(null);

                // do not catch any exception here, let it propagate up
                catchClause.process(exchange);

                // is the exception handled by the catch clause
                boolean handled = catchClause.handles(exchange);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("The exception is handled: " + handled + " for the exception: " + e.getClass().getName()
                        + " caused by: " + caught.getMessage());
                }

                if (!handled) {
                    // put exception back as it was not handled
                    if (exchange.getException() == null) {
                        exchange.setException(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class));
                    }
                }

                return;
            }
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("This TryProcessor does not catch the exception: " + e.getClass().getName() + " caused by: " + e.getMessage());
        }
    }

    protected void processFinally(Exchange exchange) throws Exception {
        if (finallyProcessor != null) {
            Exception lastException = exchange.getException();
            exchange.setException(null);

            // do not catch any exception here, let it propagate up
            finallyProcessor.process(exchange);
            if (exchange.getException() == null) {
                exchange.setException(lastException);
            }
        }
    }

    public List<Processor> next() {
        if (!hasNext()) {
            return null;
        }
        List<Processor> answer = new ArrayList<Processor>();
        if (tryProcessor != null) {
            answer.add(tryProcessor);
        }
        if (catchClauses != null) {
            answer.addAll(catchClauses);
        }
        if (finallyProcessor != null) {
            answer.add(finallyProcessor);
        }
        return answer;
    }

    public boolean hasNext() {
        return tryProcessor != null;
    }
}
