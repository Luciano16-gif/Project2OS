package ve.edu.unimet.so.project2.journal.undo;

public record JournalBlockSnapshot(
        int index,
        String ownerFileId,
        int nextBlockIndex,
        boolean systemReserved) {

    public static final int NO_NEXT_BLOCK = -1;

    public JournalBlockSnapshot {
        if (index < 0) {
            throw new IllegalArgumentException("index cannot be negative");
        }
        ownerFileId = JournalUndoData.requireNonBlank(ownerFileId, "ownerFileId");
        if (nextBlockIndex < NO_NEXT_BLOCK) {
            throw new IllegalArgumentException("nextBlockIndex cannot be less than NO_NEXT_BLOCK");
        }
        if (nextBlockIndex == index) {
            throw new IllegalArgumentException("block snapshot cannot point to itself");
        }
    }

    public int getIndex() { return index; }
    public String getOwnerFileId() { return ownerFileId; }
    public int getNextBlockIndex() { return nextBlockIndex; }
    public boolean isSystemReserved() { return systemReserved; }
}
