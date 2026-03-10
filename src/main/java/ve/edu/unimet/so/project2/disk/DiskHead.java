package ve.edu.unimet.so.project2.disk;

public class DiskHead {

    private int currentBlock;
    private DiskHeadDirection direction;

    DiskHead(int currentBlock, DiskHeadDirection direction) {
        if (currentBlock < 0) {
            throw new IllegalArgumentException("currentBlock cannot be negative");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction cannot be null");
        }
        this.currentBlock = currentBlock;
        this.direction = direction;
    }

    DiskHead(DiskHead source) {
        this(source.currentBlock, source.direction);
    }

    public int getCurrentBlock() {
        return currentBlock;
    }

    public DiskHeadDirection getDirection() {
        return direction;
    }

    void moveTo(int targetBlock) {
        if (targetBlock < 0) {
            throw new IllegalArgumentException("targetBlock cannot be negative");
        }
        this.currentBlock = targetBlock;
    }

    void setDirection(DiskHeadDirection direction) {
        if (direction == null) {
            throw new IllegalArgumentException("direction cannot be null");
        }
        this.direction = direction;
    }
}
