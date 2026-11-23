// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2;

import org.mvel2.execution.ExecutionArrayList;
import org.mvel2.execution.ExecutionObject;
import org.mvel2.util.TriFunction;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.BaseStream;
import java.util.stream.Collectors;

public class ExecutionContext implements Serializable {

    private final Map<Object, ValueReference> valueReferenceMap = new IdentityHashMap<>();
    private final Map<VarKey, Object> variablesMap = new HashMap<>();

    private final SandboxedParserConfiguration parserConfig;
    private final long maxAllowedMemory;

    private final int maxAllowedMethodArgs;

    private int stackLevel = 0;

    private long memorySize = 0;

    private final AtomicInteger idSequence = new AtomicInteger(0);

    private volatile boolean stopped = false;

    // the context of the script running on this thread (set by MVEL.executeTbExpression); inside with blocks, x.{...} and
    // projections the nodes get the with-object / element as ctx, not the ExecutionContext
    private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();

    /**
     * @return ctx if it is an ExecutionContext, else the context of the script running on this thread (null when none)
     */
    public static ExecutionContext of(Object ctx) {
        return ctx instanceof ExecutionContext ? (ExecutionContext) ctx : CURRENT.get();
    }

    static ExecutionContext setCurrent(ExecutionContext ctx) {
        ExecutionContext previous = CURRENT.get();
        CURRENT.set(ctx);
        return previous;
    }

    static void restoreCurrent(ExecutionContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public ExecutionContext(SandboxedParserConfiguration parserConfig) {
        this(parserConfig, -1);
    }

    public ExecutionContext(SandboxedParserConfiguration parserConfig, long maxAllowedMemory) {
        this(parserConfig, maxAllowedMemory, 10);
    }

    public ExecutionContext(SandboxedParserConfiguration parserConfig, long maxAllowedMemory, int maxAllowedMethodArgs) {
        this.parserConfig = parserConfig;
        this.maxAllowedMemory = maxAllowedMemory;
        this.maxAllowedMethodArgs = maxAllowedMethodArgs;
    }

    public void checkExecution() {
        if (stopped) {
            throw new ScriptExecutionStoppedException("Script execution is stopped!");
        }
    }

    public void checkArgumentsCount(Object[] args) {
        if (args != null && maxAllowedMethodArgs > 0 && args.length > maxAllowedMethodArgs) {
            throw new ScriptRuntimeException("Maximum method arguments count overflow (" + args.length + " > " + maxAllowedMethodArgs + ")!");
        }
    }

    public TriFunction<ExecutionContext, Object, Object[], Object[]> getMethodInvocationChecker(Method method) {
        return this.parserConfig.getMethodInvocationChecker(method);
    }

    public TriFunction<ExecutionContext, Object, Object[], Object[]> getConstructorChecker(Constructor<?> constructor) {
        return this.parserConfig.getConstructorChecker(constructor);
    }

    public Object[] checkInvocation(Method method, Object ctx, Object[] args) {
        checkArgumentsCount(args);
        checkPlainOperands(ctx, args);
        TriFunction<ExecutionContext, Object, Object[], Object[]> invocationChecker = this.parserConfig.getMethodInvocationChecker(method);
        if (invocationChecker != null) {
            return invocationChecker.apply(this, ctx, args);
        }
        return args;
    }

    public Object[] checkConstruction(Constructor<?> constructor, Object[] args) {
        checkArgumentsCount(args);
        checkConstructorArguments(args);
        TriFunction<ExecutionContext, Object, Object[], Object[]> constructorChecker = this.parserConfig.getConstructorChecker(constructor);
        if (constructorChecker != null) {
            return constructorChecker.apply(this, null, args);
        }
        return args;
    }

    public void stop() {
        this.stopped = true;
    }

    public void enterStack() {
        this.stackLevel++;
    }

    public void leaveStack() {
        int level = this.stackLevel;
        List<VarKey> keysToRemove = variablesMap.keySet().stream().filter(varKey -> varKey.level == level).collect(Collectors.toList());
        keysToRemove.forEach(key -> this.checkAssignVariable(key, null));
        this.stackLevel--;
    }

    public void checkArray(Class<?> componentType, int... dimensions) {
        if (componentType.isPrimitive()) {
            long arraySize = 1;
            for (int i =0; i < dimensions.length; i++) {
                arraySize *= dimensions[i];
            }
            long arrayMemorySize = arraySize * componentTypeSize(componentType);
            if (maxAllowedMemory > 0 && arrayMemorySize > maxAllowedMemory / 2) {
                throw new ScriptMemoryOverflowException("Max array length overflow (" + arrayMemorySize + " > " + maxAllowedMemory / 2 + ")!");
            }
        } else {
            throw new ScriptRuntimeException("Unsupported array type: " + componentType);
        }
    }

    public Object checkAssignGlobalVariable(String varName, Object value) {
        return this.checkAssignVariable(new VarKey(0, varName), value);
    }

    public Object checkAssignLocalVariable(String varName, Object value) {
        return this.checkAssignVariable(new VarKey(this.stackLevel, varName), value);
    }

    private Object checkAssignVariable(VarKey varKey, Object value) {
        if (this.variablesMap.containsKey(varKey)) {
            Object prevValue = this.variablesMap.get(varKey);
            ValueReference reference = valueReferenceMap.get(prevValue);
            if (reference != null) {
                if (reference.removeReference(varKey)) {
                    valueReferenceMap.remove(prevValue);
                    memorySize -= reference.getSize();
                }
            }
        }
        if (value != null) {
            Object converted = convertValue(value);
            this.variablesMap.put(varKey, converted);
            ValueReference reference = valueReferenceMap.computeIfAbsent(value, o -> {
                ValueReference newReference = new ValueReference();
                newReference.setSize(getValueSize(converted));
                memorySize += newReference.getSize();
                return newReference;
            });
            reference.addReference(varKey);
            value = converted;
        } else {
            this.variablesMap.remove(varKey);
        }
        this.checkMemoryLimit();
        return value;
    }

    public long onValRemove(ExecutionObject obj, Object val) {
        return this.onValRemove(obj, null, val);
    }

    public long onValRemove(ExecutionObject obj, Object key, Object val) {
        long valSize = getValueSize(val);
        if (key != null) {
            valSize += getValueSize(key);
        }
        ValueReference reference = valueReferenceMap.get(obj);
        if (reference != null) {
            reference.setSize(reference.getSize() - valSize);
        }
        memorySize -= valSize;
        return valSize;
    }

    public long onValAdd(ExecutionObject obj, Object val) {
        return this.onValAdd(obj, null, val);
    }

    public long onValAdd(ExecutionObject obj, Object key, Object val) {
        long valSize = getValueSize(val);
        if (key != null) {
            valSize += getValueSize(key);
        }
        ValueReference reference = valueReferenceMap.get(obj);
        if (reference != null) {
            reference.setSize(reference.getSize() + valSize);
        }
        memorySize += valSize;
        this.checkMemoryLimit();
        return valSize;
    }

    public void dumpVars() {
        System.out.println("VARS:");
        variablesMap.forEach((key, value) -> System.out.println(key + " = " + value));
    }

    public void dumpValueReferences() {
        System.out.println("VALUE REFERENCES:");
        valueReferenceMap.forEach((key, value) -> System.out.println(key + " = " + value));
    }

    public long getMemorySize() {
        return memorySize;
    }

    public long getMaxAllowedMemory() {
        return maxAllowedMemory;
    }

    private static final int MAX_NESTING_DEPTH = 1000;

    // what a walk of the structure may add up to, as a multiple of the memory: the expanded size of ordinary data is about 2.5 times what the accounting counts for it
    private static final long EXPANSION_FACTOR = 4;

    private final Map<Object, Long> expansionScratch = new IdentityHashMap<>();
    private boolean expanding;

    /**
     * The memory accounting counts a list that is already held by a variable or by another list as a reference (4 bytes), and forgets it
     * when its variable is assigned again: a list that holds the same list twice, 24 levels deep, is 16 million elements when it is walked.
     * Refuses a collection, a map, an entry, an array, an Optional or an Iterable whose expanded size (every element counted as many times as it
     * is reached) is above a multiple of the memory of the script, a cycle, an Iterator (it cannot be walked without being consumed) and a nesting
     * deeper than 1000, before toString, hashCode, equals, join, sort, a JSON writer or the caller that serializes the result walks it.
     * The walk visits every object once, so it costs the size of the structure, not the size of its expansion.
     */
    public void checkExpansion(Object root) {
        if (maxAllowedMemory > 0 && walkable(root)) {
            try {
                expandedSize(root, 0);
            } finally {
                if (!expansionScratch.isEmpty()) {
                    expansionScratch.clear();
                }
            }
        }
    }

    /** Refuses a call that does the work of walking root once for each of count elements (count x the expanded size of root above the bound). */
    public void checkExpansionTimes(Object root, long count) {
        if (maxAllowedMemory > 0 && count > 1 && walkable(root)) {
            try {
                long size = expandedSize(root, 0);
                long limit = maxAllowedMemory * EXPANSION_FACTOR;
                if (size > 0 && count > limit / size) {
                    throw new ScriptMemoryOverflowException("Max expanded size overflow (" + count + " x " + size + " > " + limit + ")!");
                }
            } finally {
                if (!expansionScratch.isEmpty()) {
                    expansionScratch.clear();
                }
            }
        }
    }

    /**
     * Checks the structure unless an outer call already did (the inner toString, hashCode and equals of the elements are part of that walk:
     * checking each of them again costs depth x size). Returns true to the outermost call, that must pass it to endExpansion.
     */
    public boolean beginExpansion(Object root) {
        if (expanding || maxAllowedMemory <= 0) {
            return false;
        }
        checkExpansion(root);
        expanding = true;
        return true;
    }

    public void endExpansion(boolean outermost) {
        if (outermost) {
            expanding = false;
        }
    }

    /**
     * A plain java.util collection, map or array (List.of, Arrays.asList, a copy such as new ArrayList(list), a view, toArray()) is no
     * ExecutionObject: its toString, hashCode and equals are the JDK's and nothing accounts or guards it, and one that holds the same nested list
     * twice is 2^depth when it is printed. The container classes of this library guard themselves; a plain one is walked where a script uses it:
     * as the receiver or an argument of a call, as an argument of a constructor and as an operand of a concatenation or of == and !=. A type with its own
     * size function (a calculated field argument) is accounted by that function and is left alone.
     */
    private boolean plainContainer(Object value) {
        return value != null && !(value instanceof ExecutionObject)
                && walkable(value) && !(value instanceof Iterator) && !(value instanceof BaseStream)
                && this.parserConfig.getValueSizeFunction(value.getClass()) == null;
    }

    public void checkPlainOperands(Object target, Object[] args) {
        if (maxAllowedMemory <= 0) {
            return;
        }
        if (plainContainer(target)) {
            checkExpansion(target);
        }
        if (args != null) {
            for (Object arg : args) {
                if (plainContainer(arg)) {
                    checkExpansion(args);
                    return;
                }
            }
        }
    }

    // a constructor keeps what it is given in an object this library cannot see into (a calculated field argument, a copy): any container is walked
    public void checkConstructorArguments(Object[] args) {
        if (maxAllowedMemory <= 0 || args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg != null && !(arg instanceof CharSequence) && !(arg instanceof Number) && walkable(arg)) {
                checkExpansion(args);
                return;
            }
        }
    }

    /** the operand of a concatenation or of == and !=: it is printed, or compared, with the toString or equals of its class */
    public static void checkOperand(Object value) {
        if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof ExecutionObject) {
            return;
        }
        ExecutionContext context = of(null);
        if (context != null && context.maxAllowedMemory > 0 && context.plainContainer(value)) {
            context.checkExpansion(value);
        }
    }

    /** String.valueOf of an operand that an operator prints (a pattern match, a contains, the message of a failed comparison): a plain container is walked first */
    public static String stringOf(Object value) {
        checkOperand(value);
        return String.valueOf(value);
    }

    public static boolean walkable(Object value) {
        return value instanceof Collection || value instanceof Map || value instanceof Map.Entry || value instanceof Object[] || value instanceof Optional
                || value instanceof Iterable || value instanceof Iterator || value instanceof BaseStream;
    }

    private long expandedSize(Object value, int depth) {
        if (value == null) {
            return 0;
        }
        if (value instanceof CharSequence) {
            return ((CharSequence) value).length();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return 8;
        }
        if (value.getClass().isArray() && value.getClass().getComponentType().isPrimitive()) {
            return Array.getLength(value);
        }
        if (!walkable(value)) {
            return 8;
        }
        if (value instanceof Iterator || value instanceof BaseStream) {
            throw new ScriptRuntimeException("Unsupported value type: " + value.getClass());
        }
        // a type with its own size function (a calculated field argument that is iterable, a time series) is accounted by that function
        if (!(value instanceof ExecutionObject) && this.parserConfig.getValueSizeFunction(value.getClass()) != null) {
            return 8;
        }
        Long known = expansionScratch.isEmpty() ? null : expansionScratch.get(value);
        if (known != null) {
            if (known < 0) {
                throw new ScriptRuntimeException("A structure that holds itself is not supported!");
            }
            return known;
        }
        if (depth > MAX_NESTING_DEPTH) {
            throw new ScriptMemoryOverflowException("Max nesting depth overflow (> " + MAX_NESTING_DEPTH + ")!");
        }
        // a structure with no nested structure in it needs neither the memo nor the cycle check: the ordinary list costs no allocation
        boolean marked = false;
        long size = 0;
        if (value instanceof Map.Entry) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) value;
            marked = mark(value, mark(value, false, entry.getKey()), entry.getValue());
            size = 4 + expandedSize(entry.getKey(), depth + 1) + expandedSize(entry.getValue(), depth + 1);
        } else if (value instanceof Map) {
            // forEach, not entrySet: the entry set of an ExecutionHashMap is a new set, hashed and accounted for every time it is asked for
            long[] total = {0};
            boolean[] nested = {false};
            ((Map<?, ?>) value).forEach((key, element) -> {
                nested[0] = mark(value, mark(value, nested[0], key), element);
                total[0] += 4 + expandedSize(key, depth + 1) + expandedSize(element, depth + 1);
                checkExpandedSize(total[0]);
            });
            size = total[0];
            marked = nested[0];
        } else if (value instanceof Optional) {
            Object element = ((Optional<?>) value).orElse(null);
            marked = mark(value, false, element);
            size = 2 + expandedSize(element, depth + 1);
        } else {
            Iterable<?> elements = value instanceof Object[] ? Arrays.asList((Object[]) value) : (Iterable<?>) value;
            for (Object element : elements) {
                marked = mark(value, marked, element);
                size += 2 + expandedSize(element, depth + 1);
                checkExpandedSize(size);
            }
        }
        checkExpandedSize(size);
        if (marked) {
            expansionScratch.put(value, size);
        }
        return size;
    }

    // in progress: reached again from below it is a cycle
    private boolean mark(Object parent, boolean marked, Object child) {
        if (!marked && walkable(child)) {
            expansionScratch.put(parent, -1L);
            return true;
        }
        return marked;
    }

    private void checkExpandedSize(long size) {
        if (size > maxAllowedMemory * EXPANSION_FACTOR) {
            throw new ScriptMemoryOverflowException("Max expanded size overflow (" + size + " > " + maxAllowedMemory * EXPANSION_FACTOR + ")!");
        }
    }

    private void checkMemoryLimit() {
        if (maxAllowedMemory > 0 && memorySize > maxAllowedMemory) {
            throw new ScriptMemoryOverflowException("Script memory overflow (" + memorySize + " > " + maxAllowedMemory + ")!");
        }
    }

    private Object convertValue(Object value) {
        if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            value = new ExecutionArrayList(Arrays.asList((Object[])value), this);
        }
        return value;
    }

    private long getValueSize(Object value) {
        if (value == null) {
            return 0;
        }
        Function<Object, Long> valueSizeFunction = this.parserConfig.getValueSizeFunction(value.getClass());
        if (valueSizeFunction != null) {
            return valueSizeFunction.apply(value);
        } else if (value instanceof ExecutionObject) {
            if (valueReferenceMap.containsKey(value)) {
                return 4;
            } else {
                return ((ExecutionObject) value).memorySize();
            }
        } else if (value instanceof String) {
            return ((String) value).getBytes().length;
        } else if (value instanceof Byte) {
            return 1;
        } else if (value instanceof Character) {
            return 1;
        } else if (value instanceof Short) {
            return 2;
        } else if (value instanceof Integer) {
            return 4;
        } else if (value instanceof Long) {
            return 8;
        } else if (value instanceof Float) {
            return 4;
        } else if (value instanceof Double) {
            return 8;
        } else if (value instanceof BigInteger) {
            return ((BigInteger) value).bitLength()/8 + 1;
        } else if (value instanceof Boolean) {
            return 1;
        } else if (value instanceof UUID) {
            return 16;
        } else if (value instanceof Date) {
            return 8;
        } else if (value.getClass().isArray() && value.getClass().getComponentType().isPrimitive()) {
            return (long) Array.getLength(value) * componentTypeSize(value.getClass().getComponentType());
        } else {
            throw new ScriptRuntimeException("Unsupported value type: " + value.getClass());
        }
    }

    private static int componentTypeSize(Class<?> componentType) {
        if (byte.class.equals(componentType)) {
            return 1;
        } else if (char.class.equals(componentType)) {
            return 1;
        } else if (short.class.equals(componentType)) {
            return 2;
        } else if (int.class.equals(componentType)) {
            return 4;
        } else if (long.class.equals(componentType)) {
            return 8;
        } else if (float.class.equals(componentType)) {
            return 4;
        } else if (double.class.equals(componentType)) {
            return 8;
        } else if (boolean.class.equals(componentType)) {
            return 1;
        } else {
            throw new ScriptRuntimeException("Unsupported array primitive type: " + componentType);
        }
    }

    private static final class VarKey {
        private final int level;
        private final String name;
        VarKey(int level, String name) {
            this.level = level;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VarKey varKey = (VarKey) o;
            return level == varKey.level && Objects.equals(name, varKey.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(level, name);
        }
    }

    private static final class ValueReference {
        private final Set<VarKey> references = new HashSet<>();
        private long size = 0;

        void addReference(VarKey varKey) {
            references.add(varKey);
        }

        boolean removeReference(VarKey varKey) {
            references.remove(varKey);
            return references.isEmpty();
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        @Override
        public String toString() {
            return "ValueReference[size: " + size + "; references: " + references + "]";
        }
    }
}
