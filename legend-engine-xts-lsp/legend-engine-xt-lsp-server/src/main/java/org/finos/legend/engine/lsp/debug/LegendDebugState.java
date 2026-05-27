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

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.stack.MutableStack;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.tuple.Tuples;
import org.finos.legend.pure.m3.exception.PureExecutionException;
import org.finos.legend.pure.m3.navigation.Instance;
import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.navigation.generictype.GenericType;
import org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity;
import org.finos.legend.pure.m3.serialization.runtime.IncrementalCompiler;
import org.finos.legend.pure.m3.serialization.runtime.Source;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.SourceInformation;
import org.finos.legend.pure.m4.transaction.framework.ThreadLocalTransactionContext;
import org.finos.legend.pure.runtime.java.interpreted.VariableContext;

class LegendDebugState
{
    private final CountDownLatch latch = new CountDownLatch(1);
    private final LegendDebugFunctionExecution functionExecution;
    private final MutableStack<CoreInstance> functionExpressionCallStack;
    private final MutableList<Pair<String, CoreInstance>> variables;
    private final String variablesTypeAndMultiplicity;

    private volatile boolean abort;

    LegendDebugState(LegendDebugFunctionExecution functionExecution, VariableContext variableContext,
                     MutableStack<CoreInstance> functionExpressionCallStack)
    {
        this.functionExecution = functionExecution;
        this.functionExpressionCallStack = functionExpressionCallStack;
        this.variables = computeVariables(variableContext);
        this.variablesTypeAndMultiplicity = computeVariablesTypeAndMultiplicity(functionExecution, this.variables);
    }

    void await()
    {
        try
        {
            this.latch.await();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new PureExecutionException("Interrupted while paused in debugger", this.functionExpressionCallStack);
        }
    }

    void release()
    {
        this.functionExecution.clearDebugState(this);
        this.latch.countDown();
    }

    void abort()
    {
        this.abort = true;
        release();
    }

    boolean aborted()
    {
        return this.abort;
    }

    SourceInformation getCurrentSourceInformation()
    {
        return this.functionExpressionCallStack.isEmpty() ? null : this.functionExpressionCallStack.peek().getSourceInformation();
    }

    int getStackDepth()
    {
        return this.functionExpressionCallStack.size();
    }

    String getCurrentFrameName()
    {
        if (this.functionExpressionCallStack.isEmpty())
        {
            return "Pure debug point";
        }
        String name = this.functionExpressionCallStack.peek().getName();
        return (name == null || name.isEmpty()) ? "Pure debug point" : name;
    }

    MutableList<Pair<String, String>> getVariableTypeAndMultiplicity()
    {
        return this.variables.collect(variable -> Tuples.pair(
                variable.getOne(),
                computeVariableTypeAndMultiplicity(this.functionExecution, variable.getTwo())));
    }

    String evaluate(String command)
    {
        return this.functionExecution.withPausesSuppressed(() ->
        {
            Source inMemoryCodeBlock = this.functionExecution.getPureRuntime().createInMemoryCodeBlock(
                    "{" + this.variablesTypeAndMultiplicity + "|\n" + command + "\n}");

            IncrementalCompiler incrementalCompiler = this.functionExecution.getPureRuntime().getIncrementalCompiler();
            IncrementalCompiler.IncrementalCompilerTransaction transaction = incrementalCompiler.newTransaction(false);
            try (ThreadLocalTransactionContext ignore = transaction.openInCurrentThread())
            {
                incrementalCompiler.compileInCurrentTransaction(inMemoryCodeBlock);
            }

            ListIterable<CoreInstance> newInstances = inMemoryCodeBlock.getNewInstances();
            CoreInstance result = this.functionExecution.start(newInstances.get(0), Lists.fixedSize.of());
            CoreInstance lambda = Instance.getValueForMetaPropertyToOneResolved(
                    result,
                    M3Properties.values,
                    this.functionExecution.getProcessorSupport());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            this.functionExecution.start(lambda, this.variables.collect(Pair::getTwo), out,
                    this.functionExecution.newOutputWriter());
            return out.toString();
        });
    }

    private static MutableList<Pair<String, CoreInstance>> computeVariables(VariableContext variableContext)
    {
        if (variableContext == null)
        {
            return Lists.mutable.empty();
        }
        return variableContext.getVariableNames()
                .asLazy()
                .collect(name -> Tuples.pair(name, variableContext.getValue(name)))
                .select(variable -> variable.getTwo() != null)
                .toList();
    }

    private static String computeVariablesTypeAndMultiplicity(LegendDebugFunctionExecution functionExecution,
                                                              MutableList<Pair<String, CoreInstance>> variables)
    {
        return variables.collect(variable -> variable.getOne() + ":"
                + computeVariableTypeAndMultiplicity(functionExecution, variable.getTwo())).makeString(", ");
    }

    private static String computeVariableTypeAndMultiplicity(LegendDebugFunctionExecution functionExecution,
                                                             CoreInstance coreInstance)
    {
        String multiplicity = Multiplicity.print(coreInstance.getValueForMetaPropertyToOne(M3Properties.multiplicity));
        CoreInstance genericType = coreInstance.getValueForMetaPropertyToOne(M3Properties.genericType);
        ProcessorSupport processorSupport = functionExecution.getProcessorSupport();

        String type;
        if (processorSupport.type_subTypeOf(
                genericType.getValueForMetaPropertyToOne(M3Properties.rawType),
                functionExecution.getPureRuntime().getCoreInstance(M3Paths.ConcreteFunctionDefinition)))
        {
            type = M3Paths.ConcreteFunctionDefinition + "<Any>";
        }
        else if (processorSupport.type_subTypeOf(
                genericType.getValueForMetaPropertyToOne(M3Properties.rawType),
                functionExecution.getPureRuntime().getCoreInstance(M3Paths.NativeFunction)))
        {
            type = M3Paths.NativeFunction + "<Any>";
        }
        else
        {
            type = GenericType.print(genericType, true, processorSupport);
        }

        return type + multiplicity;
    }
}
