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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.stack.MutableStack;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.tuple.Tuples;
import org.finos.legend.engine.lsp.protocol.LegendDebug;
import org.finos.legend.pure.m3.exception.PureExecutionException;
import org.finos.legend.pure.m3.navigation.Instance;
import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.navigation.PackageableElement.PackageableElement;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.navigation.function.Function;
import org.finos.legend.pure.m3.navigation.generictype.GenericType;
import org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity;
import org.finos.legend.pure.m3.navigation.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.serialization.runtime.IncrementalCompiler;
import org.finos.legend.pure.m3.serialization.runtime.Source;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.SourceInformation;
import org.finos.legend.pure.m4.coreinstance.primitive.PrimitiveCoreInstance;
import org.finos.legend.pure.m4.transaction.framework.ThreadLocalTransactionContext;
import org.finos.legend.pure.runtime.java.interpreted.VariableContext;

class LegendDebugState
{
    private static final int TOP_LEVEL_VARIABLES_REFERENCE = 1;
    private static final int MAX_CHILDREN = 100;
    private static final Set<String> HIDDEN_PROPERTIES = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
            M3Properties.referenceUsages,
            M3Properties.classifierGenericType)));

    private final CountDownLatch latch = new CountDownLatch(1);
    private final LegendDebugFunctionExecution functionExecution;
    private final MutableStack<CoreInstance> functionExpressionCallStack;
    private final MutableList<Pair<String, CoreInstance>> variables;
    private final String variablesTypeAndMultiplicity;
    private final Map<Integer, DebugValue> referencesById = new HashMap<>();
    private final Map<String, Integer> referenceIdsByKey = new HashMap<>();

    private volatile boolean abort;
    private int nextVariablesReference = TOP_LEVEL_VARIABLES_REFERENCE + 1;

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

    List<LegendDebug.Variable> variables(int variablesReference)
    {
        int reference = variablesReference <= 0 ? TOP_LEVEL_VARIABLES_REFERENCE : variablesReference;
        if (reference == TOP_LEVEL_VARIABLES_REFERENCE)
        {
            return topLevelVariables();
        }

        DebugValue debugValue = this.referencesById.get(reference);
        return debugValue == null ? Collections.emptyList() : childVariables(debugValue);
    }

    LegendDebug.EvaluateResult evaluate(String command)
    {
        String expression = command == null ? "" : command;
        try
        {
            String normalized = normalizeExpression(expression);
            CoreInstance result = this.functionExecution.withPausesSuppressed(() -> evaluateNormalizedExpression(normalized));
            DebugValue debugValue = DebugValue.instance("eval:" + normalized, result);
            return LegendDebug.EvaluateResult.success(formatValue(result), variablesReferenceFor(debugValue));
        }
        catch (Exception e)
        {
            return LegendDebug.EvaluateResult.error(sanitizeEvaluationError(e, expression));
        }
    }

    private CoreInstance evaluateNormalizedExpression(String expression)
    {
        Source inMemoryCodeBlock = this.functionExecution.getPureRuntime().createInMemoryCodeBlock(
                "{" + this.variablesTypeAndMultiplicity + "|\n" + expression + "\n}");

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

        return this.functionExecution.start(lambda, this.variables.collect(Pair::getTwo));
    }

    private List<LegendDebug.Variable> topLevelVariables()
    {
        List<LegendDebug.Variable> result = new ArrayList<>();
        for (Pair<String, CoreInstance> variable : this.variables.toSortedListBy(Pair::getOne))
        {
            CoreInstance value = variable.getTwo();
            DebugValue debugValue = DebugValue.instance("local:" + variable.getOne(), value);
            result.add(new LegendDebug.Variable(
                    variable.getOne(),
                    formatValue(value),
                    safeComputeVariableTypeAndMultiplicity(value),
                    variablesReferenceFor(debugValue)));
        }
        return result;
    }

    private List<LegendDebug.Variable> childVariables(DebugValue debugValue)
    {
        ListIterable<? extends CoreInstance> values = debugValue.values();
        if (values != null)
        {
            return indexedChildren(debugValue.key, values);
        }

        CoreInstance instance = debugValue.instance;
        if (instance == null)
        {
            return Collections.emptyList();
        }

        ListIterable<? extends CoreInstance> valueSpecificationValues = valueSpecificationValues(instance);
        if (valueSpecificationValues != null)
        {
            if (!isCollectionValueSpecification(instance) && valueSpecificationValues.size() == 1)
            {
                instance = valueSpecificationValues.get(0);
            }
            else
            {
                return indexedChildren(debugValue.key + ".values", valueSpecificationValues);
            }
        }

        if (instance == null || instance instanceof PrimitiveCoreInstance)
        {
            return Collections.emptyList();
        }

        List<LegendDebug.Variable> result = new ArrayList<>();
        ListIterable<String> keys = instance.getKeys()
                .reject(HIDDEN_PROPERTIES::contains)
                .toSortedList();
        int count = 0;
        for (String key : keys)
        {
            if (count >= MAX_CHILDREN)
            {
                result.add(truncatedVariable(keys.size() - MAX_CHILDREN));
                break;
            }

            ListIterable<? extends CoreInstance> propertyValues = safePropertyValues(instance, key);
            if (propertyValues == null || propertyValues.isEmpty())
            {
                continue;
            }

            String childKey = debugValue.key + "." + key;
            result.add(variableForValues(key, childKey, propertyValues));
            count++;
        }
        return result;
    }

    private List<LegendDebug.Variable> indexedChildren(String parentKey, ListIterable<? extends CoreInstance> values)
    {
        List<LegendDebug.Variable> result = new ArrayList<>();
        int size = values == null ? 0 : values.size();
        int limit = Math.min(size, MAX_CHILDREN);
        for (int i = 0; i < limit; i++)
        {
            CoreInstance value = values.get(i);
            String key = parentKey + "[" + i + "]";
            result.add(variableForInstance("[" + i + "]", key, value));
        }
        if (size > MAX_CHILDREN)
        {
            result.add(truncatedVariable(size - MAX_CHILDREN));
        }
        return result;
    }

    private LegendDebug.Variable variableForValues(String name, String key, ListIterable<? extends CoreInstance> values)
    {
        if (values.size() == 1)
        {
            return variableForInstance(name, key, values.get(0));
        }

        DebugValue debugValue = DebugValue.values(key, values);
        return new LegendDebug.Variable(name, collectionSummary(values), "List", variablesReferenceFor(debugValue));
    }

    private LegendDebug.Variable variableForInstance(String name, String key, CoreInstance value)
    {
        DebugValue debugValue = DebugValue.instance(key, value);
        return new LegendDebug.Variable(
                name,
                formatValue(value),
                typeNameForValue(value),
                variablesReferenceFor(debugValue));
    }

    private LegendDebug.Variable truncatedVariable(int remaining)
    {
        return new LegendDebug.Variable("...", remaining + " more item(s)", "", 0);
    }

    private int variablesReferenceFor(DebugValue debugValue)
    {
        if (!isExpandable(debugValue))
        {
            return 0;
        }

        Integer existing = this.referenceIdsByKey.get(debugValue.key);
        if (existing != null)
        {
            return existing;
        }

        int reference = this.nextVariablesReference++;
        this.referenceIdsByKey.put(debugValue.key, reference);
        this.referencesById.put(reference, debugValue);
        return reference;
    }

    private boolean isExpandable(DebugValue debugValue)
    {
        if (debugValue == null)
        {
            return false;
        }

        ListIterable<? extends CoreInstance> values = debugValue.values();
        if (values != null)
        {
            return values.notEmpty();
        }

        CoreInstance instance = debugValue.instance;
        ListIterable<? extends CoreInstance> valueSpecificationValues = valueSpecificationValues(instance);
        if (valueSpecificationValues != null)
        {
            return isCollectionValueSpecification(instance)
                    ? valueSpecificationValues.notEmpty()
                    : (valueSpecificationValues.size() != 1 || isExpandable(DebugValue.instance(debugValue.key + ".value", valueSpecificationValues.get(0))));
        }
        return isExpandableInstance(instance);
    }

    private boolean isExpandableInstance(CoreInstance instance)
    {
        if (instance == null || instance instanceof PrimitiveCoreInstance)
        {
            return false;
        }
        try
        {
            return instance.getKeys().anySatisfy(key -> !HIDDEN_PROPERTIES.contains(key));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private String normalizeExpression(String command)
    {
        String expression = command == null ? "" : command.trim();
        if (expression.isEmpty())
        {
            return expression;
        }

        if (expression.charAt(0) == '$')
        {
            String localName = leadingIdentifier(expression, 1);
            if (localName != null && !hasLocal(localName))
            {
                throw new IllegalArgumentException(outOfScopeMessage(localName));
            }
            return expression;
        }

        String leadingIdentifier = leadingIdentifier(expression, 0);
        if (leadingIdentifier != null && hasLocal(leadingIdentifier))
        {
            String remainder = expression.substring(leadingIdentifier.length()).trim();
            if (remainder.isEmpty() || remainder.startsWith(".") || remainder.startsWith("->") || remainder.startsWith("["))
            {
                return "$" + expression;
            }
        }
        return expression;
    }

    private boolean hasLocal(String name)
    {
        return this.variables.anySatisfy(variable -> variable.getOne().equals(name));
    }

    private String leadingIdentifier(String text, int offset)
    {
        if (text.length() <= offset || !isIdentifierStart(text.charAt(offset)))
        {
            return null;
        }

        int end = offset + 1;
        while (end < text.length() && isIdentifierPart(text.charAt(end)))
        {
            end++;
        }
        return text.substring(offset, end);
    }

    private String outOfScopeMessage(String name)
    {
        List<String> localNames = availableLocalNames();
        return "`" + name + "` is not in scope yet; available locals are "
                + (localNames.isEmpty() ? "none" : String.join(", ", localNames)) + ".";
    }

    private List<String> availableLocalNames()
    {
        List<String> names = new ArrayList<>();
        this.variables.collect(Pair::getOne).toSortedList().forEach(names::add);
        return names;
    }

    private String sanitizeEvaluationError(Exception e, String expression)
    {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        String sanitized = message
                .replaceAll("codeBlock_[A-Za-z0-9_\\-]+", "debug expression")
                .replaceAll("resource:[^\\s\\]]*debug expression", "debug expression");

        String localName = localNameFromExplicitReference(expression);
        if (localName != null && !hasLocal(localName) && !sanitized.contains("not in scope yet"))
        {
            return outOfScopeMessage(localName) + "\n" + sanitized;
        }
        return sanitized;
    }

    private String localNameFromExplicitReference(String expression)
    {
        String trimmed = expression == null ? "" : expression.trim();
        return trimmed.startsWith("$") ? leadingIdentifier(trimmed, 1) : null;
    }

    private String formatValue(CoreInstance value)
    {
        if (value == null)
        {
            return "null";
        }

        ListIterable<? extends CoreInstance> values = valueSpecificationValues(value);
        if (values != null)
        {
            if (values.isEmpty())
            {
                return "[]";
            }
            if (!isCollectionValueSpecification(value) && values.size() == 1)
            {
                return formatSingleValue(values.get(0));
            }
            return collectionSummary(values);
        }
        return formatSingleValue(value);
    }

    private String formatSingleValue(CoreInstance value)
    {
        if (value == null)
        {
            return "null";
        }
        if (value instanceof PrimitiveCoreInstance)
        {
            Object primitive = ((PrimitiveCoreInstance<?>) value).getValue();
            return primitive == null ? "null" : String.valueOf(primitive);
        }

        ProcessorSupport processorSupport = this.functionExecution.getProcessorSupport();
        try
        {
            if (Function.isFunctionDefinition(value, processorSupport)
                    || processorSupport.instance_instanceOf(value, M3Paths.NativeFunction))
            {
                return Function.print(value, processorSupport);
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            if (PackageableElement.isPackageableElement(value, processorSupport))
            {
                String path = PackageableElement.getUserPathForPackageableElement(value);
                if (path != null && !path.isEmpty())
                {
                    return path;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return typeName(value) + " " + displayNameOrId(value);
    }

    private String collectionSummary(ListIterable<? extends CoreInstance> values)
    {
        String itemType = values.isEmpty() ? "Any" : typeName(values.get(0));
        return itemType + "[" + values.size() + "]";
    }

    private String typeNameForValue(CoreInstance value)
    {
        ListIterable<? extends CoreInstance> values = valueSpecificationValues(value);
        if (values != null)
        {
            if (values.isEmpty())
            {
                return typeName(value);
            }
            if (!isCollectionValueSpecification(value) && values.size() == 1)
            {
                return typeName(values.get(0));
            }
            return "List";
        }
        return typeName(value);
    }

    private String safeComputeVariableTypeAndMultiplicity(CoreInstance value)
    {
        try
        {
            return computeVariableTypeAndMultiplicity(this.functionExecution, value);
        }
        catch (Exception e)
        {
            return typeNameForValue(value);
        }
    }

    private String typeName(CoreInstance value)
    {
        if (value == null)
        {
            return "Nil";
        }
        CoreInstance classifier = value.getClassifier();
        if (classifier == null)
        {
            return value.getClass().getSimpleName();
        }

        try
        {
            if (PackageableElement.isPackageableElement(classifier, this.functionExecution.getProcessorSupport()))
            {
                String path = PackageableElement.getUserPathForPackageableElement(classifier);
                if (path != null && !path.isEmpty())
                {
                    return path;
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return classifier.getName() == null ? classifier.getClass().getSimpleName() : classifier.getName();
    }

    private String displayNameOrId(CoreInstance value)
    {
        String name = value.getName();
        return (name == null || name.isEmpty() || name.startsWith("@_")) ? "#" + value.getSyntheticId() : name;
    }

    private ListIterable<? extends CoreInstance> valueSpecificationValues(CoreInstance value)
    {
        return valueSpecificationValues(value, this.functionExecution.getProcessorSupport());
    }

    private boolean isCollectionValueSpecification(CoreInstance value)
    {
        return isCollectionValueSpecification(value, this.functionExecution.getProcessorSupport());
    }

    private static ListIterable<? extends CoreInstance> valueSpecificationValues(CoreInstance value, ProcessorSupport processorSupport)
    {
        if (value == null)
        {
            return null;
        }
        try
        {
            return (ValueSpecification.isInstanceValue(value, processorSupport)
                    || ValueSpecification.isNonExecutableValueSpecification(value, processorSupport))
                    ? ValueSpecification.getValues(value, processorSupport)
                    : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static boolean isCollectionValueSpecification(CoreInstance value, ProcessorSupport processorSupport)
    {
        if (value == null)
        {
            return false;
        }
        try
        {
            if (!ValueSpecification.isValueSpecification(value, processorSupport))
            {
                return false;
            }
            CoreInstance multiplicity = value.getValueForMetaPropertyToOne(M3Properties.multiplicity);
            return multiplicity != null && !Multiplicity.isToOne(multiplicity);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private ListIterable<? extends CoreInstance> safePropertyValues(CoreInstance instance, String key)
    {
        try
        {
            return instance.getValueForMetaPropertyToMany(key);
        }
        catch (Exception e)
        {
            return Lists.immutable.empty();
        }
    }

    private static boolean isIdentifierStart(char c)
    {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
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
        ProcessorSupport processorSupport = functionExecution.getProcessorSupport();
        CoreInstance singleValue = singleValueSpecificationValue(coreInstance, processorSupport);
        if (singleValue != null)
        {
            try
            {
                if (Function.isFunctionDefinition(singleValue, processorSupport))
                {
                    return M3Paths.ConcreteFunctionDefinition + "<Any>" + multiplicity;
                }
            }
            catch (Exception ignored)
            {
            }
        }

        CoreInstance genericType = coreInstance.getValueForMetaPropertyToOne(M3Properties.genericType);

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

    private static CoreInstance singleValueSpecificationValue(CoreInstance coreInstance, ProcessorSupport processorSupport)
    {
        ListIterable<? extends CoreInstance> values = valueSpecificationValues(coreInstance, processorSupport);
        return values == null || values.size() != 1 ? null : values.get(0);
    }

    private static class DebugValue
    {
        private final String key;
        private final CoreInstance instance;
        private final ListIterable<? extends CoreInstance> values;

        private DebugValue(String key, CoreInstance instance, ListIterable<? extends CoreInstance> values)
        {
            this.key = key;
            this.instance = instance;
            this.values = values;
        }

        private static DebugValue instance(String key, CoreInstance instance)
        {
            return new DebugValue(key, instance, null);
        }

        private static DebugValue values(String key, ListIterable<? extends CoreInstance> values)
        {
            return new DebugValue(key, null, values);
        }

        private ListIterable<? extends CoreInstance> values()
        {
            return this.values;
        }
    }
}
