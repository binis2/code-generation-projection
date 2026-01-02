package net.binis.codegen.projection.provider;

/*-
 * #%L
 * code-generator-projection
 * %%
 * Copyright (C) 2021 - 2026 Binis Belev
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import net.binis.codegen.factory.CodeFactory;
import net.binis.codegen.map.Mapper;
import net.binis.codegen.projection.interfaces.CodeProxyControl;
import net.binis.codegen.projection.objects.CodeProxyBase;
import net.binis.codegen.tools.Reflection;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.isNull;
import static net.binis.codegen.projection.tools.ProjectionTools.decapitalize;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class MapBackedProjectionProvider {

    private static final Map<Class<?>, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final ByteBuddy BYTE_BUDDY = new ByteBuddy()
            .with(TypeValidation.DISABLED);

    @SuppressWarnings("unchecked")
    public static <T> T create(Map<String, Object> map, Class<T>... projections) {
        if (projections.length != 0) {
            try {
                var proxyClass = getOrCreateProxyClass(projections);
                var instance = CodeFactory.create(proxyClass, map);
                return (T) instance;
            } catch (Exception e) {
                throw new RuntimeException("Failed to create projection instance for " + projections[0].getName(), e);
            }
        } else {
            throw new RuntimeException("Projection class not specified");
        }
    }

    private static Class<?> getOrCreateProxyClass(Class<?>... projections) {
        return CLASS_CACHE.computeIfAbsent(projections[0], cls -> {
            try {
                return BYTE_BUDDY
                        .subclass(TypeDescription.Generic.Builder
                                .parameterizedType(CodeProxyBase.class, Map.class)
                                .build())
                        .name(cls.getName() + "$MapBackedProxy")
                        .implement(projections)
                        .defineConstructor(Visibility.PUBLIC)
                        .withParameters(Map.class)
                        .intercept(MethodCall.invoke(CodeProxyBase.class.getDeclaredConstructor())
                                .andThen(FieldAccessor.ofField("value").setsArgumentAt(0)))
                        .method(any())
                        .intercept(MethodDelegation.to(MapInterceptor.class))
                        .make()
                        .load(projections[0].getClassLoader())
                        .getLoaded();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create proxy class for " + projections[0].getName(), e);
            }
        });
    }

    public static class MapInterceptor {

        @RuntimeType
        public static Object intercept(
                @FieldValue("value") Map<String, Object> map,
                @Origin Method method,
                @AllArguments Object[] args) {

            var name = method.getName();
            int paramCount = method.getParameterCount();

            if (paramCount == 0) {
                if (name.startsWith("get") && name.length() > 3) {
                    return handleGetter(map, method, name.substring(3));
                } else if (name.startsWith("is") && name.length() > 2) {
                    return map.get(decapitalize(name.substring(2)));
                } else if (name.equals("toString")) {
                    return map.toString();
                } else if (name.equals("hashCode")) {
                    return map.hashCode();
                }
            }

            if (paramCount == 1 && name.startsWith("set") && name.length() > 3) {
                map.put(decapitalize(name.substring(3)), args[0]);
                return null;
            }

            if (paramCount == 1 && name.equals("equals")) {
                return handleEquals(map, args[0]);
            }

            throw new UnsupportedOperationException("Method not supported: " + method);
        }

        @SuppressWarnings("unchecked")
        private static Object handleGetter(Map<String, Object> map, Method method, String propertyName) {
            var key = decapitalize(propertyName);
            var result = map.get(key);

            if (isNull(result)) {
                if (method.getReturnType().isPrimitive()) {
                    if (int.class.equals(method.getReturnType())) {
                        return 0;
                    }
                    if (long.class.equals(method.getReturnType())) {
                        return 0L;
                    }
                    if (double.class.equals(method.getReturnType())) {
                        return 0D;
                    }
                    if (float.class.equals(method.getReturnType())) {
                        return 0F;
                    }
                    if (boolean.class.equals(method.getReturnType())) {
                        return false;
                    }
                    if (byte.class.equals(method.getReturnType())) {
                        return (byte) 0;
                    }
                    if (short.class.equals(method.getReturnType())) {
                        return (short) 0;
                    }
                    if (char.class.equals(method.getReturnType())) {
                        return (char) 0;
                    }
                } else {
                    return null;
                }
            }

            Class<?> returnType = method.getReturnType();

            if (returnType.isAssignableFrom(result.getClass())
                    && !Collection.class.isAssignableFrom(result.getClass())) {
                return result;
            }

            if (result instanceof List list && List.class.equals(returnType)) {
                var type = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
                if (!Object.class.equals(type)) {
                    var cls = Reflection.loadClass(((Class<?>) type).getName());
                    return list.stream().map(o -> Mapper.convert(o, cls)).toList();
                }
            }

            return Mapper.convert(result, returnType);
        }

        private static boolean handleEquals(Map<String, Object> map, Object other) {
            if (other == null) return false;

            if (other instanceof CodeProxyControl control) {
                return map.equals(control._object$());
            }

            if (other instanceof CodeProxyBase<?> base) {
                Object unwrapped = CodeProxyBase.unwrap(other);
                return map.equals(unwrapped);
            }

            return false;
        }
    }
}
