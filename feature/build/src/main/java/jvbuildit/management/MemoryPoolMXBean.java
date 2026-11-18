package jvbuildit.management;

public interface MemoryPoolMXBean {
    MemoryType getType();
    MemoryUsage getUsage();
    boolean isCollectionUsageThresholdSupported();
    boolean isUsageThresholdSupported();
    void setCollectionUsageThreshold(long threshold);
    void setUsageThreshold(long threshold);
}
