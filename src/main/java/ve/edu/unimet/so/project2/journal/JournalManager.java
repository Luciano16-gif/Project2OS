package ve.edu.unimet.so.project2.journal;

import ve.edu.unimet.so.project2.datastructures.SimpleList;
import ve.edu.unimet.so.project2.journal.undo.JournalUndoData;
import ve.edu.unimet.so.project2.process.IoOperationType;

public final class JournalManager {

    private static final String TRANSACTION_PREFIX = "TX-";

    private final SimpleList<JournalEntry> entries;
    private long nextTransactionNumber;

    public JournalManager() {
        this.entries = new SimpleList<>();
        this.nextTransactionNumber = 1L;
    }

    public JournalEntry registerPending(
            IoOperationType operationType,
            String targetPath,
            JournalUndoData undoData) {
        return registerPending(operationType, targetPath, undoData, null, null, null);
    }

    public JournalEntry registerPending(
            IoOperationType operationType,
            String targetPath,
            JournalUndoData undoData,
            String targetNodeId,
            String ownerUserId,
            String description) {
        String transactionId = buildNextTransactionId();
        ensureTransactionIdDoesNotExist(transactionId);

        JournalEntry entry = new JournalEntry(
                transactionId,
                operationType,
                targetPath,
                JournalStatus.PENDING,
                undoData,
                targetNodeId,
                ownerUserId,
                description);
        entries.add(entry);
        nextTransactionNumber++;
        return entry;
    }

    public JournalEntry restoreEntry(
            String transactionId,
            IoOperationType operationType,
            String targetPath,
            JournalStatus status,
            JournalUndoData undoData,
            String targetNodeId,
            String ownerUserId,
            String description) {
        JournalEntry entry = new JournalEntry(
                transactionId,
                operationType,
                targetPath,
                status,
                undoData,
                targetNodeId,
                ownerUserId,
                description);
        ensureTransactionIdDoesNotExist(entry.getTransactionId());
        entries.add(entry);
        advanceNextTransactionNumberFrom(entry.getTransactionId());
        return entry;
    }

    public JournalEntry markCommitted(String transactionId) {
        JournalEntry entry = requireExistingEntry(transactionId);
        entry.markCommitted();
        return entry;
    }

    public JournalEntry markUndone(String transactionId) {
        JournalEntry entry = requireExistingEntry(transactionId);
        entry.markUndone();
        return entry;
    }

    public JournalEntry findByTransactionId(String transactionId) {
        requireNonBlank(transactionId, "transactionId");
        for (int i = 0; i < entries.size(); i++) {
            JournalEntry entry = entries.get(i);
            if (entry.getTransactionId().equals(transactionId)) {
                return entry;
            }
        }
        return null;
    }

    public int getEntryCount() {
        return entries.size();
    }

    public JournalEntry getEntryAt(int index) {
        return entries.get(index);
    }

    public Object[] getEntriesSnapshot() {
        return entries.toArray();
    }

    public int getPendingCount() {
        return collectPendingEntries().size();
    }

    public JournalEntry getPendingEntryAt(int index) {
        return collectPendingEntries().get(index);
    }

    public Object[] getPendingEntriesSnapshot() {
        return collectPendingEntries().toArray();
    }

    public void clear() {
        entries.clear();
        nextTransactionNumber = 1L;
    }

    private SimpleList<JournalEntry> collectPendingEntries() {
        SimpleList<JournalEntry> pendingEntries = new SimpleList<>();
        for (int i = 0; i < entries.size(); i++) {
            JournalEntry entry = entries.get(i);
            if (entry.isPending()) {
                pendingEntries.add(entry);
            }
        }
        return pendingEntries;
    }

    private JournalEntry requireExistingEntry(String transactionId) {
        JournalEntry entry = findByTransactionId(transactionId);
        if (entry == null) {
            throw new IllegalArgumentException("journal entry not found for transactionId: " + transactionId);
        }
        return entry;
    }

    private void ensureTransactionIdDoesNotExist(String transactionId) {
        if (findByTransactionId(transactionId) != null) {
            throw new IllegalStateException("duplicate transactionId detected: " + transactionId);
        }
    }

    private String buildNextTransactionId() {
        if (nextTransactionNumber <= 0) {
            throw new IllegalStateException("nextTransactionNumber must stay positive");
        }
        return TRANSACTION_PREFIX + nextTransactionNumber;
    }

    private void advanceNextTransactionNumberFrom(String transactionId) {
        if (!transactionId.startsWith(TRANSACTION_PREFIX)) {
            return;
        }

        String suffix = transactionId.substring(TRANSACTION_PREFIX.length());
        if (suffix.isBlank()) {
            return;
        }

        long restoredNumber;
        try {
            restoredNumber = Long.parseLong(suffix);
        } catch (NumberFormatException ignored) {
            return;
        }

        if (restoredNumber <= 0) {
            return;
        }

        long candidateNext = restoredNumber + 1;
        if (candidateNext > nextTransactionNumber) {
            nextTransactionNumber = candidateNext;
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
