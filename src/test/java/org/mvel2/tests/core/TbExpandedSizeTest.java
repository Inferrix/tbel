// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.tests.core;

import junit.framework.TestCase;
import org.mvel2.ExecutionContext;
import org.mvel2.ParserContext;
import org.mvel2.SandboxedParserConfiguration;
import org.mvel2.ScriptMemoryOverflowException;
import org.mvel2.optimizers.OptimizerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static org.mvel2.MVEL.compileExpression;
import static org.mvel2.MVEL.executeTbExpression;

/**
 * A list that holds the same list twice, nested, is counted as a reference (4 bytes) by the memory accounting while it is
 * 2^depth elements when it is walked: toString, hashCode, equals, join, sort, a JSON writer, the result of the script.
 */
public class TbExpandedSizeTest extends TestCase {

    private static final String DAG = "a = ['x']; for (int i = 0; i < 24; i++) { a = [a, a]; } ";

    private SandboxedParserConfiguration parserConfig;
    private ExecutionContext executionContext;

    @Override
    protected void setUp() throws Exception {
        OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE);
        super.setUp();
        this.parserConfig = ParserContext.enableSandboxedMode();
        this.executionContext = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        ParserContext.disableSandboxedMode();
    }

    private Object run(String script) {
        Serializable compiled = compileExpression(script);
        return executeTbExpression(compiled, executionContext, new HashMap<String, Object>());
    }

    // thrown inside a method that is called by reflection, the refusal reaches the script wrapped, as every overflow of a method does
    private void assertRefused(String script) {
        long start = System.nanoTime();
        try {
            run(script);
        } catch (Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof ScriptMemoryOverflowException) {
                    assertTrue(script, (System.nanoTime() - start) / 1_000_000 < 3000);
                    return;
                }
            }
            throw new AssertionError(script + " failed with " + e, e);
        }
        fail("expected ScriptMemoryOverflowException for: " + script);
    }

    public void testExpandingAReusedListIsRefused() {
        for (String call : new String[] {"a.toString().length()", "a.hashCode()", "('' + a).length()", "a.join().length()", "a.sort()", "a.equals(a.slice())", "a.indexOf(a.slice())"}) {
            assertRefused(DAG + call);
        }
    }

    public void testTheSameInAMap() {
        assertRefused(DAG + "m = {k: a}; m.toString().length()");
        assertRefused(DAG + "m = {k: a}; m.hashCode()");
        assertRefused(DAG + "m = {k: a}; m.entrySet().toString().length()");
    }

    // executeTbExpression unpacks the result into plain lists and maps: a tree
    public void testTheResultOfAScriptIsRefusedBeforeItIsUnpacked() {
        assertRefused(DAG + "a");
        assertEquals(java.util.Arrays.asList(java.util.Arrays.asList("x"), java.util.Arrays.asList("x")), run("a = ['x']; a = [a, a]; a"));
    }

    // the leaf grows after the list was nested: the size at the time of the insertion is not the size now
    public void testAChildThatGrowsAfterTheNestingIsStillRefused() {
        assertRefused("a = ['x']; top = a; for (int i = 0; i < 12; i++) { top = [top, top]; } for (int j = 0; j < 40; j++) { a.add('y'.repeat(100000)); } top.toString().length()");
    }

    public void testANestingThatIsTooDeepIsRefusedNotAStackOverflow() {
        assertRefused("a = []; for (int i = 0; i < 3000; i++) { a = [a]; } a.toString().length()");
    }

    public void testOrdinaryNestedDataStillWorks() {
        assertEquals("[[[x], [x]], [[x], [x]]]", run("a = ['x']; a = [a, a]; a = [a, a]; a.toString()"));
        assertEquals(Boolean.TRUE, run("a = [[1, 2], [3]]; b = [[1, 2], [3]]; a.equals(b)"));
        assertEquals(Integer.valueOf(new java.util.ArrayList<>(java.util.Arrays.asList(java.util.Arrays.asList(1, 2), java.util.Arrays.asList(3))).hashCode()), run("[[1, 2], [3]].hashCode()"));
        assertEquals("{k=[1, 2]}", run("{k: [1, 2]}.toString()"));
        assertEquals("1,2,3", run("[1, 2, 3].join()"));
        Map<String, Object> flat = new HashMap<String, Object>();
        for (int i = 0; i < 1000; i++) {
            flat.put("key" + i, "value" + i);
        }
        executionContext.checkExpansion(flat);
    }

    public void testAnUnlimitedContextIsNotChecked() {
        executionContext = new ExecutionContext(parserConfig, 0);
        assertEquals(Boolean.TRUE, run("a = ['x']; for (int i = 0; i < 10; i++) { a = [a, a]; } a.toString().length() > 0"));
    }
}
