package ve.edu.unimet.so.project2.disk;

public class SimulatedDisk {

    public static final int NO_FREE_BLOCK = -1;

    private final int totalBlocks;
    private final DiskBlock[] blocks;
    private final DiskHead head;

    public SimulatedDisk(int totalBlocks, int initialHeadBlock, DiskHeadDirection initialDirection) {
        if (totalBlocks <= 0) {
            throw new IllegalArgumentException("totalBlocks must be > 0");
        }
        if (initialHeadBlock < 0 || initialHeadBlock >= totalBlocks) {
            throw new IllegalArgumentException("initialHeadBlock is out of range");
        }
        if (initialDirection == null) {
            throw new IllegalArgumentException("initialDirection cannot be null");
        }

        this.totalBlocks = totalBlocks;
        this.blocks = new DiskBlock[totalBlocks];
        for (int i = 0; i < totalBlocks; i++) {
            blocks[i] = new DiskBlock(i);
        }
        this.head = new DiskHead(initialHeadBlock, initialDirection);
    }

    public int getTotalBlocks() {
        return totalBlocks;
    }

    public DiskHead getHead() {
        return new DiskHead(head);
    }

    public DiskBlock getBlock(int index) {
        validateIndex(index);
        return new DiskBlock(blocks[index]);
    }

    public boolean isValidIndex(int index) {
        return index >= 0 && index < totalBlocks;
    }

    public int countFreeBlocks() {
        int freeCount = 0;
        for (DiskBlock block : blocks) {
            if (block.isFree()) {
                freeCount++;
            }
        }
        return freeCount;
    }

    public int countOccupiedBlocks() {
        return totalBlocks - countFreeBlocks();
    }

    public boolean hasFreeBlocks(int requiredBlocks) {
        if (requiredBlocks < 0) {
            throw new IllegalArgumentException("requiredBlocks cannot be negative");
        }
        return countFreeBlocks() >= requiredBlocks;
    }

    public int findFirstFreeBlock() {
        return findNextFreeBlock(0);
    }

    public int findNextFreeBlock(int startIndex) {
        validateIndex(startIndex);

        for (int i = startIndex; i < totalBlocks; i++) {
            if (blocks[i].isFree()) {
                return i;
            }
        }

        for (int i = 0; i < startIndex; i++) {
            if (blocks[i].isFree()) {
                return i;
            }
        }

        return NO_FREE_BLOCK;
    }

    public void allocateBlock(int index, String ownerFileId, int nextBlockIndex, boolean systemReserved) {
        validateIndex(index);
        if (nextBlockIndex != DiskBlock.NO_NEXT_BLOCK) {
            validateIndex(nextBlockIndex);
        }
        blocks[index].allocate(ownerFileId, nextBlockIndex, systemReserved);
    }

    public void updateNextBlockIndex(int index, int nextBlockIndex) {
        validateIndex(index);
        if (nextBlockIndex != DiskBlock.NO_NEXT_BLOCK) {
            validateIndex(nextBlockIndex);
        }
        blocks[index].updateNextBlockIndex(nextBlockIndex);
    }

    public void freeBlock(int index) {
        validateIndex(index);
        blocks[index].release();
    }

    public void moveHeadTo(int blockIndex) {
        validateIndex(blockIndex);
        head.moveTo(blockIndex);
    }

    public void setBlockOccupantProcessId(int index, String processId) {
        validateIndex(index);
        blocks[index].setOccupiedByProcessId(processId);
    }

    public void clearAllBlockOccupants() {
        for (DiskBlock block : blocks) {
            block.setOccupiedByProcessId(null);
        }
    }

    public void setHeadDirection(DiskHeadDirection direction) {
        head.setDirection(direction);
    }

    private void validateIndex(int index) {
        if (!isValidIndex(index)) {
            throw new IndexOutOfBoundsException("block index out of range: " + index);
        }
    }
}
