package ve.edu.unimet.so.project2.disk;

public class DiskBlock {

    public static final int NO_NEXT_BLOCK = -1;

    private final int index;
    private boolean free;
    private String ownerFileId;
    private int nextBlockIndex;
    private String occupiedByProcessId;
    private boolean systemReserved;

    DiskBlock(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index cannot be negative");
        }
        this.index = index;
        this.free = true;
        this.ownerFileId = null;
        this.nextBlockIndex = NO_NEXT_BLOCK;
        this.occupiedByProcessId = null;
        this.systemReserved = false;
    }

    DiskBlock(DiskBlock source) {
        this(source.index);
        this.free = source.free;
        this.ownerFileId = source.ownerFileId;
        this.nextBlockIndex = source.nextBlockIndex;
        this.occupiedByProcessId = source.occupiedByProcessId;
        this.systemReserved = source.systemReserved;
    }

    public int getIndex() {
        return index;
    }

    public boolean isFree() {
        return free;
    }

    public String getOwnerFileId() {
        return ownerFileId;
    }

    public int getNextBlockIndex() {
        return nextBlockIndex;
    }

    public String getOccupiedByProcessId() {
        return occupiedByProcessId;
    }

    public boolean isSystemReserved() {
        return systemReserved;
    }

    void setOccupiedByProcessId(String occupiedByProcessId) {
        this.occupiedByProcessId = normalizeOptional(occupiedByProcessId);
    }

    void allocate(String ownerFileId, int nextBlockIndex, boolean systemReserved) {
        if (!free) {
            throw new IllegalStateException("block is already allocated");
        }
        if (ownerFileId == null || ownerFileId.isBlank()) {
            throw new IllegalArgumentException("ownerFileId cannot be blank");
        }
        validateNextBlockIndex(nextBlockIndex);

        this.free = false;
        this.ownerFileId = ownerFileId;
        this.nextBlockIndex = nextBlockIndex;
        this.systemReserved = systemReserved;
        this.occupiedByProcessId = null;
    }

    void updateNextBlockIndex(int nextBlockIndex) {
        if (free) {
            throw new IllegalStateException("free blocks cannot point to next blocks");
        }
        validateNextBlockIndex(nextBlockIndex);
        this.nextBlockIndex = nextBlockIndex;
    }

    void release() {
        this.free = true;
        this.ownerFileId = null;
        this.nextBlockIndex = NO_NEXT_BLOCK;
        this.occupiedByProcessId = null;
        this.systemReserved = false;
    }

    private void validateNextBlockIndex(int nextBlockIndex) {
        if (nextBlockIndex < NO_NEXT_BLOCK) {
            throw new IllegalArgumentException("nextBlockIndex cannot be less than NO_NEXT_BLOCK");
        }
        if (nextBlockIndex == index) {
            throw new IllegalArgumentException("block cannot point to itself");
        }
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
