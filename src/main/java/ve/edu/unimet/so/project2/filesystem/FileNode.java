package ve.edu.unimet.so.project2.filesystem;

public class FileNode extends FsNode {

    public static final int NO_BLOCK = -1;

    private int sizeInBlocks;
    private int firstBlockIndex;
    private String colorId;
    private final boolean systemFile;

    public FileNode(
            String id,
            String name,
            String ownerUserId,
            AccessPermissions permissions,
            int sizeInBlocks,
            int firstBlockIndex,
            String colorId,
            boolean systemFile) {
        super(id, name, ownerUserId, permissions, FsNodeType.FILE, false);
        validateAllocation(sizeInBlocks, firstBlockIndex);
        this.sizeInBlocks = sizeInBlocks;
        this.firstBlockIndex = firstBlockIndex;
        this.colorId = normalizeColorId(colorId);
        this.systemFile = systemFile;
    }

    public int getSizeInBlocks() {
        return sizeInBlocks;
    }

    public int getFirstBlockIndex() {
        return firstBlockIndex;
    }

    public String getColorId() {
        return colorId;
    }

    public boolean isSystemFile() {
        return systemFile;
    }

    public boolean hasAllocatedBlocks() {
        return firstBlockIndex != NO_BLOCK;
    }

    public void updateAllocation(int sizeInBlocks, int firstBlockIndex) {
        validateAllocation(sizeInBlocks, firstBlockIndex);
        this.sizeInBlocks = sizeInBlocks;
        this.firstBlockIndex = firstBlockIndex;
    }

    public void clearAllocation() {
        throw new IllegalStateException("regular files cannot exist without allocated blocks");
    }

    public void setColorId(String colorId) {
        this.colorId = normalizeColorId(colorId);
    }

    private static void validateAllocation(int sizeInBlocks, int firstBlockIndex) {
        if (sizeInBlocks <= 0) {
            throw new IllegalArgumentException("sizeInBlocks must be greater than zero");
        }

        if (firstBlockIndex < 0) {
            throw new IllegalArgumentException("allocated file must have a valid firstBlockIndex");
        }
    }

    private static String normalizeColorId(String colorId) {
        if (colorId == null) {
            return null;
        }
        String normalized = colorId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("colorId cannot be blank");
        }
        return normalized;
    }
}
