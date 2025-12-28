package net.binis.codegen.projection.tools;

public interface ProjectionTools {

    static String decapitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

}
