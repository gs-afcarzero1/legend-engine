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

package org.finos.legend.engine.test.emit.core.m2m;

import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.mapping.mappingTest.ExpectedOutputMappingTestAssert;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.mapping.mappingTest.MappingTest_Legacy;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.modelToModel.mapping.ObjectInputData;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.modelToModel.mapping.ObjectInputType;
import org.finos.legend.engine.test.emit.EMITModelLoader;
import org.finos.legend.engine.test.emit.EMITTasks;
import org.finos.legend.engine.test.runner.mapping.RichMappingTestResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.stream.Stream;

public class TestConstructedClassReadProjection
{
    private static final String PREFIX = "demo::m2m::constructed::";
    private static final String JSON = "{\"id\":\"c1\",\"name\":\"Ann\",\"address\":{\"street\":\"main st\"}}";
    private static final String XML = "<Customer><id>c1</id><name>Ann</name><address><street>main st</street></address></Customer>";
    private static PureModel model;

    @BeforeAll
    static void compileModel() throws Exception
    {
        model = EMITTasks.compile(EMITTasks.parse(new EMITModelLoader().load(Paths.get(
                TestConstructedClassReadProjection.class.getClassLoader().getResource(
                        "m2m-emit-models/m2m-constructed-class-interface.emit.yaml").toURI()))).getPmcd());
    }

    @TestFactory
    Stream<DynamicTest> sparseReaders()
    {
        // The EMIT model covers JSON; the legacy runner also reaches the XML reader generator.
        return Stream.of("NoConstructor", "NestedConstructor").map(mapping ->
                DynamicTest.dynamicTest("XML sparse " + mapping, () ->
                        EMITTasks.assertLegacyMappingTestPassed(run(mapping, ObjectInputType.XML, XML))));
    }

    @TestFactory
    Stream<DynamicTest> requiredReadFieldsRemainRequired()
    {
        return Stream.of(ObjectInputType.JSON, ObjectInputType.XML).flatMap(type ->
                Stream.of("NestedConstructor").map(mapping -> DynamicTest.dynamicTest(type + " " + mapping + " missing required street", () ->
                {
                    String data = type == ObjectInputType.JSON ? JSON.replace("\"street\":\"main st\"", "") : XML.replace("<street>main st</street>", "");
                    RichMappingTestResult result = run(mapping, type, data);
                    Assertions.assertNotNull(result.getException(), "Missing a projected required field must fail execution");
                    Throwable error = result.getException();
                    StringBuilder messages = new StringBuilder();
                    while (error != null)
                    {
                        messages.append(error.getMessage()).append('\n');
                        error = error.getCause();
                    }
                    Assertions.assertTrue(messages.toString().contains("Invalid multiplicity for street: expected [1] found [0]"), messages::toString);
                })));
    }

    @TestFactory
    Stream<DynamicTest> polymorphicReaders()
    {
        return Stream.of("Address", "PostalAddress").map(sourceType -> DynamicTest.dynamicTest(sourceType + " sparse polymorphic reader", () ->
        {
            String data = "{\"@type\":\"" + PREFIX + "source::" + sourceType + "\",\"street\":\"main st\",\"box\":\"B1\"}";
            String expected = "{\"id\":\"main st\",\"values\":[\"main st\",\"" + ("PostalAddress".equals(sourceType) ? "B1" : "base") + "\"]}";
            EMITTasks.assertLegacyMappingTestPassed(run("PolymorphicSource", ObjectInputType.JSON, data, "Address", expected));
        }));
    }

    private static RichMappingTestResult run(String mapping, ObjectInputType type, String data)
    {
        String expected = "TargetClass".equals(mapping) ? "{\"id\":\"c1\",\"values\":[\"unknown\"],\"addresses\":[{\"street\":\"Ann\"}]}"
                : "{\"id\":\"c1\",\"values\":[\"" + (("NoConstructor".equals(mapping) || "Subtype".equals(mapping) || "Filter".equals(mapping)) ? "main st" : "MAIN ST") + "\"]}";
        return run(mapping, type, data, "Customer", expected);
    }

    private static RichMappingTestResult run(String mapping, ObjectInputType type, String data, String sourceClass, String expectedOutput)
    {
        boolean card = "TargetClass".equals(mapping);
        String tree = card ? "#{" + PREFIX + "target::Card{id,values,addresses{street}}}#" : "#{" + PREFIX + "target::Summary{id,values}}#";
        MappingTest_Legacy test = new MappingTest_Legacy();
        test.name = mapping + type;
        test.query = PureGrammarParser.newInstance().parseLambda("|" + PREFIX + "target::" + (card ? "Card" : "Summary")
                + ".all()->graphFetch(" + tree + ")->serialize(" + tree + ")");
        ObjectInputData input = new ObjectInputData();
        input.inputType = type;
        input.sourceClass = PREFIX + "source::" + sourceClass;
        input.data = data;
        test.inputData = Collections.singletonList(input);
        ExpectedOutputMappingTestAssert expected = new ExpectedOutputMappingTestAssert();
        expected.expectedOutput = expectedOutput;
        test._assert = expected;
        return EMITTasks.runLegacyMappingTest(PREFIX + mapping, test, model);
    }
}
