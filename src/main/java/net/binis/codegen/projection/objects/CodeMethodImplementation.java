package net.binis.codegen.projection.objects;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.MethodVisitor;

public abstract class CodeMethodImplementation implements Implementation {
    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
        return new Appender();
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
        return instrumentedType;
    }

    public abstract ByteCodeAppender.Size code(MethodVisitor methodVisitor, Implementation.Context implementationContext, MethodDescription instrumentedMethod);

    class Appender implements ByteCodeAppender {

        public Size apply(MethodVisitor methodVisitor, Implementation.Context implementationContext, MethodDescription instrumentedMethod) {
            return CodeMethodImplementation.this.code(methodVisitor, implementationContext, instrumentedMethod);
        }
    }
}
