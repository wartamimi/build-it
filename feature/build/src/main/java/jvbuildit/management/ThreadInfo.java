package jvbuildit.management;

public class ThreadInfo {
    private final Thread thread;
    public ThreadInfo(Thread thread) { this.thread = thread; }
    public String getThreadName() { return thread.getName(); }
    public Thread.State getThreadState() { return thread.getState(); }
    public StackTraceElement[] getStackTrace() { return thread.getStackTrace(); }
    public String getLockName() { return null; }
    public long getLockOwnerId() { return -1; }
    public String getLockOwnerName() { return null; }
    public boolean isInNative() { return false; }
    public boolean isSuspended() { return false; }
}
