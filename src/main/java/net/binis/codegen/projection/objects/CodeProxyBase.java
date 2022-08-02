package net.binis.codegen.projection.objects;

public class CodeProxyBase<T>  {

    protected transient T value;

    public static Object unwrap(Object instance) {
        if (instance instanceof CodeProxyBase) {
            return ((CodeProxyBase) instance).value;
        }

        return instance;
    }

}
