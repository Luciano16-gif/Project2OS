package ve.edu.unimet.so.project2.journal.undo;

import ve.edu.unimet.so.project2.filesystem.FsNodeType;

public record JournalNodeSnapshot(
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

    public static final int NO_BLOCK = -1;

    public JournalNodeSnapshot {
        nodeId = JournalUndoData.requireNonBlank(nodeId, "nodeId");
        if (nodeType == null) {
            throw new IllegalArgumentException("nodeType cannot be null");
        }
        boolean root = validateSnapshotIdentity(nodeType, name, parentNodeId, nodePath);
        ownerUserId = JournalUndoData.requireNonBlank(ownerUserId, "ownerUserId");
        parentNodeId = root ? null : JournalUndoData.requireNonBlank(parentNodeId, "parentNodeId");
        nodePath = JournalUndoData.requireNonBlank(nodePath, "nodePath");
        colorId = normalizeOptional(colorId);
        validateNodeSpecificFields(nodeType, sizeInBlocks, firstBlockIndex, colorId);
    }

    public String getNodeId() { return nodeId; }
    public FsNodeType getNodeType() { return nodeType; }
    public String getName() { return name; }
    public String getOwnerUserId() { return ownerUserId; }
    public String getParentNodeId() { return parentNodeId; }
    public String getNodePath() { return nodePath; }
    public boolean isPublicReadable() { return publicReadable; }
    public int getSizeInBlocks() { return sizeInBlocks; }
    public int getFirstBlockIndex() { return firstBlockIndex; }
    public String getColorId() { return colorId; }
    public boolean isSystemFile() { return systemFile; }
    public boolean isFile() { return nodeType == FsNodeType.FILE; }
    public boolean isDirectory() { return nodeType == FsNodeType.DIRECTORY; }
    public boolean isRoot() { return "/".equals(name); }

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
        JournalUndoData.requireNonBlank(parentNodeId, "parentNodeId");
        validateCanonicalNonRootPath(name, nodePath);
        return false;
    }

    private static void validateRegularNodeName(String value) {
        JournalUndoData.requireNonBlank(value, "name");
        if (value.contains("/")) {
            throw new IllegalArgumentException("name cannot contain /");
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("name cannot be . or ..");
        }
    }

    private static void validateCanonicalNonRootPath(String name, String nodePath) {
        JournalUndoData.requireNonBlank(nodePath, "nodePath");
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
        if (!name.equals(nodePath.substring(lastSlash + 1))) {
            throw new IllegalArgumentException("nodePath must end with the snapshot name");
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
