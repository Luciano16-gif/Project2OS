package ve.edu.unimet.so.project2.coordinator.log;

import ve.edu.unimet.so.project2.datastructures.SimpleList;

public final class SystemEventLog {

    private final SimpleList<SystemEventLogEntry> entries;
    private long nextSequenceNumber;

    public SystemEventLog() {
        this.entries = new SimpleList<>();
        this.nextSequenceNumber = 1L;
    }

    public synchronized void record(long tick, String category, String message) {
        entries.add(new SystemEventLogEntry(nextSequenceNumber++, tick, category, message));
    }

    public synchronized int getEntryCount() {
        return entries.size();
    }

    public synchronized SystemEventLogEntry getEntryAt(int index) {
        return entries.get(index);
    }

    public synchronized SystemEventLogEntry[] getEntriesSnapshot() {
        Object[] source = entries.toArray();
        SystemEventLogEntry[] snapshot = new SystemEventLogEntry[source.length];
        for (int i = 0; i < source.length; i++) {
            snapshot[i] = (SystemEventLogEntry) source[i];
        }
        return snapshot;
    }

    public synchronized void clear() {
        entries.clear();
        nextSequenceNumber = 1L;
    }
}
