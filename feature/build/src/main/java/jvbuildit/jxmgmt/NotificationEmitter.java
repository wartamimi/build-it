package jvbuildit.jxmgmt;

/** Stand-in for javax.management (not on Android); see jvbuildit.management.ManagementFactory. */
public interface NotificationEmitter {
    void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback);
}
