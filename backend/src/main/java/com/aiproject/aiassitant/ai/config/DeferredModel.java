package com.aiproject.aiassitant.ai.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

/** Keep credential-dependent clients out of application startup; preserve every interface overload. */
final class DeferredModel {
    private DeferredModel() {}

    static <T> T create(Class<T> type, Supplier<T> supplier) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "Deferred " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new IllegalStateException("Unexpected Object method");
                };
            }
            try { return method.invoke(supplier.get(), args); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        }));
    }
}
