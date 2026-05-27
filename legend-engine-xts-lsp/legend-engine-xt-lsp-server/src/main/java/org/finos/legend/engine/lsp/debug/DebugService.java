// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.engine.lsp.debug;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.finos.legend.engine.lsp.LegendPureSession;
import org.finos.legend.engine.lsp.RepositoryScanner;
import org.finos.legend.engine.lsp.UriMapper;
import org.finos.legend.engine.lsp.protocol.LegendDebug;
import org.finos.legend.engine.lsp.runtime.PureRuntimeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DebugService.class);

    private final PureRuntimeManager runtimeManager;
    private final RepositoryScanner repositoryScanner;
    private final UriMapper uriMapper;
    private final Supplier<Map<String, String>> openDocumentSourceSnapshot;

    private volatile LegendDebugSession debugSession;

    public DebugService(PureRuntimeManager runtimeManager, RepositoryScanner repositoryScanner,
                        UriMapper uriMapper, Supplier<Map<String, String>> openDocumentSourceSnapshot)
    {
        this.runtimeManager = runtimeManager;
        this.repositoryScanner = repositoryScanner;
        this.uriMapper = uriMapper;
        this.openDocumentSourceSnapshot = openDocumentSourceSnapshot;
    }

    public synchronized LegendDebug.Response start(LegendDebug.StartParams params)
    {
        LegendPureSession session = this.runtimeManager.getSession();
        if (session == null || !session.isInitialized())
        {
            return LegendDebug.Response.error("Runtime not initialized");
        }

        stopActiveSession();
        try
        {
            LegendDebugSession nextSession = LegendDebugSession.create(
                    session,
                    this.repositoryScanner,
                    this.uriMapper,
                    this.openDocumentSourceSnapshot.get(),
                    params == null ? null : params.getFunction(),
                    params == null ? Collections.emptyList() : params.getBreakpoints());
            this.debugSession = nextSession;

            LegendDebug.Response response = nextSession.start();
            clearIfTerminal(response);
            return response;
        }
        catch (Exception e)
        {
            LOGGER.error("Debug start failed", e);
            this.debugSession = null;
            return LegendDebug.Response.error(message(e));
        }
    }

    public synchronized LegendDebug.Response continueExecution()
    {
        LegendDebugSession active = this.debugSession;
        if (active == null)
        {
            return LegendDebug.Response.error("No active debug session");
        }
        LegendDebug.Response response = active.continueExecution();
        clearIfTerminal(response);
        return response;
    }

    public synchronized LegendDebug.Response stepIn()
    {
        LegendDebugSession active = this.debugSession;
        if (active == null)
        {
            return LegendDebug.Response.error("No active debug session");
        }
        LegendDebug.Response response = active.stepIn();
        clearIfTerminal(response);
        return response;
    }

    public synchronized LegendDebug.Response stepOver()
    {
        LegendDebugSession active = this.debugSession;
        if (active == null)
        {
            return LegendDebug.Response.error("No active debug session");
        }
        LegendDebug.Response response = active.stepOver();
        clearIfTerminal(response);
        return response;
    }

    public synchronized LegendDebug.Response stepOut()
    {
        if (this.debugSession == null)
        {
            return LegendDebug.Response.error("No active debug session");
        }
        return LegendDebug.Response.error("Step out is not supported by the current Pure debug runtime");
    }

    public synchronized LegendDebug.EvaluateResult evaluate(LegendDebug.EvaluateParams params)
    {
        LegendDebugSession active = this.debugSession;
        if (active == null || !active.isPaused())
        {
            return LegendDebug.EvaluateResult.error("Debug execution is not paused");
        }
        return active.evaluate(params == null ? "" : params.getExpression());
    }

    public synchronized List<LegendDebug.Variable> variables(LegendDebug.VariablesParams params)
    {
        LegendDebugSession active = this.debugSession;
        return active == null ? Collections.emptyList() : active.variables(params == null ? 1 : params.getVariablesReference());
    }

    public synchronized LegendDebug.Response stop()
    {
        LegendDebugSession active = this.debugSession;
        this.debugSession = null;
        return active == null ? LegendDebug.Response.completed(null) : active.stop();
    }

    public synchronized void shutdown()
    {
        stopActiveSession();
    }

    private void clearIfTerminal(LegendDebug.Response response)
    {
        if (response != null && !"paused".equals(response.getState()))
        {
            this.debugSession = null;
        }
    }

    private void stopActiveSession()
    {
        LegendDebugSession active = this.debugSession;
        if (active != null)
        {
            active.stop();
            this.debugSession = null;
        }
    }

    private static String message(Exception e)
    {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
