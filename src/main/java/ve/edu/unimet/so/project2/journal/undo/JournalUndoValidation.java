package ve.edu.unimet.so.project2.journal.undo;

final class JournalUndoValidation {

    private JournalUndoValidation() {
    }

    static void validateDeletedFileChain(
            JournalNodeSnapshot fileSnapshot,
            JournalBlockSnapshot[] blockSnapshots) {
        if (fileSnapshot == null) {
            throw new IllegalArgumentException("fileSnapshot cannot be null");
        }
        if (!fileSnapshot.isFile()) {
            throw new IllegalArgumentException("fileSnapshot must represent a file");
        }
        if (blockSnapshots == null || blockSnapshots.length == 0) {
            throw new IllegalArgumentException("blockSnapshots cannot be null or empty");
        }
        if (blockSnapshots.length != fileSnapshot.getSizeInBlocks()) {
            throw new IllegalArgumentException("blockSnapshots must match the file sizeInBlocks");
        }

        validateUniqueBlockIndexes(blockSnapshots, "blockSnapshots");

        for (JournalBlockSnapshot snapshot : blockSnapshots) {
            if (snapshot == null) {
                throw new IllegalArgumentException("blockSnapshots cannot contain null entries");
            }
            if (!snapshot.getOwnerFileId().equals(fileSnapshot.getNodeId())) {
                throw new IllegalArgumentException("all block snapshots must belong to the deleted file");
            }
        }

        JournalBlockSnapshot current = findBlockByIndex(blockSnapshots, fileSnapshot.getFirstBlockIndex());
        if (current == null) {
            throw new IllegalArgumentException("blockSnapshots must include the firstBlockIndex of the deleted file");
        }

        int visited = 0;
        while (true) {
            visited++;
            if (visited > blockSnapshots.length) {
                throw new IllegalArgumentException("block chain contains a cycle or extra indirection");
            }
            if (current.getNextBlockIndex() == JournalBlockSnapshot.NO_NEXT_BLOCK) {
                break;
            }

            JournalBlockSnapshot next = findBlockByIndex(blockSnapshots, current.getNextBlockIndex());
            if (next == null) {
                throw new IllegalArgumentException("block chain is missing an intermediate or tail block");
            }
            current = next;
        }

        if (visited != blockSnapshots.length) {
            throw new IllegalArgumentException("blockSnapshots must describe the complete deleted-file chain");
        }
    }

    static void validateDeletedDirectorySubtree(
            String deletedDirectoryId,
            JournalNodeSnapshot[] subtreeNodeSnapshots,
            JournalBlockSnapshot[] associatedBlockSnapshots) {
        if (subtreeNodeSnapshots == null || subtreeNodeSnapshots.length == 0) {
            throw new IllegalArgumentException("subtreeNodeSnapshots cannot be null or empty");
        }
        if (associatedBlockSnapshots == null) {
            throw new IllegalArgumentException("associatedBlockSnapshots cannot be null");
        }

        validateUniqueNodeIds(subtreeNodeSnapshots);
        validateUniqueBlockIndexes(associatedBlockSnapshots, "associatedBlockSnapshots");
        validateSubtreeContainment(deletedDirectoryId, subtreeNodeSnapshots);
        validateSubtreePathsMatchHierarchy(deletedDirectoryId, subtreeNodeSnapshots);
        validateSiblingNameUniqueness(subtreeNodeSnapshots);
        validateAssociatedBlocksBelongToSubtreeFiles(subtreeNodeSnapshots, associatedBlockSnapshots);
        validateEachDeletedFileHasACompleteChain(subtreeNodeSnapshots, associatedBlockSnapshots);
    }

    private static void validateSubtreeContainment(String deletedDirectoryId, JournalNodeSnapshot[] subtreeNodeSnapshots) {
        JournalNodeSnapshot deletedRoot = findNodeById(subtreeNodeSnapshots, deletedDirectoryId);
        if (deletedRoot == null || !deletedRoot.isDirectory()) {
            throw new IllegalArgumentException("the deleted subtree root must exist and be a directory");
        }
        validateDeletedRootParentBoundary(deletedRoot, subtreeNodeSnapshots);

        for (JournalNodeSnapshot snapshot : subtreeNodeSnapshots) {
            if (snapshot == null) {
                throw new IllegalArgumentException("subtreeNodeSnapshots cannot contain null entries");
            }

            if (snapshot.getNodeId().equals(deletedDirectoryId)) {
                if (!snapshot.isDirectory()) {
                    throw new IllegalArgumentException("the deleted subtree root must be a directory");
                }
                continue;
            }

            String parentNodeId = snapshot.getParentNodeId();
            if (parentNodeId == null) {
                throw new IllegalArgumentException("subtree child snapshots must keep a valid parentNodeId");
            }
            JournalNodeSnapshot parentSnapshot = findNodeById(subtreeNodeSnapshots, parentNodeId);
            if (parentSnapshot == null) {
                throw new IllegalArgumentException("subtree child snapshots must point to parents inside the same subtree");
            }
            if (!parentSnapshot.isDirectory()) {
                throw new IllegalArgumentException("subtree child snapshots must point only to directory parents");
            }
            ensureNodeReachesDeletedRoot(snapshot, deletedDirectoryId, subtreeNodeSnapshots);
        }
    }

    private static void validateDeletedRootParentBoundary(
            JournalNodeSnapshot deletedRoot,
            JournalNodeSnapshot[] subtreeNodeSnapshots) {
        if (deletedRoot.getParentNodeId() == null) {
            throw new IllegalArgumentException("the deleted subtree root must keep a valid external parentNodeId");
        }
        if (findNodeById(subtreeNodeSnapshots, deletedRoot.getParentNodeId()) != null) {
            throw new IllegalArgumentException("the deleted subtree root cannot point to a parent inside the deleted subtree");
        }
    }

    private static void ensureNodeReachesDeletedRoot(
            JournalNodeSnapshot startNode,
            String deletedDirectoryId,
            JournalNodeSnapshot[] subtreeNodeSnapshots) {
        JournalNodeSnapshot current = startNode;
        int traversed = 0;

        while (!current.getNodeId().equals(deletedDirectoryId)) {
            traversed++;
            if (traversed > subtreeNodeSnapshots.length) {
                throw new IllegalArgumentException("subtreeNodeSnapshots must form a single tree rooted at the deleted directory");
            }

            String parentNodeId = current.getParentNodeId();
            if (parentNodeId == null) {
                throw new IllegalArgumentException("subtree child snapshots must keep a valid parentNodeId");
            }

            current = findNodeById(subtreeNodeSnapshots, parentNodeId);
            if (current == null) {
                throw new IllegalArgumentException("subtree child snapshots must point to parents inside the same subtree");
            }
            if (!current.isDirectory()) {
                throw new IllegalArgumentException("subtree child snapshots must point only to directory parents");
            }
        }
    }

    private static void validateAssociatedBlocksBelongToSubtreeFiles(
            JournalNodeSnapshot[] subtreeNodeSnapshots,
            JournalBlockSnapshot[] associatedBlockSnapshots) {
        for (JournalBlockSnapshot blockSnapshot : associatedBlockSnapshots) {
            if (blockSnapshot == null) {
                throw new IllegalArgumentException("associatedBlockSnapshots cannot contain null entries");
            }

            JournalNodeSnapshot ownerFile = findNodeById(subtreeNodeSnapshots, blockSnapshot.getOwnerFileId());
            if (ownerFile == null || !ownerFile.isFile()) {
                throw new IllegalArgumentException("every associated block must belong to a file inside the deleted subtree");
            }
        }
    }

    private static void validateSubtreePathsMatchHierarchy(
            String deletedDirectoryId,
            JournalNodeSnapshot[] subtreeNodeSnapshots) {
        for (JournalNodeSnapshot snapshot : subtreeNodeSnapshots) {
            if (snapshot == null || snapshot.getNodeId().equals(deletedDirectoryId)) {
                continue;
            }

            JournalNodeSnapshot parentSnapshot = findNodeById(subtreeNodeSnapshots, snapshot.getParentNodeId());
            if (parentSnapshot == null) {
                throw new IllegalArgumentException("subtree child snapshots must point to parents inside the same subtree");
            }

            String expectedPath = deriveChildPath(parentSnapshot.getNodePath(), snapshot.getName());
            if (!expectedPath.equals(snapshot.getNodePath())) {
                throw new IllegalArgumentException("subtree node paths must match the parent hierarchy and node name");
            }
        }
    }

    private static void validateSiblingNameUniqueness(JournalNodeSnapshot[] subtreeNodeSnapshots) {
        for (int i = 0; i < subtreeNodeSnapshots.length; i++) {
            JournalNodeSnapshot left = subtreeNodeSnapshots[i];
            if (left == null || left.getParentNodeId() == null) {
                continue;
            }

            for (int j = i + 1; j < subtreeNodeSnapshots.length; j++) {
                JournalNodeSnapshot right = subtreeNodeSnapshots[j];
                if (right == null || right.getParentNodeId() == null) {
                    continue;
                }
                if (left.getParentNodeId().equals(right.getParentNodeId())
                        && left.getName().equals(right.getName())) {
                    throw new IllegalArgumentException(
                            "subtreeNodeSnapshots cannot contain duplicate sibling names under the same parent");
                }
            }
        }
    }

    private static void validateEachDeletedFileHasACompleteChain(
            JournalNodeSnapshot[] subtreeNodeSnapshots,
            JournalBlockSnapshot[] associatedBlockSnapshots) {
        for (JournalNodeSnapshot snapshot : subtreeNodeSnapshots) {
            if (!snapshot.isFile()) {
                continue;
            }
            JournalBlockSnapshot[] fileBlocks = collectBlocksForOwner(associatedBlockSnapshots, snapshot.getNodeId());
            validateDeletedFileChain(snapshot, fileBlocks);
        }
    }

    private static JournalBlockSnapshot[] collectBlocksForOwner(
            JournalBlockSnapshot[] associatedBlockSnapshots,
            String ownerFileId) {
        int matchCount = 0;
        for (JournalBlockSnapshot snapshot : associatedBlockSnapshots) {
            if (snapshot.getOwnerFileId().equals(ownerFileId)) {
                matchCount++;
            }
        }

        JournalBlockSnapshot[] matches = new JournalBlockSnapshot[matchCount];
        int nextIndex = 0;
        for (JournalBlockSnapshot snapshot : associatedBlockSnapshots) {
            if (snapshot.getOwnerFileId().equals(ownerFileId)) {
                matches[nextIndex++] = snapshot;
            }
        }
        return matches;
    }

    private static JournalNodeSnapshot findNodeById(JournalNodeSnapshot[] snapshots, String nodeId) {
        for (JournalNodeSnapshot snapshot : snapshots) {
            if (snapshot != null && snapshot.getNodeId().equals(nodeId)) {
                return snapshot;
            }
        }
        return null;
    }

    private static JournalBlockSnapshot findBlockByIndex(JournalBlockSnapshot[] snapshots, int blockIndex) {
        for (JournalBlockSnapshot snapshot : snapshots) {
            if (snapshot != null && snapshot.getIndex() == blockIndex) {
                return snapshot;
            }
        }
        return null;
    }

    private static String deriveChildPath(String parentPath, String childName) {
        if ("/".equals(parentPath)) {
            return "/" + childName;
        }
        return parentPath + "/" + childName;
    }

    private static void validateUniqueNodeIds(JournalNodeSnapshot[] snapshots) {
        for (int i = 0; i < snapshots.length; i++) {
            JournalNodeSnapshot left = snapshots[i];
            if (left == null) {
                throw new IllegalArgumentException("subtreeNodeSnapshots cannot contain null entries");
            }
            for (int j = i + 1; j < snapshots.length; j++) {
                JournalNodeSnapshot right = snapshots[j];
                if (right == null) {
                    throw new IllegalArgumentException("subtreeNodeSnapshots cannot contain null entries");
                }
                if (left.getNodeId().equals(right.getNodeId())) {
                    throw new IllegalArgumentException("subtreeNodeSnapshots cannot repeat node ids");
                }
            }
        }
    }

    private static void validateUniqueBlockIndexes(JournalBlockSnapshot[] snapshots, String fieldName) {
        for (int i = 0; i < snapshots.length; i++) {
            JournalBlockSnapshot left = snapshots[i];
            if (left == null) {
                throw new IllegalArgumentException(fieldName + " cannot contain null entries");
            }
            for (int j = i + 1; j < snapshots.length; j++) {
                JournalBlockSnapshot right = snapshots[j];
                if (right == null) {
                    throw new IllegalArgumentException(fieldName + " cannot contain null entries");
                }
                if (left.getIndex() == right.getIndex()) {
                    throw new IllegalArgumentException(fieldName + " cannot repeat block indexes");
                }
            }
        }
    }
}
