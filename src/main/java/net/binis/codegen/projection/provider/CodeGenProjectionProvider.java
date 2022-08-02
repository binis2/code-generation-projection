package net.binis.codegen.projection.provider;

import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.factory.ProjectionInstantiation;
import net.binis.codegen.factory.ProjectionProvider;
import net.binis.codegen.projection.exception.ProjectionCreationException;
import net.binis.codegen.projection.objects.CodeMethodImplementation;
import net.binis.codegen.projection.objects.CodeProxyBase;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

@Slf4j
public class CodeGenProjectionProvider implements ProjectionProvider {

    private static final String PROXY_BASE = "net/binis/codegen/projection/objects/CodeProxyBase";
    public static final String OBJECT_DESC = "Ljava/lang/Object;";
    public static final String FIELD_NAME = "value";

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

        for (var p : projections) {
            type = handleInterface(type, cls, p, desc);
        }
//                .method(ElementMatchers.named("toString"))
//                .intercept(FixedValue.value("Hello World!"))

        return type.make()
                .load(cls.getClassLoader())
                .getLoaded();
    }

    private DynamicType.Builder<?> handleInterface(DynamicType.Builder<?> type, Class<?> cls, Class<?> intf, String desc) {
        for (var mtd : intf.getDeclaredMethods()) {
            type = handleMethod(type, cls, mtd, desc);
        }

        for (var i : intf.getInterfaces()) {
            type = handleInterface(type, cls, i, desc);
        }

        return type;
    }

    private DynamicType.Builder<?> handleMethod(DynamicType.Builder<?> type, Class<?> cls, Method mtd, String desc) {
        var types = mtd.getParameterTypes();
        var ret = mtd.getReturnType();
        var isVoid = void.class.equals(ret);
        try {
            var m = cls.getDeclaredMethod(mtd.getName(), types);
            type = handleDeclaredMethod(type, mtd, m, desc, types, ret, isVoid);
        } catch (NoSuchMethodException e) {
            type = handleUndeclaredMethod(type, mtd, types, ret, isVoid);
        }

        return type;
    }

    private DynamicType.Builder<?> handleUndeclaredMethod(DynamicType.Builder<?> type, Method mtd, Class<?>[] types, Class<?> ret, boolean isVoid) {
        return type.defineMethod(mtd.getName(), ret, Opcodes.ACC_PUBLIC).withParameters(types).intercept(new CodeMethodImplementation() {
            @Override
            public ByteCodeAppender.Size code(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                if (isVoid) {
                    methodVisitor.visitInsn(Opcodes.RETURN);
                    return new ByteCodeAppender.Size(0, types.length + 1);
                } else {
                    if (ret.isPrimitive()) {
                        if (ret.equals(long.class)) {
                            methodVisitor.visitInsn(Opcodes.LCONST_0);
                            methodVisitor.visitInsn(Opcodes.LRETURN);
                            return new ByteCodeAppender.Size(2, types.length + 1);
                        } else if (ret.equals(double.class)) {
                            methodVisitor.visitInsn(Opcodes.DCONST_0);
                            methodVisitor.visitInsn(Opcodes.DRETURN);
                            return new ByteCodeAppender.Size(2, types.length + 1);
                        } else if (ret.equals(float.class)) {
                            methodVisitor.visitInsn(Opcodes.FCONST_0);
                            methodVisitor.visitInsn(Opcodes.FRETURN);
                            return new ByteCodeAppender.Size(2, types.length + 1);
                        } else {
                            methodVisitor.visitInsn(Opcodes.ICONST_0);
                            methodVisitor.visitInsn(Opcodes.IRETURN);
                            return new ByteCodeAppender.Size(2, types.length + 1);
                        }
                    } else {
                        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                        methodVisitor.visitInsn(Opcodes.ARETURN);
                        return new ByteCodeAppender.Size(1, types.length + 1);
                    }
                }
            }
        });
    }

    private DynamicType.Builder<?> handleDeclaredMethod(DynamicType.Builder<?> type, Method mtd, Method m, String desc, Class<?>[] types, Class<?> ret, boolean isVoid) {
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

                if (isVoid) {
                    methodVisitor.visitInsn(Opcodes.RETURN);
                } else {
                    if (long.class.equals(ret)) {
                        offset++;
                        methodVisitor.visitInsn(Opcodes.LRETURN);
                    } else if (double.class.equals(ret)) {
                        offset++;
                        methodVisitor.visitInsn(Opcodes.DRETURN);
                    } else if (float.class.equals(ret)) {
                        methodVisitor.visitInsn(Opcodes.FRETURN);
                    } else if (ret.isPrimitive()) {
                        methodVisitor.visitInsn(Opcodes.IRETURN);
                    } else {
                        methodVisitor.visitInsn(Opcodes.ARETURN);
                    }
                }

                return new ByteCodeAppender.Size(offset, locals);
            }
        });
    }

    private DynamicType.Builder<?> handleProjection(DynamicType.Builder<?> type, Method mtd, Method m, String desc, Class<?>[] types, Class<?> ret) {
        var pdesc = TypeDefinition.Sort.describe(mtd.getReturnType()).getActualName().replace('.', '/');
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
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, pdesc);
                methodVisitor.visitInsn(Opcodes.ARETURN);
                var size = (offset == 1) ? 2 : offset;
                return new ByteCodeAppender.Size(size, offset);
            }
        });
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

    private int getLoadOffset(Class<?> type) {
        if (type.isPrimitive() && (long.class.equals(type) || double.class.equals(type))) {
            return 2;
        }
        return 1;
    }

    private int loadParams(MethodVisitor methodVisitor, Class<?>[] types) {
        var offset = 1;
        for (Class<?> type : types) {
            methodVisitor.visitVarInsn(getLoadOpcode(type), offset);
            offset += getLoadOffset(type);
        }

        return offset;
    }

}
