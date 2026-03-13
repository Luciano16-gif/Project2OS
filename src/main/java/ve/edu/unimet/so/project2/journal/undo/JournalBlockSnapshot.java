package ve.edu.unimet.so.project2.journal.undo;

public final class JournalBlockSnapshot {

    public static final int NO_NEXT_BLOCK = -1;

    private final int index;
    private final String ownerFileId;
    private final int nextBlockIndex;
    private final boolean systemReserved;

    public JournalBlockSnapshot(int index, String ownerFileId, int nextBlockIndex, boolean systemReserved) {
        if (index < 0) {
            throw new IllegalArgumentException("index cannot be negative");
        }
        this.ownerFileId = requireNonBlank(ownerFileId, "ownerFileId");
        if (nextBlockIndex < NO_NEXT_BLOCK) {
            throw new IllegalArgumentException("nextBlockIndex cannot be less than NO_NEXT_BLOCK");
        }
        if (nextBlockIndex == index) {
            throw new IllegalArgumentException("block snapshot cannot point to itself");
        }
        this.index = index;
        this.nextBlockIndex = nextBlockIndex;
        this.systemReserved = systemReserved;
    }

    public int getIndex() {
        return index;
    }

    public String getOwnerFileId() {
        return ownerFileId;
    }

    public int getNextBlockIndex() {
        return nextBlockIndex;
    }

    public boolean isSystemReserved() {
        return systemReserved;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
