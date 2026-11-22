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
}
