package jvbuildit;

/** Stand-in for java.beans.Introspector (not on Android): the compiler only uses decapitalize. */
public final class Introspector {
    private Introspector() {}

    public static String decapitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) return name;
        char[] chars = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }
}
