package javax.lang.model;

/**
 * Android has no javax.lang.model. The Eclipse compiler (ECJ) reads this enum once, while setting
 * up its class path, to detect the Java version it runs on; without it ECJ fails on ART. Only the
 * members ECJ touches are provided.
 */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5, RELEASE_6, RELEASE_7, RELEASE_8,
    RELEASE_9, RELEASE_10, RELEASE_11, RELEASE_12, RELEASE_13, RELEASE_14, RELEASE_15, RELEASE_16,
    RELEASE_17;

    public static SourceVersion latest() { return RELEASE_17; }

    public static SourceVersion latestSupported() { return RELEASE_17; }
}
