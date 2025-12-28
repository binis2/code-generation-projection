package net.binis.codegen.projection.provider;

/*-
 * #%L
 * code-generator-projection
 * %%
 * Copyright (C) 2021 - 2024 Binis Belev
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

import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.factory.CodeFactory;
import net.binis.codegen.factory.ProjectionInstantiation;
import net.binis.codegen.factory.ProjectionProvider;
import net.binis.codegen.factory.ProxyProvider;
import net.binis.codegen.objects.Pair;
import net.binis.codegen.projection.exception.ProjectionCreationException;
import net.binis.codegen.projection.interfaces.CodeProxyControl;
import net.binis.codegen.projection.objects.CodeMethodImplementation;
import net.binis.codegen.projection.objects.CodeProjectionProxyList;
import net.binis.codegen.projection.objects.CodeProjectionProxySet;
import net.binis.codegen.projection.objects.CodeProxyBase;
import net.binis.codegen.tools.Reflection;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static net.binis.codegen.projection.tools.ProjectionTools.decapitalize;

@Slf4j
public class CodeGenProjectionProvider implements ProjectionProvider, ProxyProvider {

    protected static final String PROXY_BASE = "net/binis/codegen/projection/objects/CodeProxyBase";
    public static final String OBJECT_DESC = "Ljava/lang/Object;";
    public static final String FIELD_NAME = "value";
    protected static final Map<Class, Class> proxies = new ConcurrentHashMap<>();


    static {
        CodeFactory.registerCustomProxyClass(List.class, (cls, projections) -> obj -> new CodeProjectionProxyList((List) obj, projections));
        CodeFactory.registerCustomProxyClass(Set.class, (cls, projections) -> obj -> new CodeProjectionProxySet((Set) obj, projections));
    }

    @Override
    public ProjectionInstantiation create(Class<?> cls, Class<?>... projections) {
        var c = createObject(cls, projections);
        return o -> {
            try {
                return c.newInstance(o);
            } catch (Exception e) {
                throw new ProjectionCreationException("Unable to create projection for class: " + cls.getCanonicalName(), e);
            }
        };
    }

    protected Constructor<?> createObject(Class<?> cls, Class<?>[] projections) {
        try {
            return createProjectionClass(cls, projections).getDeclaredConstructor(cls);
        } catch (NoSuchMethodException e) {
            throw new ProjectionCreationException("Unable to find constructor for proxy class: " + cls.getCanonicalName(), e);
        }
    }

    protected Class<?> createProjectionClass(Class<?> cls, Class<?>[] projections) {
        var implement = new ArrayList<>(Arrays.asList(projections));
        implement.add(CodeProxyControl.class);
        var desc = TypeDefinition.Sort.describe(cls).getActualName().replace('.', '/');
        var objectName = "net.binis.projection." + cls.getSimpleName();
        for (var p : projections) {
            objectName += "$" + p.getSimpleName();
        }

        DynamicType.Builder<?> type = new ByteBuddy()
                .subclass(CodeProxyBase.class)
                .visit(new EnableFramesComputing())
                .name(objectName)
                .implement(implement)
                .defineConstructor(Opcodes.ACC_PUBLIC).withParameter(cls).intercept(new CodeMethodImplementation() {
                    @Override
                    public ByteCodeAppender.Size code(MethodVisitor methodVisitor, Implementation.Context implementationContext, MethodDescription instrumentedMethod) {
                        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, PROXY_BASE, "<init>", "()V", false);
                        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                        methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
                        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, PROXY_BASE, FIELD_NAME, OBJECT_DESC);
                        methodVisitor.visitInsn(Opcodes.RETURN);
                        return new ByteCodeAppender.Size(2, 2);
                    }
                })
                .defineMethod("_object$", Object.class, Opcodes.ACC_PUBLIC).intercept(new CodeMethodImplementation() {
                    @Override
                    public ByteCodeAppender.Size code(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, PROXY_BASE, FIELD_NAME, OBJECT_DESC);
                        methodVisitor.visitInsn(Opcodes.ARETURN);
                        return new ByteCodeAppender.Size(1, 1);
                    }
                });

        var methods = new HashMap<String, List<Class<?>[]>>();
        for (var p : projections) {
            type = type.annotateType(p.getDeclaredAnnotations());
            type = handleInterface(type, cls, p, desc, methods);
        }

        return type.make()
                .load(nonNull(cls.getClassLoader()) ? cls.getClassLoader() : this.getClass().getClassLoader())
                .getLoaded();
    }

    protected DynamicType.Builder<?> handleInterface(DynamicType.Builder<?> type, Class<?> cls, Class<?> intf, String desc, Map<String, List<Class<?>[]>> methods) {
        for (var mtd : intf.getDeclaredMethods()) {
            if ((mtd.getModifiers() & Modifier.STATIC) == 0) {
                type = handleMethod(type, cls, mtd, desc, methods);
            }
        }

        for (var i : intf.getInterfaces()) {
            type = handleInterface(type, cls, i, desc, methods);
        }

        return type;
    }

    protected DynamicType.Builder<?> handleMethod(DynamicType.Builder<?> type, Class<?> cls, Method mtd, String desc, Map<String, List<Class<?>[]>> methods) {
        var types = mtd.getParameterTypes();
        var ret = mtd.getReturnType();
        if (!methodExists(methods, mtd, types)) {
            var isVoid = void.class.equals(ret);
            try {
                if (Map.class.isAssignableFrom(cls)) {
                    type = handleMapMethod(type, mtd, desc, types, ret);
                } else {
                    var m = findMethod(cls, mtd.getName(), types);
                    type = handleDeclaredMethod(type, mtd, m, desc, types, ret);
                }
            } catch (NoSuchMethodException e) {
                var t = checkPath(type, cls, mtd, desc, types, ret, isVoid);
                if (isNull(t)) {
                    if (!mtd.isDefault()) {
                        type = handleUndeclaredMethod(type, mtd, types, ret, isVoid);
                    }
                } else {
                    type = t;
                }
            }
        }

        return type;
    }

    protected DynamicType.Builder<?> handleUndeclaredMethod(DynamicType.Builder<?> type, Method mtd, Class<?>[] types, Class<?> ret, boolean isVoid) {
        log.info("Handle undeclared method: {}", mtd.toString());
        return type.defineMethod(mtd.getName(), ret, Opcodes.ACC_PUBLIC).withParameters(types).intercept(new CodeMethodImplementation() {
            @Override
            public ByteCodeAppender.Size code(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                if (isVoid) {
                    methodVisitor.visitInsn(Opcodes.RETURN);
                    return new ByteCodeAppender.Size(0, types.length + 1);
                } else {
                    defaultReturn(methodVisitor, ret);
                    return new ByteCodeAppender.Size(1 + (ret.isPrimitive() ? 1 : 0), types.length + 1);
                }
            }
        }).annotateMethod(mtd.getDeclaredAnnotations());
    }

    protected DynamicType.Builder<?> handleDeclaredMethod(DynamicType.Builder<?> type, Method mtd, Method m, String desc, Class<?>[] types, Class<?> ret) {
        if (CodeFactory.isCustomProxyClass(mtd.getReturnType())) {
            if (mtd.getGenericReturnType() instanceof ParameterizedType mtdType && m.getGenericReturnType() instanceof ParameterizedType mType) {
                var generics = mtdType.getActualTypeArguments();
                if (needProjection(generics, mType.getActualTypeArguments())) {
                    return handleCustomClassProjection(type, mtd, m, desc, types, ret, generics);
                }
            }
        }

        if (ret.isInterface() && !mtd.getReturnType().equals(m.getReturnType())) {
            return handleProjection(type, mtd, m, desc, types, ret);
        }

        return type.defineMethod(mtd.getName(), ret, Opcodes.ACC_PUBLIC).withParameters(types).intercept(new CodeMethodImplementation() {
            @Override
            public ByteCodeAppender.Size code(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitFieldInsn(Opcodes.GETFIELD, PROXY_BASE, FIELD_NAME, OBJECT_DESC);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, desc);
                var offset = loadParams(methodVisitor, types);
                if (ret.isAssignableFrom(m.getReturnType())) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, desc, mtd.getName(), calcDescriptor(types, ret), false);
                } else {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, desc, mtd.getName(), calcDescriptor(types, m.getReturnType()), false);
                    methodVisitor.visitVarInsn(Opcodes.ASTORE, 1);
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
                    var restDesc = Type.getType(ret);
                    methodVisitor.visitLdcInsn(restDesc);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "net/binis/codegen/map/Mapper", "convert", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, restDesc.getInternalName());
                }
                var locals = offset;
                var retOp = getReturnOpcode(ret);
                methodVisitor.visitInsn(retOp.getKey());
                offset += retOp.getValue();

                return new ByteCodeAppender.Size(offset, locals);
            }
        }).annotateMethod(mtd.getDeclaredAnnotations());
    }

    protected DynamicType.Builder<?> handleMapMethod(DynamicType.Builder<?> type, Method mtd, String desc, Class<?>[] types, Class<?> ret) {
        var key = getKeyName(mtd.getName());

        return type.defineMethod(mtd.getName(), ret, Opcodes.ACC_PUBLIC).withParameters(types).intercept(new CodeMethodImplementation() {
            @Override
            public ByteCodeAppender.Size code(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitFieldInsn(Opcodes.GETFIELD, PROXY_BASE, FIELD_NAME, OBJECT_DESC);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, desc);
                var offset = loadParams(methodVisitor, types);
                methodVisitor.visitLdcInsn(key);

                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, desc, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                var restDesc = Type.getType(ret);
                methodVisitor.visitLdcInsn(restDesc);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "net/binis/codegen/map/Mapper", "convert", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, restDesc.getInternalName());

                var locals = offset;
                var retOp = getReturnOpcode(ret);
                methodVisitor.visitInsn(retOp.getKey());
                offset += retOp.getValue();

                return new ByteCodeAppender.Size(offset, locals);
            }
        }).annotateMethod(mtd.getDeclaredAnnotations());
    }


    protected DynamicType.Builder<?> handlePath(DynamicType.Builder<?> type, Class<?> cls, Method mtd, String desc, Class<?>[] types, Class<?> ret, boolean isVoid, Deque<Object> path) {
        assert path.size() > 1;
        return type.defineMethod(mtd.getName(), ret, Opcodes.ACC_PUBLIC).withParameters(types).intercept(new CodeMethodImplementation() {
            @Override
            public ByteCodeAppender.Size code(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                var loadOffset = loadOffset(types);
                var label = new Label();
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitFieldInsn(Opcodes.GETFIELD, PROXY_BASE, FIELD_NAME, OBJECT_DESC);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, desc);
                var size = path.size();
                var m = (Method) path.pop();
                if (m.getDeclaringClass().isInterface()) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, desc, m.getName(), calcDescriptor(m.getParameterTypes(), m.getReturnType()), true);
                } else {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, desc, m.getName(), calcDescriptor(m.getParameterTypes(), m.getReturnType()), false);
                }
                Method pm;
                for (var i = 1; i < size - 1; i++) {
                    pm = m;
                    m = (Method) path.pop();
                    var o = loadOffset + i;
                    methodVisitor.visitVarInsn(Opcodes.ASTORE, o);
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, o);
                    methodVisitor.visitJumpInsn(Opcodes.IFNULL, label);

//                    //Test
//                    methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
//                    methodVisitor.visitVarInsn(Opcodes.ALOAD, loadOffset + 1);
//                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false);
//                    //Test

                    methodVisitor.visitVarInsn(Opcodes.ALOAD, o);
                    if (m.getDeclaringClass().isInterface()) {
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, TypeDefinition.Sort.describe(pm.getReturnType()).getActualName().replace('.', '/'), m.getName(), calcDescriptor(m.getParameterTypes(), m.getReturnType()), true);
                    } else {
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TypeDefinition.Sort.describe(pm.getReturnType()).getActualName().replace('.', '/'), m.getName(), calcDescriptor(m.getParameterTypes(), m.getReturnType()), false);
                    }
                }
                pm = m;
                var q = path.pop();
                var o = loadOffset + size - 1;
                methodVisitor.visitVarInsn(Opcodes.ASTORE, o);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, o);
                methodVisitor.visitJumpInsn(Opcodes.IFNULL, label);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, o);
                loadParams(methodVisitor, types);
                var retDesc = Type.getType(ret);
                if (q instanceof Method mm) {
                    if (mm.getDeclaringClass().isInterface()) {
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, TypeDefinition.Sort.describe(pm.getReturnType()).getActualName().replace('.', '/'), mm.getName(), calcDescriptor(mm.getParameterTypes(), mm.getReturnType()), true);
                    } else {
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TypeDefinition.Sort.describe(pm.getReturnType()).getActualName().replace('.', '/'), mm.getName(), calcDescriptor(mm.getParameterTypes(), mm.getReturnType()), false);
                    }
                    if (ret.isInterface() && !ret.equals(mm.getReturnType())) {
                        methodVisitor.visitLdcInsn(retDesc);
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "net/binis/codegen/factory/CodeFactory", "projection", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, retDesc.getInternalName());
                    } else if (!ret.equals(mm.getReturnType())) {
                        methodVisitor.visitLdcInsn(retDesc);
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "net/binis/codegen/map/Mapper", "convert", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, retDesc.getInternalName());
                    }
                } else {
                    methodVisitor.visitLdcInsn(q);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                    methodVisitor.visitLdcInsn(retDesc);

                    if (ret.isInterface()) {
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "net/binis/codegen/factory/CodeFactory", "projection", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
                    } else {
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "net/binis/codegen/map/Mapper", "convert", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
                    }
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, retDesc.getInternalName());
                }
                var retOp = getReturnOpcode(ret);
                methodVisitor.visitInsn(retOp.getKey());
                methodVisitor.visitLabel(label);
                defaultReturn(methodVisitor, ret);

                return new ByteCodeAppender.Size(1, 1);
            }
        }).annotateMethod(mtd.getDeclaredAnnotations());
    }

    protected DynamicType.Builder<?> handleProjection(DynamicType.Builder<?> type, Method mtd, Method m, String desc, Class<?>[] types, Class<?> ret) {
        return type.defineMethod(mtd.getName(), ret, Opcodes.ACC_PUBLIC).withParameters(types).intercept(new CodeMethodImplementation() {
            @Override
            public ByteCodeAppender.Size code(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitFieldInsn(Opcodes.GETFIELD, PROXY_BASE, FIELD_NAME, OBJECT_DESC);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, desc);
                var offset = loadParams(methodVisitor, types);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, desc, mtd.getName(), calcDescriptor(types, m.getReturnType()), false);
                methodVisitor.visitLdcInsn(Type.getType(mtd.getReturnType()));
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "net/binis/codegen/factory/CodeFactory", "projection", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, TypeDefinition.Sort.describe(mtd.getReturnType()).getActualName().replace('.', '/'));
                methodVisitor.visitInsn(Opcodes.ARETURN);
                var size = (offset == 1) ? 2 : offset;
                return new ByteCodeAppender.Size(size, offset);
            }
        }).annotateMethod(mtd.getDeclaredAnnotations());
    }

    protected DynamicType.Builder<?> handleCustomClassProjection(DynamicType.Builder<?> type, Method mtd, Method m, String desc, Class<?>[] types, Class<?> ret, java.lang.reflect.Type[] generics) {
        return type.defineMethod(mtd.getName(), ret, Opcodes.ACC_PUBLIC).withParameters(types).intercept(new CodeMethodImplementation() {
            @Override
            public ByteCodeAppender.Size code(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitFieldInsn(Opcodes.GETFIELD, PROXY_BASE, FIELD_NAME, OBJECT_DESC);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, desc);
                loadParams(methodVisitor, types);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, desc, mtd.getName(), calcDescriptor(types, m.getReturnType()), false);
                methodVisitor.visitInsn(Opcodes.ICONST_0 + generics.length);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
                for (var c : generics) {
                    methodVisitor.visitInsn(Opcodes.DUP);
                    methodVisitor.visitInsn(Opcodes.ICONST_0);
                    methodVisitor.visitLdcInsn(Type.getType((Class) c));
                    methodVisitor.visitInsn(Opcodes.AASTORE);
                }
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "net/binis/codegen/factory/CodeFactory", "projections", "(Ljava/lang/Object;[Ljava/lang/Class;)Ljava/lang/Object;", false);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, TypeDefinition.Sort.describe(mtd.getReturnType()).getActualName().replace('.', '/'));
                methodVisitor.visitInsn(Opcodes.ARETURN);
                return new ByteCodeAppender.Size(1, 1);
            }
        }).annotateMethod(mtd.getDeclaredAnnotations());
    }


    protected boolean needProjection(java.lang.reflect.Type[] generics, java.lang.reflect.Type[] original) {

        if (generics.length == original.length) {
            for (var i = 0; i < generics.length; i++) {
                if (generics[i] instanceof Class && original[i] instanceof Class && ((Class) generics[i]).isInterface() && !generics[i].equals(original[i])) {
                    return true;
                }
            }
        }

        return false;
    }

    protected String calcDescriptor(Class<?>[] types, Class<?> returnType) {
        var sb = new StringBuilder("(");

        for (var t : types) {
            if (t.isPrimitive()) {
                sb.append(getPrimitiveDescriptor(t));
            } else {
                sb.append('L').append(TypeDefinition.Sort.describe(t).getActualName().replace('.', '/')).append(';');
            }
        }
        sb.append(')');
        if (void.class.equals(returnType)) {
            sb.append('V');
        } else {
            if (returnType.isPrimitive()) {
                sb.append(getPrimitiveDescriptor(returnType));
            } else {
                sb.append('L').append(TypeDefinition.Sort.describe(returnType).getActualName().replace('.', '/')).append(';');
            }
        }
        return sb.toString();
    }

    protected char getPrimitiveDescriptor(Class<?> type) {

        if (boolean.class.equals(type))
            return 'Z';
        if (byte.class.equals(type))
            return 'B';
        if (short.class.equals(type))
            return 'S';
        if (char.class.equals(type))
            return 'C';
        if (int.class.equals(type))
            return 'I';
        if (long.class.equals(type))
            return 'J';
        if (float.class.equals(type))
            return 'F';
        if (double.class.equals(type))
            return 'D';
        if (void.class.equals(type))
            return 'V';

        throw new ProjectionCreationException("Unknown primitive type: " + type.getCanonicalName());
    }

    protected int getLoadOpcode(Class<?> type) {
        if (type.isPrimitive()) {
            if (long.class.equals(type)) {
                return Opcodes.LLOAD;
            }
            if (double.class.equals(type)) {
                return Opcodes.DLOAD;
            }
            if (float.class.equals(type)) {
                return Opcodes.FLOAD;
            }
            return Opcodes.ILOAD;
        }
        return Opcodes.ALOAD;
    }

    protected Pair<Integer, Integer> getReturnOpcode(Class<?> type) {
        if (void.class.equals(type)) {
            return Pair.of(Opcodes.RETURN, 0);
        }
        if (long.class.equals(type)) {
            return Pair.of(Opcodes.LRETURN, 1);
        }
        if (double.class.equals(type)) {
            return Pair.of(Opcodes.DRETURN, 1);
        }
        if (float.class.equals(type)) {
            return Pair.of(Opcodes.FRETURN, 0);
        }
        if (type.isPrimitive()) {
            return Pair.of(Opcodes.IRETURN, 0);
        }
        return Pair.of(Opcodes.ARETURN, 0);
    }

    protected int getLoadOffset(Class<?> type) {
        if (type.isPrimitive() && (long.class.equals(type) || double.class.equals(type))) {
            return 2;
        }
        return 1;
    }

    protected int loadParams(MethodVisitor methodVisitor, Class<?>[] types) {
        var offset = 1;
        for (var type : types) {
            methodVisitor.visitVarInsn(getLoadOpcode(type), offset);
            offset += getLoadOffset(type);
        }

        return offset;
    }

    protected int loadOffset(Class<?>[] types) {
        var offset = 0;
        for (Class<?> type : types) {
            offset += getLoadOffset(type);
        }

        return offset;
    }


    protected boolean methodExists(Map<String, List<Class<?>[]>> methods, Method mtd, Class<?>[] types) {
        var list = methods.computeIfAbsent(mtd.getName(), k -> new ArrayList<>());

        for (var t : list) {
            if (paramsMatch(types, t)) {
                return true;
            }
        }

        list.add(types);
        return false;
    }

    protected boolean paramsMatch(Class<?>[] types, Class<?>[] t) {
        if (t.length == types.length) {
            var match = true;
            for (var i = 0; i < t.length; i++) {
                if (!t[i].equals(types[i])) {
                    match = false;
                    break;
                }
            }
            return match;
        }
        return false;
    }

    protected DynamicType.Builder<?> checkPath(DynamicType.Builder<?> type, Class<?> cls, Method mtd, String desc, Class<?>[] types, Class<?> ret, boolean isVoid) {
        var path = new ArrayDeque<Object>();
        findStartMethod(cls, mtd.getName(), types, path);
        if (!path.isEmpty()) {
            return handlePath(type, cls, mtd, desc, types, ret, isVoid, path);
        }

        return null;
    }

    protected Method findMethod(Class<?> cls, String name, Class<?>[] types) throws NoSuchMethodException {
        try {
            return cls.getDeclaredMethod(name, types);
        } catch (NoSuchMethodException ex) {
            if (nonNull(cls.getSuperclass())) {
                return findMethod(cls.getSuperclass(), name, types);
            } else {
                throw ex;
            }
        }
    }

    protected boolean findStartMethod(Class<?> cls, String name, Class<?>[] types, Deque<Object> path) {
        var result = false;
        for (var m : cls.getDeclaredMethods()) {
            if (name.startsWith(m.getName())) {
                var left = name.substring(m.getName().length());
                if (left.isEmpty()) {
                    if (paramsMatch(m.getParameterTypes(), types)) {
                        path.push(m);
                        return true;
                    }
                } else if (Map.class.isAssignableFrom(m.getReturnType())) {
                    if (paramsMatch(m.getParameterTypes(), types)) {
                        path.push(decapitalize(left));
                        path.push(m);
                        return true;
                    }
                } else {
                    if (m.getParameterCount() == 0 && !m.getReturnType().isPrimitive() && Character.isUpperCase(left.charAt(0))) {
                        result = findStartMethod(m.getReturnType(), calcGetterName(left), types, path);
                        if (!path.isEmpty()) {
                            path.push(m);
                        }
                        if (result) {
                            return result;
                        } else {
                            result = findStartMethod(m.getReturnType(), left.substring(0, 1).toLowerCase() + left.substring(1), types, path);
                            if (!path.isEmpty()) {
                                path.push(m);
                            }
                            if (result) {
                                return result;
                            }
                        }
                    }
                }
            }
        }

        if (nonNull(cls.getSuperclass())) {
            result = findStartMethod(cls.getSuperclass(), name, types, path);
        }
        if (!result) {
            for (var i : cls.getInterfaces()) {
                result = findStartMethod(i, name, types, path);
                if (result) {
                    break;
                }
            }
        }
        return result;
    }

    protected String calcGetterName(String value) {
        return "get" + Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    protected static void defaultReturn(MethodVisitor methodVisitor, Class<?> ret) {
        if (ret.isPrimitive()) {
            if (ret.equals(long.class)) {
                methodVisitor.visitInsn(Opcodes.LCONST_0);
                methodVisitor.visitInsn(Opcodes.LRETURN);
            } else if (ret.equals(double.class)) {
                methodVisitor.visitInsn(Opcodes.DCONST_0);
                methodVisitor.visitInsn(Opcodes.DRETURN);
            } else if (ret.equals(float.class)) {
                methodVisitor.visitInsn(Opcodes.FCONST_0);
                methodVisitor.visitInsn(Opcodes.FRETURN);
            } else {
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitInsn(Opcodes.IRETURN);
            }
        } else {
            methodVisitor.visitInsn(Opcodes.ACONST_NULL);
            methodVisitor.visitInsn(Opcodes.ARETURN);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object proxy(Class cls, InvocationHandler handler) {
        if (cls.isInterface()) {
            return Proxy.newProxyInstance(cls.getClassLoader(), new Class[]{cls}, handler);
        } else {
            var inst = CodeFactory.create(proxies.computeIfAbsent(cls, k ->
                    new ByteBuddy()
                            .subclass(k)
                            .defineField("handler", InvocationHandler.class, Visibility.PUBLIC)
                            .method(ElementMatchers.any())
                            .intercept(InvocationHandlerAdapter.toField("handler"))
                            .make()
                            .load(k.getClassLoader())
                            .getLoaded()));
            return Reflection.setFieldValue(inst, "handler", handler);
        }
    }

    @Override
    public Object multiple(InvocationHandler handler, Class... cls) {
        //TODO: Check for implementations
        return switch (cls.length) {
            case 0 -> throw new IllegalArgumentException("No classes provided!");
            case 1 -> proxy(cls[0], handler);
            default -> Proxy.newProxyInstance(cls[0].getClassLoader(), cls, handler);
        };
    }

    protected static class EnableFramesComputing implements AsmVisitorWrapper {
        @Override
        public final int mergeWriter(int flags) {
            return flags | ClassWriter.COMPUTE_FRAMES;
        }

        @Override
        public final int mergeReader(int flags) {
            return flags | ClassWriter.COMPUTE_FRAMES;
        }

        @Override
        public final ClassVisitor wrap(TypeDescription td, ClassVisitor cv, Implementation.Context ctx, TypePool tp, FieldList<FieldDescription.InDefinedShape> fields, MethodList<?> methods, int wflags, int rflags) {
            return cv;
        }
    }

    public static String getKeyName(String name) {
        if (name.startsWith("is")) {
            return name.substring(2, 3).toLowerCase() + name.substring(3);
        } else {
            return name.substring(3, 4).toLowerCase() + name.substring(4);
        }
    }

}
