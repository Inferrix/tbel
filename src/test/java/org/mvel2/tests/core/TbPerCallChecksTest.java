// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.tests.core;

import junit.framework.TestCase;
import org.mvel2.ExecutionContext;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;
import org.mvel2.SandboxedParserConfiguration;
import org.mvel2.ScriptMemoryOverflowException;
import org.mvel2.ScriptRuntimeException;
import org.mvel2.optimizers.OptimizerFactory;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mvel2.MVEL.compileExpression;
import static org.mvel2.MVEL.executeTbExpression;

public class TbPerCallChecksTest extends TestCase {

    private SandboxedParserConfiguration parserConfig;

    @Override
    protected void setUp() throws Exception {
        OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE);
        super.setUp();
        this.parserConfig = ParserContext.enableSandboxedMode();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        ParserContext.disableSandboxedMode();
    }

    private void registerRepeatLimit(AtomicInteger counter, int limit) throws Exception {
        Method repeat = String.class.getMethod("repeat", int.class);
        parserConfig.registerMethodInvocationChecker(repeat, (ctx, target, args) -> {
            counter.incrementAndGet();
            if (((Number) args[0]).intValue() > limit) {
                throw new ScriptMemoryOverflowException("repeat too large");
            }
            return args;
        });
    }

    private Object run(String script) {
        return run(script, new HashMap<String, Object>());
    }

    private Object run(String script, Map<String, Object> vars) {
        Serializable compiled = compileExpression(script);
        return executeTbExpression(compiled, new ExecutionContext(parserConfig, 8L * 1024 * 1024), vars);
    }

    // The checker only looks at Integer (as the production checkers do): it must be handed the argument
    // converted to the int parameter on the second call at a site, as on the first.
    private void assertSecondWideningCallIsChecked(String script, Map<String, Object> vars) throws Exception {
        AtomicInteger counter = new AtomicInteger();
        Method repeat = String.class.getMethod("repeat", int.class);
        parserConfig.registerMethodInvocationChecker(repeat, (ctx, target, args) -> {
            counter.incrementAndGet();
            if (args[0] instanceof Integer && (Integer) args[0] > 100) {
                throw new ScriptMemoryOverflowException("repeat too large");
            }
            return args;
        });
        try {
            run(script, vars);
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertEquals(2, counter.get());
        }
    }

    public void testSecondCallWithCharacterArgumentIsChecked() throws Exception {
        // 'a' = 97 passes, 'e' = 101 must be refused on the second call
        assertSecondWideningCallIsChecked("s = 'ae'; r = ''; for (i = 0; i < 2; i++) { r = 'x'.repeat(s.charAt(i)) }; r",
                new HashMap<String, Object>());
    }

    public void testSecondCallWithShortArgumentIsChecked() throws Exception {
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("sh", Arrays.asList((short) 5, (short) 1000));
        assertSecondWideningCallIsChecked("r = ''; for (i = 0; i < 2; i++) { r = 'x'.repeat(sh[i]) }; r", vars);
    }

    public void testSecondCallWithByteArgumentIsChecked() throws Exception {
        Map<String, Object> vars = new HashMap<String, Object>();
        // Integer first: the first call at the site is made with a convertible value, the Byte arrives second
        vars.put("by", Arrays.asList(5, (byte) 120));
        assertSecondWideningCallIsChecked("r = ''; for (i = 0; i < 2; i++) { r = 'x'.repeat(by[i]) }; r", vars);
    }

    public void testSecondCallAtTheSameSiteIsChecked() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        registerRepeatLimit(counter, 100);
        try {
            run("s = 'a'; r = ''; for (i = 0; i < 2; i++) { r = s.repeat(i == 0 ? 5 : 1000) }; r");
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertEquals(2, counter.get());
        }
    }

    public void testCoercedCallIsChecked() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        registerRepeatLimit(counter, 100);
        try {
            run("s = 'a'; r = ''; for (i = 0; i < 2; i++) { r = s.repeat(i == 0 ? 5L : 1000L) }; r");
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertTrue(counter.get() >= 2);
        }
    }

    public void testOverrideTargetCallIsChecked() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        Method size = java.util.Map.class.getMethod("size");
        parserConfig.registerMethodInvocationChecker(size, (ctx, target, args) -> {
            counter.incrementAndGet();
            return args;
        });
        // the site is first optimised for a List; the Map arrives later, the call is
        // redirected to Map#size (executeOverrideTarget) and must be checked there
        run("for (x : [[1,2], {a: 1}]) { x.size() }");
        assertEquals(1, counter.get());
    }

    public void testCheckerExceptionIsNotWrapped() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        Method repeat = String.class.getMethod("repeat", int.class);
        parserConfig.registerMethodInvocationChecker(repeat, (ctx, target, args) -> {
            if (counter.incrementAndGet() == 2) {
                throw new ScriptRuntimeException("boom");
            }
            return args;
        });
        try {
            run("s = 'a'; r = ''; for (i = 0; i < 2; i++) { r = s.repeat(3) }; r");
            fail("expected ScriptRuntimeException");
        } catch (RuntimeException e) {
            // MVELRuntime.execute re-wraps a ScriptRuntimeException into another ScriptRuntimeException
            // (message "Invalid statement"), keeping the original as the cause; the accessor itself
            // must not turn it into a plain RuntimeException ("cannot invoke method").
            assertEquals(ScriptRuntimeException.class, e.getClass());
            assertNotNull(e.getCause());
            assertEquals(ScriptRuntimeException.class, e.getCause().getClass());
            assertEquals("boom", e.getCause().getMessage());
        }
    }

    public void testNoExecutionContextMeansNoCheck() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        registerRepeatLimit(counter, 100);
        Serializable compiled = compileExpression("s = 'a'; r = ''; for (i = 0; i < 2; i++) { r = s.repeat(i == 0 ? 5 : 1000) }; r");
        Object res = MVEL.executeExpression(compiled, new HashMap<String, Object>());
        assertEquals(1000, ((String) res).length());
        assertEquals(0, counter.get());
    }

    public void testOutOfRangeNarrowingOnSecondCallStillFails() {
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("v", Arrays.asList(1, 4294967297L));
        // the same Long fails on the first call at a site (ConversionException); it must not be
        // silently truncated to 1 on a later call
        try {
            Object res = run("r = ''; for (i = 0; i < 2; i++) { r = 'abcdef'.charAt(v[i]) }; r", vars);
            fail("expected a conversion error, got " + res);
        } catch (RuntimeException expected) {
            assertTrue(String.valueOf(expected.getMessage()), expected.getMessage() != null);
        }
    }

    private Constructor<?> arrayListIntConstructor() throws Exception {
        parserConfig.registerDataType("ArrayList", ArrayList.class, list -> 8L);
        return ArrayList.class.getConstructor(int.class);
    }

    public void testConstructorCheckerRunsOnEveryNew() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        parserConfig.registerConstructorChecker(arrayListIntConstructor(), (ctx, target, args) -> {
            counter.incrementAndGet();
            assertNull(target);
            if ((Integer) args[0] > 100) {
                throw new ScriptMemoryOverflowException("capacity too large");
            }
            return args;
        });
        try {
            run("r = null; for (i = 0; i < 2; i++) { r = new ArrayList(i == 0 ? 5 : 1000) }; r");
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertEquals(2, counter.get());
        }
    }

    public void testConstructorCheckerRunsOnFirstNewWithResidual() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        parserConfig.registerConstructorChecker(arrayListIntConstructor(), (ctx, target, args) -> {
            counter.incrementAndGet();
            if ((Integer) args[0] > 100) {
                throw new ScriptMemoryOverflowException("capacity too large");
            }
            return args;
        });
        try {
            run("new ArrayList(1000).size()");
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertEquals(1, counter.get());
        }
    }

    public void testNegativeCapacityStillFailsInTheJdk() throws Exception {
        parserConfig.registerConstructorChecker(arrayListIntConstructor(), (ctx, target, args) -> {
            if ((Integer) args[0] > 100) {
                throw new ScriptMemoryOverflowException("capacity too large");
            }
            return args;
        });
        try {
            run("new ArrayList(-1)");
            fail("expected the JDK failure");
        } catch (RuntimeException e) {
            Throwable t = e;
            boolean found = false;
            while (t != null) {
                if (t.getMessage() != null && t.getMessage().contains("Illegal Capacity: -1")) {
                    found = true;
                }
                t = t.getCause();
            }
            assertTrue("expected 'Illegal Capacity: -1' in the cause chain of " + e, found);
        }
    }

    public void testConstructorCheckerNotCalledWithoutExecutionContext() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        parserConfig.registerConstructorChecker(arrayListIntConstructor(), (ctx, target, args) -> {
            counter.incrementAndGet();
            return args;
        });
        Serializable compiled = compileExpression("r = null; for (i = 0; i < 2; i++) { r = new ArrayList(i == 0 ? 5 : 1000) }; r");
        Object res = MVEL.executeExpression(compiled, new HashMap<String, Object>());
        assertTrue(res instanceof ArrayList);
        assertEquals(0, counter.get());
    }

    public void testSecondConstructionWithCharacterArgumentIsChecked() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        // the checker only looks at Integer, as the production checkers do
        parserConfig.registerConstructorChecker(arrayListIntConstructor(), (ctx, target, args) -> {
            counter.incrementAndGet();
            if (args[0] instanceof Integer && (Integer) args[0] > 100) {
                throw new ScriptMemoryOverflowException("capacity too large");
            }
            return args;
        });
        try {
            // 'a' = 97 passes, 'e' = 101 must be refused on the second call
            run("s = 'ae'; r = null; for (i = 0; i < 2; i++) { r = new ArrayList(s.charAt(i)) }; r");
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertEquals(2, counter.get());
        }
    }

    public void testSecondConstructionWithOutOfRangeLongStillFails() throws Exception {
        parserConfig.registerConstructorChecker(arrayListIntConstructor(), (ctx, target, args) -> args);
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("v", Arrays.asList(1, 4294967297L));
        // the same Long fails on the first call at a site (ConversionException); it must not be
        // silently truncated to 1 on a later call
        try {
            Object res = run("r = null; for (i = 0; i < 2; i++) { r = new ArrayList(v[i]) }; r", vars);
            fail("expected a conversion error, got " + res);
        } catch (RuntimeException expected) {
            assertTrue(String.valueOf(expected.getMessage()), expected.getMessage() != null);
        }
    }

    private AtomicInteger registerNoArgArrayListCounter() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        parserConfig.registerDataType("ArrayList", ArrayList.class, list -> 8L);
        parserConfig.registerConstructorChecker(ArrayList.class.getConstructor(), (ctx, target, args) -> {
            counter.incrementAndGet();
            return args;
        });
        return counter;
    }

    public void testNoArgConstructorCheckerRunsOnceForAScriptExecutedOnce() throws Exception {
        AtomicInteger counter = registerNoArgArrayListCounter();
        run("new ArrayList().size()");
        assertEquals(1, counter.get());
    }

    public void testNoArgConstructorCheckerRunsOnEveryNewWithResidual() throws Exception {
        AtomicInteger counter = registerNoArgArrayListCounter();
        run("r = 0; for (i = 0; i < 3; i++) { r = new ArrayList().size() }; r");
        assertEquals(3, counter.get());
    }

    public void testNoArgConstructorCheckerRunsOnEveryNewWithoutResidual() throws Exception {
        AtomicInteger counter = registerNoArgArrayListCounter();
        run("r = null; for (i = 0; i < 3; i++) { r = new ArrayList() }; r");
        assertEquals(3, counter.get());
    }

    public void testNoArgConstructorCheckerRefusalOnFirstExecution() throws Exception {
        parserConfig.registerDataType("ArrayList", ArrayList.class, list -> 8L);
        parserConfig.registerConstructorChecker(ArrayList.class.getConstructor(), (ctx, target, args) -> {
            throw new ScriptMemoryOverflowException("denied");
        });
        try {
            run("new ArrayList().size()");
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            // refused on the first execution of the site
        }
    }

    // a with block evaluated its statements against the with object as the execution context, so no checker applied
    private void assertWithBlockIsRefused(String script) throws Exception {
        AtomicInteger counter = new AtomicInteger();
        registerRepeatLimit(counter, 100);
        parserConfig.registerMethodInvocationChecker(String.class.getMethod("indent", int.class), (ctx, target, args) -> {
            counter.incrementAndGet();
            if (((Number) args[0]).intValue() > 100) {
                throw new ScriptMemoryOverflowException("indent too large");
            }
            return args;
        });
        parserConfig.registerDataType("ArrayList", ArrayList.class, list -> 8L);
        parserConfig.registerConstructorChecker(ArrayList.class.getConstructor(int.class), (ctx, target, args) -> {
            counter.incrementAndGet();
            if (((Number) args[0]).intValue() > 100) {
                throw new ScriptMemoryOverflowException("capacity too large");
            }
            return args;
        });
        try {
            run(script);
            fail("expected ScriptMemoryOverflowException for: " + script);
        } catch (ScriptMemoryOverflowException expected) {
            assertTrue(counter.get() >= 1);
        }
    }

    public void testWithBlockMethodCallOnTheWithObjectIsChecked() throws Exception {
        assertWithBlockIsRefused("x = 'a'; with (x) { indent(1500000000) }; 1");
    }

    public void testWithBlockAssignmentFromAMethodCallIsChecked() throws Exception {
        assertWithBlockIsRefused("x = {}; with (x) { y = 'a'.repeat(1500000000).length() }; 1");
    }

    public void testWithBlockConstructorIsChecked() throws Exception {
        assertWithBlockIsRefused("x = {}; with (x) { y = new ArrayList(300000000).size() }; 1");
    }

    public void testWithBlockInsideAFunctionIsChecked() throws Exception {
        assertWithBlockIsRefused("function f(msg) { var x = {}; with (x) { y = 'a'.repeat(1500000000).length() } return {msg: msg}; } f(1)");
    }

    public void testWithBlockCompoundAssignmentAndWithAsAValueAreChecked() throws Exception {
        assertWithBlockIsRefused("x = {y: 1}; with (x) { y += 'a'.repeat(1500000000).length() }; 1");
        assertWithBlockIsRefused("x = {}; r = with (x) { y = 'a'.repeat(1500000000).length() }; 1");
    }

    public void testWithBlockKeepsItsSemanticsWhenNothingIsRefused() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        registerRepeatLimit(counter, 100);
        assertEquals(3, run("x = {}; with (x) { y = 'a'.repeat(3).length() }; x.y"));
        assertEquals(1, counter.get());
    }

    // ---- addendum: the with object stays the context of a with body (names resolve as upstream), the ExecutionContext comes from the thread

    public void testWithBlockResolvesWithObjectFieldsAsMethodArguments() throws Exception {
        parserConfig.addImport("Math", Math.class);
        assertEquals(5, run("x = {v: 5}; with (x) { r = Math.max(v, 3) }; x.r"));
        assertEquals(3, ((String) run("x = {n: 3}; with (x) { y = 'a'.repeat(n) }; x.y")).length());
        assertEquals("q", run("x = {a: 'q'}; with (x) { put('c', a) }; x.c"));
    }

    // the checkers still see the arguments of a with block: String.indent, String.repeat, String.format and ArrayList(int)
    public void testWithBlockCheckersSeeResolvedArguments() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        parserConfig.registerMethodInvocationChecker(String.class.getMethod("repeat", int.class), (ctx, target, args) -> {
            counter.incrementAndGet();
            if (((Number) args[0]).intValue() > 100) {
                throw new ScriptMemoryOverflowException("repeat too large");
            }
            return args;
        });
        parserConfig.registerMethodInvocationChecker(String.class.getMethod("format", String.class, Object[].class), (ctx, target, args) -> {
            counter.incrementAndGet();
            if (((String) args[0]).contains("900000000")) {
                throw new ScriptMemoryOverflowException("format too wide");
            }
            return args;
        });
        // n comes from the with object: the checker gets 1500000000, not null or an unresolved name
        try {
            run("x = {n: 1500000000}; with (x) { y = 'a'.repeat(n).length() }; 1");
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertEquals(1, counter.get());
        }
        try {
            run("x = {}; with (x) { y = String.format('%0900000000d', 1).length() }; 1");
            fail("expected ScriptMemoryOverflowException");
        } catch (ScriptMemoryOverflowException expected) {
            assertEquals(2, counter.get());
        }
    }

    public void testNewArrayLengthIsCheckedInsideWithInlineWithAndProjection() {
        for (String script : new String[] {
                "x = {}; with (x) { z = new byte[2000000000] }; 1",
                "x = {}; x.{ z = new byte[2000000000] }; 1",
                "l = [1, 2]; (new byte[2000000000] in l)"}) {
            try {
                run(script);
                fail("expected ScriptMemoryOverflowException for: " + script);
            } catch (ScriptMemoryOverflowException expected) {
                // refused by checkArray before the array is allocated
            }
        }
        assertEquals(10, ((java.util.List<?>) run("x = {}; with (x) { z = new byte[10] }; x.z")).size());
    }

    // a projection (expr in list) runs the stop flag check per element
    public void testStopFlagIsCheckedInAProjectionLoop() {
        ExecutionContext executionContext = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        executionContext.stop();
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("l", Arrays.asList(1, 2, 3));
        for (String script : new String[] {"($ in l)", "($ in l if $ > 1)"}) {
            try {
                executeTbExpression(compileExpression(script), executionContext, vars);
                fail("expected ScriptExecutionStoppedException for: " + script);
            } catch (org.mvel2.ScriptExecutionStoppedException expected) {
                // checked before the first element
            }
        }
    }

    // the result list of a projection is accounted against the script's memory, element by element
    public void testProjectionResultIsMemoryAccounted() {
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("l", java.util.Collections.nCopies(200, 1));
        for (String script : new String[] {
                "m = {s: 'x'.repeat(2000000)}; (m.s + 'y' in l)",
                "m = {s: 'x'.repeat(2000000)}; (m.s + 'y' in l if true)"}) {
            try {
                run(script, vars);
                fail("expected ScriptMemoryOverflowException for: " + script);
            } catch (ScriptMemoryOverflowException expected) {
                // refused while the list grows, not after 200 x 2MB
            }
        }
        assertEquals(3, ((java.util.List<?>) run("($ * 2 in l)", java.util.Collections.singletonMap("l", Arrays.asList(1, 2, 3)))).size());
    }

    // a projection that returns an object array (split) works as before; the array becomes a list, as on a variable assignment
    public void testProjectionMayReturnAnObjectArray() {
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("strs", Arrays.asList("a,b", "c,d"));
        for (String script : new String[] {"($.split(',') in strs)", "($.split(',') in strs if true)"}) {
            java.util.List<?> result = (java.util.List<?>) run(script, vars);
            assertEquals(script, "[[a, b], [c, d]]", result.toString());
        }
    }

    // the with body takes expressions only (a bare while does not parse), so the loop runs in a function called from the body
    public void testStopFlagWorksInsideAWithBody() throws Exception {
        Serializable compiled = compileExpression("function spin() { while (true) { } return 1; } x = {}; with (x) { y = spin() }; 1");
        ExecutionContext executionContext = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try {
                executeTbExpression(compiled, executionContext, new HashMap<String, Object>());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        thread.setDaemon(true);
        thread.start();
        Thread.sleep(200);
        executionContext.stop();
        boolean finished = done.await(2, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            thread.stop();
        }
        assertTrue("the script did not end after stop()", finished);
        boolean stopped = false;
        for (Throwable t = thrown.get(); t != null; t = t.getCause()) {
            stopped |= t instanceof org.mvel2.ScriptExecutionStoppedException;
        }
        assertTrue(String.valueOf(thrown.get()), stopped);
    }

    // a variable assigned in a function called from a with body is accounted against the script's memory, as at top level
    public void testMemoryAccountingAppliesInsideAWithBody() {
        String grow = "function grow() { t = 'abc'; for (i = 0; i < 12; i++) { t += t } return t.length(); } ";
        try {
            executeTbExpression(compileExpression(grow + "grow()"), new ExecutionContext(parserConfig, 2000), new HashMap<String, Object>());
            fail("expected ScriptMemoryOverflowException at top level");
        } catch (ScriptMemoryOverflowException expected) {
            // the model
        }
        try {
            executeTbExpression(compileExpression(grow + "x = {}; with (x) { y = grow() }; 1"), new ExecutionContext(parserConfig, 2000),
                    new HashMap<String, Object>());
            fail("expected ScriptMemoryOverflowException inside a with body");
        } catch (ScriptMemoryOverflowException expected) {
            // refused as at top level
        }
    }

    public void testCurrentContextIsRestoredAfterEveryRun() throws Exception {
        assertNull(ExecutionContext.of(null));
        run("1 + 1");
        assertNull(ExecutionContext.of(null));
        try {
            run("throw new RuntimeException('boom')");
            fail("expected an exception");
        } catch (RuntimeException expected) {
            // thrown by the script
        }
        assertNull(ExecutionContext.of(null));
        try {
            run("'a'.nope()");
            fail("expected an exception");
        } catch (RuntimeException expected) {
            // unresolvable
        }
        assertNull(ExecutionContext.of(null));
        ExecutionContext outer = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        assertSame(outer, ExecutionContext.of(outer));
        assertNull(ExecutionContext.of("not a context"));
    }

    public void testNestedRunDoesNotReplaceTheOuterContext() throws Exception {
        ExecutionContext outer = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
        AtomicInteger seen = new AtomicInteger();
        java.util.List<Object> observed = new ArrayList<>();
        parserConfig.registerMethodInvocationChecker(String.class.getMethod("repeat", int.class), (ctx, target, args) -> {
            Serializable inner = compileExpression("'z'.toUpperCase()");
            ExecutionContext innerCtx = new ExecutionContext(parserConfig, 8L * 1024 * 1024);
            executeTbExpression(inner, innerCtx, new HashMap<String, Object>());
            observed.add(ExecutionContext.of(null));
            seen.incrementAndGet();
            return args;
        });
        Object r = executeTbExpression(compileExpression("x = {}; with (x) { y = 'a'.repeat(2) }; x.y"), outer, new HashMap<String, Object>());
        assertEquals("aa", r);
        assertEquals(1, seen.get());
        assertSame(outer, observed.get(0));
        assertNull(ExecutionContext.of(null));
    }

    public static class CtxProbe {
        public static boolean hasContext(ExecutionContext ctx, Object ignored) {
            return ctx != null;
        }
    }

    public void testExecutionContextParameterIsInjectedInsideAWithBody() throws Exception {
        parserConfig.addImport("hasContext", new org.mvel2.util.MethodStub(CtxProbe.class.getMethod("hasContext", ExecutionContext.class, Object.class)));
        assertEquals(true, run("hasContext(1)"));
        assertEquals(true, run("x = {}; with (x) { y = hasContext(1) }; x.y"));
        assertEquals(true, run("x = {}; x.{ y = hasContext(1) }; x.y"));
    }

    // no checker is registered for String#charAt: later calls at the site take the plain Method.invoke path
    // (Character widens, Long goes through the IllegalArgumentException + coercion fallback) and still work
    public void testUncheckedMethodLongAndCharacterArgumentsOnLaterCalls() {
        Map<String, Object> longs = new HashMap<String, Object>();
        longs.put("v", Arrays.asList(1, 2L, 3L));
        assertEquals("bcd", run("r = ''; for (i = 0; i < 3; i++) { r = r + 'abcdef'.charAt(v[i]) }; r", longs));
        Map<String, Object> chars = new HashMap<String, Object>();
        chars.put("v", Arrays.asList(1, (char) 2, (char) 3));
        assertEquals("bcd", run("r = ''; for (i = 0; i < 3; i++) { r = r + 'abcdef'.charAt(v[i]) }; r", chars));
    }
}
