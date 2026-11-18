package jvbuildit.management;

import java.util.Collections;
import java.util.List;

/**
 * Stand-in for java.lang.management, which Android does not have. The Kotlin compiler only reads
 * timings and memory statistics from it (for its own reports); Build It relocates the compiler's
 * references here when it converts the compiler to DEX (see AndroidKotlinCompilerHost).
 */
public final class ManagementFactory {
    private ManagementFactory() {}

    private static final ThreadMXBean THREADS = new ThreadMXBean() {
        @Override public long getCurrentThreadCpuTime() { return android.os.Debug.threadCpuTimeNanos(); }
        @Override public long getCurrentThreadUserTime() { return android.os.Debug.threadCpuTimeNanos(); }
        @Override public void setThreadCpuTimeEnabled(boolean enable) {}
        @Override public ThreadInfo[] dumpAllThreads(boolean lockedMonitors, boolean lockedSynchronizers) { return new ThreadInfo[0]; }
        @Override public long[] getAllThreadIds() { return new long[0]; }
        @Override public ThreadInfo[] getThreadInfo(long[] ids, int maxDepth) { return new ThreadInfo[ids.length]; }
    };

    private static final MemoryMXBean MEMORY = new MemoryMXBean() {
        @Override public void addNotificationListener(jvbuildit.jxmgmt.NotificationListener listener, jvbuildit.jxmgmt.NotificationFilter filter, Object handback) {}
    };

    public static ThreadMXBean getThreadMXBean() { return THREADS; }
    public static CompilationMXBean getCompilationMXBean() { return () -> 0L; }
    public static List<GarbageCollectorMXBean> getGarbageCollectorMXBeans() { return Collections.emptyList(); }
    public static MemoryMXBean getMemoryMXBean() { return MEMORY; }
    public static List<MemoryPoolMXBean> getMemoryPoolMXBeans() { return Collections.emptyList(); }
    public static RuntimeMXBean getRuntimeMXBean() { return Collections::emptyList; }
}
