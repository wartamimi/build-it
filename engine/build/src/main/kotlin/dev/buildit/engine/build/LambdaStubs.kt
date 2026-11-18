package dev.buildit.engine.build

import java.io.File

/**
 * android.jar has no LambdaMetafactory/StringConcatFactory, which the Java compiler needs to emit
 * lambdas and string concatenation (D8 later desugars both). Like AGP's core-lambda-stubs.jar, we
 * provide signature-only stubs, compiled from the sources below on first use. They are only on the
 * compile classpath and never packaged into an APK.
 */
object LambdaStubs {
    private const val VERSION = 1

    private val sources = mapOf(
        "java/lang/invoke/LambdaMetafactory.java" to """
            package java.lang.invoke;
            public final class LambdaMetafactory {
                public static final int FLAG_SERIALIZABLE = 1, FLAG_MARKERS = 2, FLAG_BRIDGES = 4;
                private LambdaMetafactory() {}
                public static CallSite metafactory(MethodHandles.Lookup caller, String name, MethodType type, MethodType samType, MethodHandle impl, MethodType instantiated) throws LambdaConversionException { throw new UnsupportedOperationException(); }
                public static CallSite altMetafactory(MethodHandles.Lookup caller, String name, MethodType type, Object... args) throws LambdaConversionException { throw new UnsupportedOperationException(); }
            }
        """,
        "java/lang/invoke/LambdaConversionException.java" to """
            package java.lang.invoke;
            public class LambdaConversionException extends Exception { public LambdaConversionException() {} }
        """,
        "java/lang/invoke/StringConcatFactory.java" to """
            package java.lang.invoke;
            public final class StringConcatFactory {
                private StringConcatFactory() {}
                public static CallSite makeConcat(MethodHandles.Lookup lookup, String name, MethodType type) throws StringConcatException { throw new UnsupportedOperationException(); }
                public static CallSite makeConcatWithConstants(MethodHandles.Lookup lookup, String name, MethodType type, String recipe, Object... constants) throws StringConcatException { throw new UnsupportedOperationException(); }
            }
        """,
        "java/lang/invoke/StringConcatException.java" to """
            package java.lang.invoke;
            public class StringConcatException extends Exception { public StringConcatException() {} }
        """,
        "java/lang/runtime/SwitchBootstraps.java" to """
            package java.lang.runtime;
            import java.lang.invoke.*;
            public final class SwitchBootstraps {
                private SwitchBootstraps() {}
                public static CallSite typeSwitch(MethodHandles.Lookup lookup, String name, MethodType type, Object... labels) { throw new UnsupportedOperationException(); }
                public static CallSite enumSwitch(MethodHandles.Lookup lookup, String name, MethodType type, Object... labels) { throw new UnsupportedOperationException(); }
            }
        """,
    )

    /** Returns a directory of stub classes for [androidJar], compiling them into [cacheDir] if needed. */
    fun classes(androidJar: File, cacheDir: File): File {
        val out = File(cacheDir, "lambda-stubs-v$VERSION")
        val marker = File(out, ".complete")
        if (marker.isFile) return out
        val src = File(cacheDir, "lambda-stubs-src").apply { deleteRecursively() }
        val files = sources.map { (path, code) -> File(src, path).apply { parentFile.mkdirs(); writeText(code.trimIndent()) } }
        out.deleteRecursively()
        val d = Diagnostics()
        check(JavaCompiler.compileStubs(files, androidJar, out, d)) { "Could not prepare compiler stubs: ${d.all.joinToString()}" }
        marker.writeText("ok")
        src.deleteRecursively()
        return out
    }
}
