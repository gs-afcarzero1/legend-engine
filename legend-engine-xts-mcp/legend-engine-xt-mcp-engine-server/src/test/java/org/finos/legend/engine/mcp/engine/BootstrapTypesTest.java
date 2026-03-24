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
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
// implied. See the License for the specific language governing
// permissions and limitations under the License.

package org.finos.legend.engine.mcp.engine;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BootstrapTypesTest
{
    @Test
    public void testBuildScopeFromInputSelf()
    {
        Assert.assertEquals(BuildScope.SELF, BuildScope.fromInput("self"));
    }

    @Test
    public void testBuildScopeFromInputClosure()
    {
        Assert.assertEquals(BuildScope.CLOSURE, BuildScope.fromInput("closure"));
    }

    @Test
    public void testBuildScopeFromInputNullDefaultsToSelf()
    {
        Assert.assertEquals(BuildScope.SELF, BuildScope.fromInput(null));
    }

    @Test
    public void testBuildScopeFromInputInvalidReturnsNull()
    {
        Assert.assertNull(BuildScope.fromInput("invalid"));
    }

    @Test
    public void testBuildScopeSupportedValuesContainsSelf()
    {
        String values = BuildScope.supportedValues();
        Assert.assertFalse("supportedValues should not be empty", values.isEmpty());
        Assert.assertTrue("supportedValues should contain 'self'", values.contains("self"));
    }

    @Test
    public void testBuildScopeAppendToUpstream()
    {
        List<String> args = new ArrayList<>();
        BuildScope.UPSTREAM.appendTo(args);
        Assert.assertTrue("UPSTREAM should add -am", args.contains("-am"));
        Assert.assertFalse("UPSTREAM should not add -amd", args.contains("-amd"));
    }

    @Test
    public void testBuildScopeAppendToDownstream()
    {
        List<String> args = new ArrayList<>();
        BuildScope.DOWNSTREAM.appendTo(args);
        Assert.assertFalse("DOWNSTREAM should not add -am", args.contains("-am"));
        Assert.assertTrue("DOWNSTREAM should add -amd", args.contains("-amd"));
    }

    @Test
    public void testBuildScopeAppendToClosure()
    {
        List<String> args = new ArrayList<>();
        BuildScope.CLOSURE.appendTo(args);
        Assert.assertTrue("CLOSURE should add -am", args.contains("-am"));
        Assert.assertTrue("CLOSURE should add -amd", args.contains("-amd"));
    }

    @Test
    public void testBuildScopeAppendToSelf()
    {
        List<String> args = new ArrayList<>();
        BuildScope.SELF.appendTo(args);
        Assert.assertFalse("SELF should not add -am", args.contains("-am"));
        Assert.assertFalse("SELF should not add -amd", args.contains("-amd"));
    }

    @Test
    public void testDependencyCoordinateKey()
    {
        DependencyCoordinate coord = new DependencyCoordinate("org.example", "my-artifact");
        Assert.assertEquals("groupId:artifactId", "org.example:my-artifact", coord.key());
    }

    @Test
    public void testProcessResultFieldsAccessible()
    {
        ProcessResult result = new ProcessResult(0, "Build successful", "mvn compile");
        Assert.assertEquals(0, result.exitCode);
        Assert.assertEquals("Build successful", result.output);
        Assert.assertEquals("mvn compile", result.commandLine);
    }
}
