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

    private void assertFails(String script, Class<? extends Throwable> type) {
        try {
            run(script);
        } catch (Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (type.isInstance(t)) {
                    return;
                }
            }
            throw new AssertionError(script + " failed with " + e, e);
        }
        fail("expected " + type.getSimpleName() + " for: " + script);
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

    // an array, an Optional and an Iterable are walked by unpack and by a JSON writer as the lists are
    public void testOtherContainersAreWalkedToo() {
        for (String call : new String[] {"a.toArray()", "java.util.Optional.of(a)", "[a].toArray()"}) {
            assertRefused(DAG + call);
        }
        assertRefused("m = {v: 'x'}; for (int i = 0; i < 24; i++) { m = {l: m, r: m}; } m.values().toArray()");
        assertFails(DAG + "a.iterator()", org.mvel2.ScriptRuntimeException.class);
    }

    // a cycle is not a finite structure: hashCode and toString of it end in a StackOverflowError, which no caller handles
    public void testACycleIsRefusedNotAStackOverflow() {
        for (String script : new String[] {"a = []; b = [a]; a.add(b); a.toString()", "a = []; b = [a]; a.add(b); a.hashCode()", "m = {}; m.put('k', m); m.hashCode()", "l = []; l.add(l); l.hashCode()", "a = []; b = [a]; a.add(b); a"}) {
            assertFails(script, org.mvel2.ScriptRuntimeException.class);
        }
    }

    // the walk of toString/hashCode/equals is made once, not again by every nested call: depth x size
    public void testANestedCallDoesNotWalkItsSubtreeAgain() {
        long start = System.nanoTime();
        run("l = []; for (int i = 0; i < 700000; i++) { l.add(i); } a = l; for (int j = 0; j < 999; j++) { a = [a]; } a.hashCode()");
        assertTrue("took " + (System.nanoTime() - start) / 1_000_000 + " ms", (System.nanoTime() - start) / 1_000_000 < 3000);
    }

    // the bound is a multiple of the memory: an ordinary list of 850,000 numbers (3.4 MB accounted) can still be printed
    public void testALargeButAccountedListCanStillBePrinted() {
        assertEquals(Boolean.TRUE, run("l = []; for (int i = 0; i < 850000; i++) { l.add(i); } l.toString().length() > 0"));
    }

    // the walk must not call entrySet of an ExecutionHashMap: that builds a new accounted set of entries and hashes them on every call
    public void testTheWalkDoesNotChangeTheMemoryAccounting() {
        ExecutionContext context = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        org.mvel2.execution.ExecutionHashMap<String, Object> map = new org.mvel2.execution.ExecutionHashMap<>(4, context);
        map.put("a", "x");
        map.put("b", "yy");
        long before = context.getMemorySize();
        for (int i = 0; i < 5; i++) {
            context.checkExpansion(map);
        }
        assertEquals(before, context.getMemorySize());
    }

    public void testAnUnlimitedContextIsNotChecked() {
        executionContext = new ExecutionContext(parserConfig, 0);
        assertEquals(Boolean.TRUE, run("a = ['x']; for (int i = 0; i < 10; i++) { a = [a, a]; } a.toString().length() > 0"));
    }
}
