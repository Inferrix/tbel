package org.mvel2.optimizers.impl.refl.nodes;

import org.mvel2.ExecutionContext;
import org.mvel2.util.TriFunction;
import org.mvel2.compiler.ExecutableStatement;
import org.mvel2.integration.VariableResolverFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.mvel2.DataConversion.convert;

public abstract class InvokableAccessor extends BaseAccessor {
  protected int length;
  protected ExecutableStatement[] parms;
  protected Class[] parameterTypes;
  protected boolean coercionNeeded = false;

  protected Object[] executeAndCoerce(Class[] target, Object elCtx, VariableResolverFactory vars, boolean isVarargs) {
    Object[] values = new Object[length];
    for (int i = 0; i < length && !(isVarargs && i >= length-1); i++) {
      //noinspection unchecked
      values[i] = convert(parms[i].getValue(elCtx, vars), target[i]);
    }
    if (isVarargs) {
      Class<?> componentType = target[length-1].getComponentType();
      Object vararg;
      if (parms == null) {
        vararg = Array.newInstance( componentType, 0 );
      } else {
        vararg = Array.newInstance(componentType, parms.length - length + 1);
        for (int i = length-1; i < parms.length; i++) {
          Array.set(vararg, i - length + 1, convert(parms[i].getValue(elCtx, vars), componentType));
        }
      }
      values[length-1] = vararg;
    }
    return values;
  }

  protected static Object[] checkInvocation(Method method, Object ctx, Object elCtx, Object[] args) {
    if (elCtx instanceof ExecutionContext) {
      ExecutionContext executionContext = (ExecutionContext) elCtx;
      executionContext.checkArgumentsCount(args);
      TriFunction<ExecutionContext, Object, Object[], Object[]> checker = executionContext.getMethodInvocationChecker(method);
      if (checker != null) {
        return checker.apply(executionContext, ctx, convertToParameterTypes(method.getParameterTypes(), method.isVarArgs(), args));
      }
    }
    return args;
  }

  protected static Object[] checkConstruction(Constructor<?> constructor, Object elCtx, Object[] args) {
    if (elCtx instanceof ExecutionContext) {
      ExecutionContext executionContext = (ExecutionContext) elCtx;
      executionContext.checkArgumentsCount(args);
      TriFunction<ExecutionContext, Object, Object[], Object[]> checker = executionContext.getConstructorChecker(constructor);
      if (checker != null) {
        return checker.apply(executionContext, null, convertToParameterTypes(constructor.getParameterTypes(), constructor.isVarArgs(), args));
      }
    }
    return args;
  }

  /**
   * The first call at a call site checks the arguments after they are converted to the parameter types
   * (ReflectiveAccessorOptimizer), while Method.invoke and Constructor.newInstance themselves accept widening (Character, Short, Byte to int,
   * Integer to long, ...) without a conversion. Convert here as well so the checker sees the same types on every call.
   */
  private static Object[] convertToParameterTypes(Class<?>[] types, boolean isVarArgs, Object[] args) {
    if (args == null || args.length == 0) {
      return args;
    }
    if (types.length != args.length) {
      return args;
    }
    Object[] converted = null;
    int last = isVarArgs ? types.length - 1 : types.length;
    for (int i = 0; i < last; i++) {
      Object arg = args[i];
      if (arg == null || (!types[i].isPrimitive() && types[i].isInstance(arg))) {
        continue;
      }
      Object value;
      try {
        //noinspection unchecked
        value = convert(arg, types[i]);
      } catch (Exception e) {
        // DataConversion has no Byte -> Integer style rule; Method.invoke still widens it
        value = widenToPrimitive(arg, types[i]);
      }
      if (value != arg) {
        if (converted == null) {
          converted = args.clone();
        }
        converted[i] = value;
      }
    }
    return converted != null ? converted : args;
  }

  /**
   * Java's primitive widening only (Character/Byte/Short/Integer/Long/Float to a wider primitive), which is what
   * Method.invoke accepts without a conversion. Narrowing is never done here: DataConversion refuses an out of
   * range value on purpose, and the IllegalArgumentException + coercion path must keep reporting it.
   */
  private static Object widenToPrimitive(Object arg, Class<?> type) {
    if (!type.isPrimitive()) {
      return arg;
    }
    if (arg instanceof Character) {
      char c = (Character) arg;
      if (type == int.class) return (int) c;
      if (type == long.class) return (long) c;
      if (type == float.class) return (float) c;
      if (type == double.class) return (double) c;
    } else if (arg instanceof Byte || arg instanceof Short) {
      Number n = (Number) arg;
      if (type == short.class && arg instanceof Byte) return n.shortValue();
      if (type == int.class) return n.intValue();
      if (type == long.class) return n.longValue();
      if (type == float.class) return n.floatValue();
      if (type == double.class) return n.doubleValue();
    } else if (arg instanceof Integer) {
      int n = (Integer) arg;
      if (type == long.class) return (long) n;
      if (type == float.class) return (float) n;
      if (type == double.class) return (double) n;
    } else if (arg instanceof Long) {
      long n = (Long) arg;
      if (type == float.class) return (float) n;
      if (type == double.class) return (double) n;
    } else if (arg instanceof Float) {
      if (type == double.class) return (double) (Float) arg;
    }
    return arg;
  }

  public Class[] getParameterTypes() {
    return parameterTypes;
  }
}
