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

    // Jackson (Jdk8Module) writes a Stream as a list: a stream cannot be walked without being consumed
    public void testAStreamIsRefused() {
        assertFails(DAG + "a.stream()", org.mvel2.ScriptRuntimeException.class);
    }

    // AbstractSet.equals asks this set whether it contains every element of the other: the elements of the argument are hashed
    public void testSetEqualsChecksItsArgument() {
        ExecutionContext context = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        org.mvel2.execution.ExecutionArrayList<Object> a = new org.mvel2.execution.ExecutionArrayList<>(context);
        a.add("x");
        for (int i = 0; i < 24; i++) {
            org.mvel2.execution.ExecutionArrayList<Object> b = new org.mvel2.execution.ExecutionArrayList<>(context);
            context.checkAssignGlobalVariable("a", a); // held by a variable: the accounting counts it as a 4 byte reference
            b.add(a);
            b.add(a);
            a = b;
        }
        org.mvel2.execution.ExecutionArrayList<Object> x = new org.mvel2.execution.ExecutionArrayList<>(context);
        org.mvel2.execution.ExecutionLinkedHashSet<Object> s1 = new org.mvel2.execution.ExecutionLinkedHashSet<>(context);
        org.mvel2.execution.ExecutionLinkedHashSet<Object> s2 = new org.mvel2.execution.ExecutionLinkedHashSet<>(context);
        s1.add(1);
        s2.add(x);
        context.checkAssignGlobalVariable("a", a);
        x.add(a); // after the element is in the set, as a script can
        x.add(a);
        long start = System.nanoTime();
        try {
            s1.equals(s2);
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertTrue((System.nanoTime() - start) / 1_000_000 < 3000);
        }
    }

    // a plain java.util collection (List.of, Arrays.asList, a copy, a view, an array) is no ExecutionObject: its toString and hashCode are the JDK's
    // and nothing accounts it. It is walked where it is used as the receiver or an argument of a call, in a constructor and in a string concatenation.
    public void testAPlainCollectionDagIsRefusedWhereItIsBuilt() {
        String f = "function f(n, p) { return n == 0 ? p : f(n - 1, java.util.List.of(p, p)); } ";
        assertRefused(f + "f(24, 'x').hashCode()");
        assertRefused(f + "('' + f(24, 'x')).length()");
        assertRefused("function f(n, p) { return n == 0 ? p : f(n - 1, java.util.Arrays.asList(p, p)); } f(24, 'x').hashCode()");
        // an array in a parameter
        assertRefused("function f(n, p) { return n == 0 ? p : f(n - 1, java.util.Arrays.asList(p, p).toArray()); } java.util.Arrays.deepToString(f(24, 'x')).length()");
        // a for-each variable
        StringBuilder loop = new StringBuilder("p0 = 'x'; ");
        for (int i = 1; i <= 24; i++) {
            loop.append("for (p").append(i).append(" : java.util.List.of(java.util.List.of(p").append(i - 1).append(", p").append(i - 1).append("))) { ");
        }
        loop.append("('' + p24).length()");
        for (int i = 1; i <= 24; i++) {
            loop.append(" }");
        }
        assertRefused(loop.toString());
    }

    // each element passes the guard of its own container; the copy or view that holds the same one N times does not
    public void testACopyOrViewThatHoldsAReusedListIsRefused() {
        String big = "a = ['x']; for (int i = 0; i < 21; i++) { a = [a, a]; } big = [a, a, a, a, a, a, a, a]; ";
        assertRefused(big + "big.toString().length()");
        for (String call : new String[] {"java.util.Arrays.toString(big.toArray()).length()", "java.util.List.copyOf(big).toString().length()",
                "new java.util.ArrayList(big).toString().length()", "new java.util.TreeMap(m).toString().length()", "m.values().toString().length()",
                "java.util.Arrays.hashCode(big.toArray())"}) {
            assertRefused(big + "m = {k: big}; " + call);
        }
    }

    // a call site checks only the first call by the optimizer: the cached accessors of the following calls check as well
    public void testAWarmCallSiteIsCheckedToo() {
        String f = "function f(n, p) { return n == 0 ? p : f(n - 1, java.util.List.of(p, p)); } ";
        assertRefused(f + "f(1, 'x'); function g(p) { return p.hashCode(); } g(java.util.List.of(1)); g(f(29, 'x'))");
        assertRefused(f + "f(1, 'x'); function g(p) { return p.toString().length(); } g(java.util.List.of(1)); g(f(24, 'x'))");
        String big = "a = ['x']; for (int i = 0; i < 21; i++) { a = [a, a]; } big = [a, a, a, a, a, a, a, a]; ";
        assertRefused(big + "function h(p) { return new java.util.ArrayList(p).size(); } h([1]); h(big)");
    }

    // Map.Entry and Optional are expanded by toString and hashCode as a list is
    public void testAnEntryOrOptionalChainIsRefused() {
        assertRefused("function f(n, p) { return n == 0 ? p : f(n - 1, java.util.Map.entry(p, p)); } ('' + f(23, 'x')).length()");
        assertRefused("function f(n, p) { return n == 0 ? p : f(n - 1, java.util.Map.entry(p, p)); } f(30, 'x').hashCode()");
        assertRefused("function f(n, p) { return n == 0 ? p : f(n - 1, java.util.List.of(java.util.Optional.of(p), java.util.Optional.of(p))); } f(24, 'x').toString().length()");
    }

    // == calls equals on the left operand: a plain left operand is walked like any other use
    public void testAnEqualityWithAPlainOperandIsRefused() {
        String two = "a1 = ['x']; a2 = ['x']; for (int i = 0; i < 20; i++) { a1 = [a1, a1]; a2 = [a2, a2]; } "
                + "x = [a1, a1, a1, a1, a1, a1, a1, a1, a1, a1, a1, a1, a1, a1, a1, a1]; y = [a2, a2, a2, a2, a2, a2, a2, a2, a2, a2, a2, a2, a2, a2, a2, a2]; ";
        assertRefused(two + "java.util.List.copyOf(x) == y");
        assertRefused(two + "java.util.List.copyOf(x) != y");
        assertEquals(Boolean.TRUE, run("java.util.List.copyOf([1, 2]) == [1, 2]"));
    }

    public void testOrdinaryPlainCollectionsStillWork() {
        assertEquals(2, run("function g(p) { return p.size(); } g(java.util.List.of(1, 2))"));
        assertEquals("[1, 2]", run("java.util.List.of(1, 2).toString()"));
        assertEquals("[1, 2]", run("('' + java.util.Arrays.asList(1, 2))"));
        assertEquals(3, run("l = [1, 2, 3]; new java.util.ArrayList(l).size()"));
        assertEquals("[a, b]", run("java.util.Arrays.toString('a,b'.split(','))"));
        assertEquals("xyyy", run("function f(n, p) { return n == 0 ? p : f(n - 1, p + 'y'); } f(3, 'x')"));
    }

    // AbstractSet.equals answers false for a non-set without looking at it: the walk of the argument is for another set of the same size
    public void testSetEqualsDoesNotWalkAListArgument() {
        ExecutionContext context = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        org.mvel2.execution.ExecutionArrayList<Object> a = new org.mvel2.execution.ExecutionArrayList<>(context);
        a.add("x");
        for (int i = 0; i < 24; i++) {
            org.mvel2.execution.ExecutionArrayList<Object> b = new org.mvel2.execution.ExecutionArrayList<>(context);
            context.checkAssignGlobalVariable("a", a);
            b.add(a);
            b.add(a);
            a = b;
        }
        org.mvel2.execution.ExecutionLinkedHashSet<Object> set = new org.mvel2.execution.ExecutionLinkedHashSet<>(context);
        set.add(1);
        assertFalse(set.equals(a));
    }

    // an entry of an ExecutionHashMap is an ExecutionObject that every walk skips: a map keyed and valued by the previous entry doubles at each step
    public void testAnEntryThatHoldsItselfTwiceIsRefused() {
        String build = "m = {'k': 'v'}; e = null; for (x : m.entrySet()) { e = x; } for (int i = 0; i < 22; i++) { n = {}; n.put(e, e); for (x : n.entrySet()) { e = x; } } ";
        for (String use : new String[] {"e.toString().length()", "('' + e).length()", "e.hashCode()", "e.equals(e)", "u = [e]; u.toUnmodifiable().toString().length()"}) {
            assertRefused(build + use);
        }
        assertEquals("k=v", run("m = {'k': 'v'}; for (x : m.entrySet()) { return x.toString(); }"));
    }

    // built by hand: the script that builds the entry hashes it at every step and is refused there, before any use
    public void testAnEntryHoldingItselfTwiceIsRefusedWhereItIsPrinted() {
        ExecutionContext context = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        Object e = new org.mvel2.execution.ExecutionEntry<Object, Object>("k", "v", context);
        for (int i = 0; i < 24; i++) {
            e = new org.mvel2.execution.ExecutionEntry<Object, Object>(e, e, context);
        }
        final Object entry = e;
        for (java.util.concurrent.Callable<Object> use : java.util.Arrays.<java.util.concurrent.Callable<Object>>asList(entry::toString, entry::hashCode, () -> entry.equals(new org.mvel2.execution.ExecutionEntry<Object, Object>(entry, entry, context)))) {
            try {
                use.call();
                fail("expected ScriptMemoryOverflowException");
            } catch (ScriptMemoryOverflowException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith("Max expanded size overflow"));
            } catch (Exception other) {
                throw new AssertionError(other);
            }
        }
    }

    // arg.equals(element): AbstractMap.equals hashes the keys of the argument for every element, whatever the element holds
    public void testIndexOfWithAMapArgumentIsBoundedByTheArgumentTimesTheElements() {
        String o = "a = ['x']; for (int i = 0; i < 21; i++) { a = [a, a]; } o = {}; o.put(a, 1); big = []; for (int i = 0; i < 40; i++) { big.add({'q': 1}); } ";
        for (String call : new String[] {"big.indexOf(o)", "big.contains(o)", "big.lastIndexOf(o)", "big.remove(o)"}) {
            assertRefused(o + call);
        }
        assertEquals(1, run("big = [{'q': 1}, {'r': 2}]; big.indexOf({'r': 2})"));
        assertEquals(Boolean.TRUE, run("big = [{'q': 1}, {'r': 2}]; big.contains({'q': 1})"));
    }

    private static final String DAG20_REFS = "a = ['x']; for (int i = 0; i < 20; i++) { a = [a, a]; } big = []; for (int i = 0; i < 40; i++) { big.add(a); } ";

    // a copy or a view of a list is a plain java.util collection: every operator that prints its operand walks it first
    public void testAPlainCopyOrViewIsRefusedWhereAnOperatorPrintsIt() {
        for (String use : new String[] {"(big + 1) ~= 'x'", "'abc' contains (big + 1)", "'abc' < (big + 1)", "'abc' >= (big + 1)",
                "m = {}; for (int i = 0; i < 40; i++) { m.put([a, i], 1); } m.keySet() ~= 'x'"}) {
            assertRefused(DAG20_REFS + use);
        }
        assertEquals(Boolean.TRUE, run("l = [1, 2]; ('abc' contains (l + 3)) == false"));
        assertEquals(Boolean.TRUE, run("l = ['a', 'b']; (l + 'c') ~= '.*'"));
    }

    // one call that compares or hashes every element is one walk: N references to one nested list are N times its expansion
    public void testACallThatLoopsOverReusedElementsIsRefused() {
        String x = "b = ['x']; for (int i = 0; i < 19; i++) { b = [b, b]; } x = [b, 'z']; ";
        assertRefused(DAG20_REFS + x + "big.indexOf(x)");
        assertRefused(DAG20_REFS + x + "big.contains(x)");
        assertRefused(DAG20_REFS + x + "big.remove(x)");
        assertRefused(DAG20_REFS + "m = {}; for (int i = 0; i < 40; i++) { m.put('k' + i, a); } m.sortByValue()");
        assertRefused(DAG20_REFS + "m = {}; for (int i = 0; i < 40; i++) { m.put('k' + i, a); } m.toSortedByValue()");
        assertRefused(DAG20_REFS + "m = {}; for (int i = 0; i < 40; i++) { m.put([a, i], 1); } n = {}; n.putAll(m)");
        assertEquals(1, run("l = [[1, 2], [3]]; l.indexOf([3])"));
        assertEquals(Boolean.TRUE, run("l = [[1, 2], [3]]; l.contains([3])"));
        assertEquals(Boolean.TRUE, run("l = [1, 2, 3]; l.remove(2); l.size() == 2"));
    }

    public void testASetAddAllOfReusedElementsIsRefused() {
        ExecutionContext context = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        org.mvel2.execution.ExecutionArrayList<Object> a = new org.mvel2.execution.ExecutionArrayList<>(context);
        a.add("x");
        for (int i = 0; i < 20; i++) {
            org.mvel2.execution.ExecutionArrayList<Object> b = new org.mvel2.execution.ExecutionArrayList<>(context);
            context.checkAssignGlobalVariable("a", a);
            b.add(a);
            b.add(a);
            a = b;
        }
        context.checkAssignGlobalVariable("a", a);
        org.mvel2.execution.ExecutionArrayList<Object> big = new org.mvel2.execution.ExecutionArrayList<>(context);
        for (int i = 0; i < 40; i++) {
            big.add(a);
        }
        org.mvel2.execution.ExecutionLinkedHashSet<Object> set = new org.mvel2.execution.ExecutionLinkedHashSet<>(context);
        long start = System.nanoTime();
        try {
            set.addAll(big);
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("Max expanded size overflow"));
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000);
    }

    // the argument is compared with every element (o.equals(element)): a map hashes its own keys on the way, a list holds one: both are walked, with one element too, nested in a list too
    public void testAnArgumentThatHoldsAReusedListIsRefusedWhereverItIsNested() {
        String head = "a = ['x']; for (int i = 0; i < 27; i++) { a = [a, a]; } k = []; o = {}; o.put(k, 1); k.add(a); k.add(a); l = [{'q': 1}]; ";
        for (String call : new String[] {"l.indexOf(o)", "l.lastIndexOf(o)", "l.contains(o)", "l.remove(o)", "l.indexOf([o])", "l.contains([o])", "l.remove([o])"}) {
            assertRefused(head + call);
        }
    }

    // an element that the argument is never compared with (another type, another size) costs nothing: no multiple of the argument
    public void testAnArgumentIsNotMultipliedByElementsItIsNotComparedWith() {
        String o = "o = {}; for (int i = 0; i < 500; i++) { o.put('k' + i, i); } ";
        assertEquals(Boolean.FALSE, run(o + "l = []; for (int i = 0; i < 5000; i++) { l.add({'id': i}); } l.contains(o)"));
        assertEquals(-1, run(o + "l = []; for (int i = 0; i < 5000; i++) { l.add(i); } l.indexOf(o)"));
    }

    private static org.mvel2.execution.ExecutionArrayList<Object> reusedList(ExecutionContext context, int depth) {
        org.mvel2.execution.ExecutionArrayList<Object> a = new org.mvel2.execution.ExecutionArrayList<>(context);
        a.add("x");
        for (int i = 0; i < depth; i++) {
            org.mvel2.execution.ExecutionArrayList<Object> b = new org.mvel2.execution.ExecutionArrayList<>(context);
            context.checkAssignGlobalVariable("a", a);
            b.add(a);
            b.add(a);
            a = b;
        }
        context.checkAssignGlobalVariable("a", a);
        return a;
    }

    // a set element or a map key that is compared with an existing one hashes the existing one's members: the receiver is walked too, not only the argument
    public void testAnAddAllOrPutAllThatComparesWithAnElementNobodyWalkedIsRefused() {
        ExecutionContext context = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        org.mvel2.execution.ExecutionArrayList<Object> kk = new org.mvel2.execution.ExecutionArrayList<>(context);
        org.mvel2.execution.ExecutionLinkedHashSet<Object> k = new org.mvel2.execution.ExecutionLinkedHashSet<>(context);
        k.add(kk);
        org.mvel2.execution.ExecutionLinkedHashSet<Object> set = new org.mvel2.execution.ExecutionLinkedHashSet<>(context);
        set.add(k);
        org.mvel2.execution.ExecutionHashMap<Object, Object> map = new org.mvel2.execution.ExecutionHashMap<>(4, context);
        map.put(k, 1);
        org.mvel2.execution.ExecutionArrayList<Object> a = reusedList(context, 22);
        kk.add(a); // after kk is in the set and in the map, as a script can
        kk.add(a);
        org.mvel2.execution.ExecutionHashMap<Object, Object> other = new org.mvel2.execution.ExecutionHashMap<>(4, context);
        other.put(1, 1);
        try {
            set.addAll(java.util.List.of(other.keySet()));
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("Max expanded size overflow"));
        }
        try {
            map.putAll(java.util.Map.of(other.keySet(), 2));
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("Max expanded size overflow"));
        }
        org.mvel2.execution.ExecutionLinkedHashSet<Object> small = new org.mvel2.execution.ExecutionLinkedHashSet<>(context);
        small.add(new org.mvel2.execution.ExecutionArrayList<Object>(context));
        org.mvel2.execution.ExecutionArrayList<Object> one = new org.mvel2.execution.ExecutionArrayList<>(context);
        one.add(2);
        assertTrue(small.addAll(java.util.List.of(one, 3)));
        assertEquals(3, small.size());
        assertEquals(2, run("n = {}; n.put([1], 1); m = {}; m.put([2], 2); n.putAll(m); n.size()"));
    }

    // the entry set of an unmodifiable map asks its own set whether it contains each entry of the argument, whatever the outer call walked
    public void testAnUnmodifiableEntrySetEqualsChecksItsArgument() {
        ExecutionContext context = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        org.mvel2.execution.ExecutionHashMap<Object, Object> map = new org.mvel2.execution.ExecutionHashMap<>(4, context);
        map.put("x", 1);
        java.util.Set<?> entries = ((java.util.Map<Object, Object>) map.toUnmodifiable()).entrySet();
        org.mvel2.execution.ExecutionArrayList<Object> key = new org.mvel2.execution.ExecutionArrayList<>(context);
        java.util.Set<Object> other = new java.util.HashSet<>();
        other.add(new java.util.AbstractMap.SimpleEntry<Object, Object>(key, 1));
        org.mvel2.execution.ExecutionArrayList<Object> a = reusedList(context, 22);
        key.add(a);
        key.add(a);
        context.beginExpansion(new Object());
        try {
            entries.equals(other);
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("Max expanded size overflow"));
        }
    }

    private static final String D22 = "x = ['x']; for (int i = 0; i < 22; i++) { x = [x, x]; } ";

    // entrySet(), the loop over a map and reverse() hash every entry or key: that is one walk of the map, not one per entry (each entry guards only itself)
    public void testAMapThatIsWalkedEntryByEntryIsOneWalk() {
        assertRefused(D22 + "m = {}; for (int i = 0; i < 200; i++) { m.put(i, x); } c = 0; for (e : m) { c++; } c");
        assertRefused(D22 + "m = {}; for (int i = 0; i < 200; i++) { m.put(i, x); } m.entrySet().size()");
        assertRefused(D22 + "m = {}; ks = []; for (int i = 0; i < 50; i++) { k = [i]; m.put(k, 1); ks.add(k); } for (k : ks) { k.add(x); } m.reverse(); m.size()");
        assertRefused(D22 + "m = {}; ks = []; for (int i = 0; i < 50; i++) { k = [i]; m.put(k, 1); ks.add(k); } for (k : ks) { k.add(x); } m.toReversed().size()");
        assertEquals(2, run("m = {'a': 1, 'b': [2]}; c = 0; for (e : m) { c++; } c"));
        assertEquals(Boolean.TRUE, run("m = {'a': 1, 'b': 2}; m.reverse(); m.keys().get(0) == 'b'"));
    }

    public void testContainsValueBoundsTheArgumentTimesTheValues() {
        String two = "c = ['x']; for (int i = 0; i < 22; i++) { c = [c, c]; } d = ['x']; for (int i = 0; i < 22; i++) { d = [d, d]; } x = [c, 'y']; b = [d, 'z']; ";
        assertRefused(two + "m = {}; for (int i = 0; i < 100; i++) { m.put(i, b); } m.containsValue(x)");
        assertEquals(Boolean.TRUE, run("m = {'a': [1], 'b': [2]}; m.containsValue([2])"));
        assertEquals(Boolean.FALSE, run("m = {'a': [1], 'b': [2]}; m.containsValue([3])"));
    }

    // a map inside the argument is hashed for every element it can be compared with, whatever the element holds: a list or an entry that holds the map counts too
    public void testAMapNestedInTheArgumentIsMultiplied() {
        String head = D22 + "o = {}; o.put(x, 1); p = [{'q': 1}]; l = []; for (int i = 0; i < 100; i++) { l.add(p); } ";
        assertRefused(head + "l.indexOf([o])");
        assertRefused(head + "l.contains([o])");
        assertRefused(head + "l.remove([o])");
        assertEquals(-1, run("o = {'a': 1}; l = []; for (int i = 0; i < 100; i++) { l.add([{'q': 1}]); } l.indexOf([o])"));
    }

    // 'contains' on a plain copy compares every element of it, each as its own call: the copy is walked first
    public void testContainsOnAPlainCopyIsWalked() {
        String two = "c = ['x']; for (int i = 0; i < 22; i++) { c = [c, c]; } d = ['x']; for (int i = 0; i < 22; i++) { d = [d, d]; } x = [c, 'y']; b = [d, 'z']; big = []; for (int i = 0; i < 100; i++) { big.add(b); } ";
        assertRefused(two + "(big + 1) contains x");
        assertRefused(two + "big.toArray() contains x");
        assertEquals(Boolean.TRUE, run("l = [1, 2]; (l + 3) contains 3"));
        assertEquals(Boolean.TRUE, run("l = [1, 2]; l.toArray() contains 2"));
    }

    // the cost of a comparison is bounded by the argument: a small argument needs no walk of the receiver, and addAll walks the receiver only for an argument that holds a set
    public void testASmallArgumentOrAContainerlessAddAllDoesNotWalkTheReceiver() {
        assertEquals(Boolean.TRUE, run("t = []; for (int i = 0; i < 10000; i++) { t.add(i); } l = []; for (int i = 0; i < 1000; i++) { l.add(t); } l.contains([1]) == false"));
        long start = System.nanoTime();
        assertEquals(20000, run("s = {'a': 1}.entrySet(); for (int i = 0; i < 20000; i++) { s.addAll([[i]]); } s.size() - 1"));
        assertTrue("addAll walked its receiver", (System.nanoTime() - start) / 1_000_000 < 1500);
    }

    // a set argument hashes the members of the element it is compared with: the receiver is walked, however small the argument
    public void testASetArgumentWalksTheReceiver() {
        ExecutionContext context = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        org.mvel2.execution.ExecutionArrayList<Object> kk = new org.mvel2.execution.ExecutionArrayList<>(context);
        org.mvel2.execution.ExecutionLinkedHashSet<Object> k = new org.mvel2.execution.ExecutionLinkedHashSet<>(context);
        k.add(kk);
        org.mvel2.execution.ExecutionArrayList<Object> list = new org.mvel2.execution.ExecutionArrayList<>(context);
        list.add(k);
        kk.add(reusedList(context, 22)); // after kk is in the set, as a script can
        kk.add(reusedList(context, 22));
        org.mvel2.execution.ExecutionHashMap<Object, Object> other = new org.mvel2.execution.ExecutionHashMap<>(4, context);
        other.put(1, 1);
        try {
            list.indexOf(other.keySet());
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("Max expanded size overflow"));
        }
    }

    public void testAnUnlimitedContextIsNotChecked() {
        executionContext = new ExecutionContext(parserConfig, 0);
        assertEquals(Boolean.TRUE, run("a = ['x']; for (int i = 0; i < 10; i++) { a = [a, a]; } a.toString().length() > 0"));
    }
}
