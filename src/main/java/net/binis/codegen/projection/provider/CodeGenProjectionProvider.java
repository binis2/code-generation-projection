package net.binis.codegen.projection.provider;

/*-
 * #%L
 * code-generator-projection
 * %%
 * Copyright (C) 2021 - 2022 Binis Belev
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
import net.binis.codegen.objects.Pair;
import net.binis.codegen.projection.exception.ProjectionCreationException;
import net.binis.codegen.projection.objects.CodeMethodImplementation;
import net.binis.codegen.projection.objects.CodeProjectionProxyList;
import net.binis.codegen.projection.objects.CodeProjectionProxySet;
import net.binis.codegen.projection.objects.CodeProxyBase;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;

import java.lang.reflect.*;
import java.util.*;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
public class CodeGenProjectionProvider implements ProjectionProvider {

    private static final String PROXY_BASE = "net/binis/codegen/projection/objects/CodeProxyBase";
    public static final String OBJECT_DESC = "Ljava/lang/Object;";
    public static final String FIELD_NAME = "value";

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

    private Constructor<?> createObject(Class<?> cls, Class<?>[] projections) {
        try {
            return createProjectionClass(cls, projections).getDeclaredConstructor(cls);
        } catch (NoSuchMethodException e) {
            throw new ProjectionCreationException("Unable to find constructor for proxy class: " + cls.getCanonicalName(), e);
        }
    }

    private Class<?> createProjectionClass(Class<?> cls, Class<?>[] projections) {
        var desc = TypeDefinition.Sort.describe(cls).getActualName().replace('.', '/');
        var objectName = "net.binis.projection." + cls.getSimpleName();
        for (var p : projections) {
            objectName += "$" + p.getSimpleName();
        }

        DynamicType.Builder<?> type = new ByteBuddy()
                .subclass(CodeProxyBase.class)
                .visit(new EnableFramesComputing())
                .name(objectName)
                .implement(projections)
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
                });

        var methods = new HashMap<String, List<Class<?>[]>>();
        for (var p : projections) {
            type = type.annotateType(p.getDeclaredAnnotations());
            type = handleInterface(type, cls, p, desc, methods);
        }

        return type.make()
                .load(cls.getClassLoader())
                .getLoaded();
    }

    private DynamicType.Builder<?> handleInterface(DynamicType.Builder<?> type, Class<?> cls, Class<?> intf, String desc, Map<String, List<Class<?>[]>> methods) {
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

    private DynamicType.Builder<?> handleMethod(DynamicType.Builder<?> type, Class<?> cls, Method mtd, String desc, Map<String, List<Class<?>[]>> methods) {
        var types = mtd.getParameterTypes();
        var ret = mtd.getReturnType();
        if (!methodExists(methods, mtd, types)) {
            var isVoid = void.class.equals(ret);
            try {
                var m = findMethod(cls, mtd.getName(), types);
                type = handleDeclaredMethod(type, mtd, m, desc, types, ret);
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

    private DynamicType.Builder<?> handleUndeclaredMethod(DynamicType.Builder<?> type, Method mtd, Class<?>[] types, Class<?> ret, boolean isVoid) {
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

    private DynamicType.Builder<?> handleDeclaredMethod(DynamicType.Builder<?> type, Method mtd, Method m, String desc, Class<?>[] types, Class<?> ret) {
        if (CodeFactory.isCustomProxyClass(mtd.getReturnType())) {
            var generics = ((ParameterizedType) mtd.getGenericReturnType()).getActualTypeArguments();
            if (needProjection(generics, ((ParameterizedType) m.getGenericReturnType()).getActualTypeArguments())) {
                return handleCustomClassProjection(type, mtd, m, desc, types, ret, generics);
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
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, desc, mtd.getName(), calcDescriptor(types, ret), false);
                var locals = offset;
                var retOp = getReturnOpcode(ret);
                methodVisitor.visitInsn(retOp.getKey());
                offset += retOp.getValue();

                return new ByteCodeAppender.Size(offset, locals);
            }
        }).annotateMethod(mtd.getDeclaredAnnotations());
    }

    private DynamicType.Builder<?> handlePath(DynamicType.Builder<?> type, Class<?> cls, Method mtd, String desc, Class<?>[] types, Class<?> ret, boolean isVoid, Deque<Method> path) {
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
                var m = path.pop();
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, desc, m.getName(), calcDescriptor(m.getParameterTypes(), m.getReturnType()), false);
                Method pm;
                for (var i = 1; i < size - 1; i++) {
                    pm = m;
                    m = path.pop();
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
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TypeDefinition.Sort.describe(pm.getReturnType()).getActualName().replace('.', '/'), m.getName(), calcDescriptor(m.getParameterTypes(), m.getReturnType()), false);
                }
                pm = m;
                m = path.pop();
                var o = loadOffset + size - 1;
                methodVisitor.visitVarInsn(Opcodes.ASTORE, o);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, o);
                methodVisitor.visitJumpInsn(Opcodes.IFNULL, label);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, o);
                loadParams(methodVisitor, types);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TypeDefinition.Sort.describe(pm.getReturnType()).getActualName().replace('.', '/'), m.getName(), calcDescriptor(m.getParameterTypes(), m.getReturnType()), false);
                if (ret.isInterface() && !ret.equals(m.getReturnType())) {
                    methodVisitor.visitLdcInsn(Type.getType(ret));
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "net/binis/codegen/factory/CodeFactory", "projection", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, TypeDefinition.Sort.describe(ret).getActualName().replace('.', '/'));
                }
                var retOp = getReturnOpcode(ret);
                methodVisitor.visitInsn(retOp.getKey());
                methodVisitor.visitLabel(label);
                defaultReturn(methodVisitor, ret);

                return new ByteCodeAppender.Size(1, 1);
            }
        });
    }

    private DynamicType.Builder<?> handleProjection(DynamicType.Builder<?> type, Method mtd, Method m, String desc, Class<?>[] types, Class<?> ret) {
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
        });
    }

    private DynamicType.Builder<?> handleCustomClassProjection(DynamicType.Builder<?> type, Method mtd, Method m, String desc, Class<?>[] types, Class<?> ret, java.lang.reflect.Type[] generics) {
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
        });
    }


    private boolean needProjection(java.lang.reflect.Type[] generics, java.lang.reflect.Type[] original) {

        if (generics.length == original.length) {
            for (var i = 0; i < generics.length; i++) {
                if (generics[i] instanceof Class && original[i] instanceof Class && ((Class) generics[i]).isInterface() && !generics[i].equals(original[i])) {
                    return true;
                }
            }
        }

        return false;
    }

    private String calcDescriptor(Class<?>[] types, Class<?> returnType) {
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

    private char getPrimitiveDescriptor(Class<?> type) {

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

    private int getLoadOpcode(Class<?> type) {
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

    private Pair<Integer, Integer> getReturnOpcode(Class<?> type) {
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

    private int getLoadOffset(Class<?> type) {
        if (type.isPrimitive() && (long.class.equals(type) || double.class.equals(type))) {
            return 2;
        }
        return 1;
    }

    private int loadParams(MethodVisitor methodVisitor, Class<?>[] types) {
        var offset = 1;
        for (var type : types) {
            methodVisitor.visitVarInsn(getLoadOpcode(type), offset);
            offset += getLoadOffset(type);
        }

        return offset;
    }

    private int loadOffset(Class<?>[] types) {
        var offset = 0;
        for (Class<?> type : types) {
            offset += getLoadOffset(type);
        }

        return offset;
    }


    private boolean methodExists(Map<String, List<Class<?>[]>> methods, Method mtd, Class<?>[] types) {
        var list = methods.computeIfAbsent(mtd.getName(), k -> new ArrayList<>());

        for (var t : list) {
            if (paramsMatch(types, t)) {
                return true;
            }
        }

        list.add(types);
        return false;
    }

    private boolean paramsMatch(Class<?>[] types, Class<?>[] t) {
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

    private DynamicType.Builder<?> checkPath(DynamicType.Builder<?> type, Class<?> cls, Method mtd, String desc, Class<?>[] types, Class<?> ret, boolean isVoid) {
        var path = new ArrayDeque<Method>();
        findStartMethod(cls, mtd.getName(), types, path);
        if (!path.isEmpty()) {
            return handlePath(type, cls, mtd, desc, types, ret, isVoid, path);
        }

        return null;
    }

    private Method findMethod(Class<?> cls, String name, Class<?>[] types) throws NoSuchMethodException {
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

    private void findStartMethod(Class<?> cls, String name, Class<?>[] types, Deque<Method> path) {
        for (var m : cls.getDeclaredMethods()) {
            if (name.startsWith(m.getName())) {
                var left = name.substring(m.getName().length());
                if (left.length() == 0) {
                    if (paramsMatch(m.getParameterTypes(), types)) {
                        path.push(m);
                        return;
                    }
                } else {
                    if (m.getParameterCount() == 0 && !m.getReturnType().isPrimitive()) {
                        findStartMethod(m.getReturnType(), calcGetterName(left), types, path);
                        if (!path.isEmpty()) {
                            path.push(m);
                        }
                    }
                }
            }
        }

        if (nonNull(cls.getSuperclass())) {
            findStartMethod(cls.getSuperclass(), name, types, path);
        }
    }

    private String calcGetterName(String value) {
        return "get" + Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void defaultReturn(MethodVisitor methodVisitor, Class<?> ret) {
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

    private static class EnableFramesComputing implements AsmVisitorWrapper {
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

}
