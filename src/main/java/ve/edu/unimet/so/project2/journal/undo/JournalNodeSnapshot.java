package ve.edu.unimet.so.project2.journal.undo;

import ve.edu.unimet.so.project2.filesystem.FsNodeType;

public final class JournalNodeSnapshot {

    public static final int NO_BLOCK = -1;

    private final String nodeId;
    private final FsNodeType nodeType;
    private final String name;
    private final String ownerUserId;
    private final String parentNodeId;
    private final String nodePath;
    private final boolean publicReadable;
    private final int sizeInBlocks;
    private final int firstBlockIndex;
    private final String colorId;
    private final boolean systemFile;
    private final boolean root;

    public JournalNodeSnapshot(
            String nodeId,
            FsNodeType nodeType,
            String name,
            String ownerUserId,
            String parentNodeId,
            String nodePath,
            boolean publicReadable,
            int sizeInBlocks,
            int firstBlockIndex,
            String colorId,
            boolean systemFile) {
        this.nodeId = requireNonBlank(nodeId, "nodeId");
        if (nodeType == null) {
            throw new IllegalArgumentException("nodeType cannot be null");
        }
        this.root = validateSnapshotIdentity(nodeType, name, parentNodeId, nodePath);
        this.ownerUserId = requireNonBlank(ownerUserId, "ownerUserId");
        this.parentNodeId = root ? null : requireNonBlank(parentNodeId, "parentNodeId");
        this.nodePath = requireNonBlank(nodePath, "nodePath");
        this.colorId = normalizeOptional(colorId);
        validateNodeSpecificFields(nodeType, sizeInBlocks, firstBlockIndex, this.colorId);

        this.nodeType = nodeType;
        this.name = name;
        this.publicReadable = publicReadable;
        this.sizeInBlocks = sizeInBlocks;
        this.firstBlockIndex = firstBlockIndex;
        this.systemFile = systemFile;
    }

    public String getNodeId() {
        return nodeId;
    }

    public FsNodeType getNodeType() {
        return nodeType;
    }

    public String getName() {
        return name;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public String getParentNodeId() {
        return parentNodeId;
    }

    public String getNodePath() {
        return nodePath;
    }

    public boolean isPublicReadable() {
        return publicReadable;
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

    public boolean isFile() {
        return nodeType == FsNodeType.FILE;
    }

    public boolean isDirectory() {
        return nodeType == FsNodeType.DIRECTORY;
    }

    public boolean isRoot() {
        return root;
    }

    private static void validateNodeSpecificFields(
            FsNodeType nodeType,
            int sizeInBlocks,
            int firstBlockIndex,
            String colorId) {
        if (nodeType == FsNodeType.FILE) {
            if (sizeInBlocks <= 0) {
                throw new IllegalArgumentException("file snapshots must keep a positive sizeInBlocks");
            }
            if (firstBlockIndex < 0) {
                throw new IllegalArgumentException("file snapshots must keep a valid firstBlockIndex");
            }
            return;
        }

        if (sizeInBlocks != 0) {
            throw new IllegalArgumentException("directory snapshots must use sizeInBlocks == 0");
        }
        if (firstBlockIndex != NO_BLOCK) {
            throw new IllegalArgumentException("directory snapshots must use NO_BLOCK as firstBlockIndex");
        }
        if (colorId != null) {
            throw new IllegalArgumentException("directory snapshots must not keep a colorId");
        }
    }

    private static boolean validateSnapshotIdentity(
            FsNodeType nodeType,
            String name,
            String parentNodeId,
            String nodePath) {
        if ("/".equals(name)) {
            if (nodeType != FsNodeType.DIRECTORY) {
                throw new IllegalArgumentException("only directories can use / as name");
            }
            if (!"/".equals(nodePath)) {
                throw new IllegalArgumentException("root snapshots must use / as nodePath");
            }
            if (parentNodeId != null && !parentNodeId.isBlank()) {
                throw new IllegalArgumentException("root snapshots must not declare a parentNodeId");
            }
            return true;
        }

        validateRegularNodeName(name);
        requireNonBlank(parentNodeId, "parentNodeId");
        validateCanonicalNonRootPath(name, nodePath);
        return false;
    }

    private static void validateRegularNodeName(String value) {
        requireNonBlank(value, "name");
        if (value.contains("/")) {
            throw new IllegalArgumentException("name cannot contain /");
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("name cannot be . or ..");
        }
    }

    private static void validateCanonicalNonRootPath(String name, String nodePath) {
        requireNonBlank(nodePath, "nodePath");
        if (!nodePath.startsWith("/")) {
            throw new IllegalArgumentException("non-root nodePath must start with /");
        }
        if ("/".equals(nodePath)) {
            throw new IllegalArgumentException("only root snapshots can use / as nodePath");
        }
        if (nodePath.endsWith("/")) {
            throw new IllegalArgumentException("non-root nodePath must not end with /");
        }
        if (nodePath.contains("//")) {
            throw new IllegalArgumentException("nodePath must not contain empty path segments");
        }
        if (nodePath.contains("/./") || nodePath.endsWith("/.") || nodePath.contains("/../") || nodePath.endsWith("/..")) {
            throw new IllegalArgumentException("nodePath must not contain . or .. path segments");
        }

        int lastSlash = nodePath.lastIndexOf('/');
        if (lastSlash < 0) {
            throw new IllegalArgumentException("nodePath must contain at least one /");
        }

        String lastSegment = nodePath.substring(lastSlash + 1);
        if (!name.equals(lastSegment)) {
            throw new IllegalArgumentException("nodePath must end with the snapshot name");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

}
