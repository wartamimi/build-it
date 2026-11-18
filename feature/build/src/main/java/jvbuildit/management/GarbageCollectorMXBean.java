package jvbuildit.management;

public interface GarbageCollectorMXBean {
    String getName();
    long getCollectionCount();
    long getCollectionTime();
}
