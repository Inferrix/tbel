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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
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
        Serializable compiled = compileExpression(script);
        return executeTbExpression(compiled, new ExecutionContext(parserConfig, 8L * 1024 * 1024), new HashMap<String, Object>());
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
}
