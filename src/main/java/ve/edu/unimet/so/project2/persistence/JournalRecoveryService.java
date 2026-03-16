package ve.edu.unimet.so.project2.persistence;

import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.disk.DiskBlock;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.AccessPermissions;
import ve.edu.unimet.so.project2.filesystem.DirectoryNode;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FileSystemCatalog;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.journal.JournalEntry;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.journal.undo.CreateDirectoryUndoData;
import ve.edu.unimet.so.project2.journal.undo.CreateFileUndoData;
import ve.edu.unimet.so.project2.journal.undo.DeleteDirectoryUndoData;
import ve.edu.unimet.so.project2.journal.undo.DeleteFileUndoData;
import ve.edu.unimet.so.project2.journal.undo.JournalBlockSnapshot;
import ve.edu.unimet.so.project2.journal.undo.JournalNodeSnapshot;
import ve.edu.unimet.so.project2.journal.undo.JournalUndoData;
import ve.edu.unimet.so.project2.journal.undo.UpdateRenameUndoData;

public final class JournalRecoveryService {

    public void recoverPendingEntries(
            SimulationApplicationState applicationState,
            SimulatedDisk disk,
            JournalManager journalManager) {
        if (applicationState == null) {
            throw new IllegalArgumentException("applicationState cannot be null");
        }
        if (disk == null) {
            throw new IllegalArgumentException("disk cannot be null");
        }
        if (journalManager == null) {
            throw new IllegalArgumentException("journalManager cannot be null");
        }

        Object[] entries = journalManager.getEntriesSnapshot();
        for (int i = entries.length - 1; i >= 0; i--) {
            JournalEntry entry = (JournalEntry) entries[i];
            if (!entry.isPending()) {
                continue;
            }
            undoEntry(entry, applicationState, disk);
            journalManager.markUndone(entry.getTransactionId());
        }
    }

    private void undoEntry(
            JournalEntry entry,
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        JournalUndoData undoData = entry.getUndoData();
        if (undoData instanceof CreateFileUndoData createFileUndoData) {
            undoCreateFile(createFileUndoData, applicationState, disk);
            return;
        }
        if (undoData instanceof CreateDirectoryUndoData createDirectoryUndoData) {
            undoCreateDirectory(createDirectoryUndoData, applicationState);
            return;
        }
        if (undoData instanceof DeleteFileUndoData deleteFileUndoData) {
            undoDeleteFile(deleteFileUndoData, applicationState, disk);
            return;
        }
        if (undoData instanceof DeleteDirectoryUndoData deleteDirectoryUndoData) {
            undoDeleteDirectory(deleteDirectoryUndoData, applicationState, disk);
            return;
        }
        undoRename((UpdateRenameUndoData) undoData, applicationState);
    }

    private void undoCreateFile(
            CreateFileUndoData undoData,
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        FileSystemCatalog catalog = applicationState.getFileSystemCatalog();
        FsNode existingNode = catalog.findById(undoData.getCreatedFileId());
        if (existingNode instanceof FileNode fileNode) {
            catalog.removeNode(fileNode);
        } else if (existingNode instanceof DirectoryNode) {
            throw new IllegalArgumentException("create-file recovery found directory with created file id");
        }
        freeBlocksIfOwnedBy(
                disk,
                undoData.getCreatedFileId(),
                undoData.getAllocatedBlockIndexesSnapshot());
    }

    private void undoCreateDirectory(
            CreateDirectoryUndoData undoData,
            SimulationApplicationState applicationState) {
        FileSystemCatalog catalog = applicationState.getFileSystemCatalog();
        FsNode existingNode = catalog.findById(undoData.getCreatedDirectoryId());
        if (existingNode == null) {
            return;
        }
        if (!(existingNode instanceof DirectoryNode directoryNode)) {
            throw new IllegalArgumentException("create-directory recovery found non-directory node");
        }
        catalog.removeNode(directoryNode);
    }

    private void undoDeleteFile(
            DeleteFileUndoData undoData,
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        FileSystemCatalog catalog = applicationState.getFileSystemCatalog();
        DirectoryNode parent = catalog.requireDirectoryById(undoData.getParentDirectoryId());
        JournalNodeSnapshot snapshot = undoData.getDeletedFileSnapshot();
        if (catalog.findById(snapshot.getNodeId()) != null) {
            throw new IllegalArgumentException("cannot recover deleted file; node already exists");
        }
        JournalBlockSnapshot[] blockSnapshots = undoData.getBlockChainSnapshotsSnapshot();
        allocateRestoredBlocks(disk, blockSnapshots);
        try {
            catalog.addNode(parent, new FileNode(
                    snapshot.getNodeId(),
                    snapshot.getName(),
                    snapshot.getOwnerUserId(),
                    toPermissions(snapshot.isPublicReadable()),
                    snapshot.getSizeInBlocks(),
                    snapshot.getFirstBlockIndex(),
                    snapshot.getColorId(),
                    snapshot.isSystemFile()));
        } catch (RuntimeException exception) {
            freeAllocatedSnapshots(disk, blockSnapshots, blockSnapshots.length);
            throw exception;
        }
    }

    private void undoDeleteDirectory(
            DeleteDirectoryUndoData undoData,
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        FileSystemCatalog catalog = applicationState.getFileSystemCatalog();
        if (catalog.findById(undoData.getDeletedDirectoryId()) != null) {
            throw new IllegalArgumentException("cannot recover deleted directory; node already exists");
        }

        JournalNodeSnapshot[] subtree = undoData.getSubtreeNodeSnapshotsSnapshot();
        Object[] rebuiltNodes = new Object[subtree.length];
        for (int i = 0; i < subtree.length; i++) {
            rebuiltNodes[i] = createNodeFromSnapshot(subtree[i]);
        }

        DirectoryNode externalParent = catalog.requireDirectoryById(undoData.getParentDirectoryId());
        attachRecoveredSubtree(rebuiltNodes, subtree, undoData.getDeletedDirectoryId());
        JournalBlockSnapshot[] blockSnapshots = undoData.getAssociatedBlockSnapshotsSnapshot();
        allocateRestoredBlocks(disk, blockSnapshots);
        try {
            catalog.addNode(externalParent, (FsNode) findRecoveredNode(rebuiltNodes, undoData.getDeletedDirectoryId()));
        } catch (RuntimeException exception) {
            freeAllocatedSnapshots(disk, blockSnapshots, blockSnapshots.length);
            throw exception;
        }
    }

    private void undoRename(
            UpdateRenameUndoData undoData,
            SimulationApplicationState applicationState) {
        FsNode node = applicationState.getFileSystemCatalog().requireById(undoData.getTargetNodeId());
        node.rename(undoData.getOldName());
    }

    private void allocateRestoredBlocks(SimulatedDisk disk, JournalBlockSnapshot[] snapshots) {
        int allocatedCount = 0;
        try {
            for (JournalBlockSnapshot snapshot : snapshots) {
                if (!disk.getBlock(snapshot.getIndex()).isFree()) {
                    throw new IllegalArgumentException("cannot recover journal entry; target block is occupied");
                }
                disk.allocateBlock(
                        snapshot.getIndex(),
                        snapshot.getOwnerFileId(),
                        snapshot.getNextBlockIndex(),
                        snapshot.isSystemReserved());
                allocatedCount++;
            }
        } catch (RuntimeException exception) {
            freeAllocatedSnapshots(disk, snapshots, allocatedCount);
            throw exception;
        }
    }

    private void freeAllocatedSnapshots(SimulatedDisk disk, JournalBlockSnapshot[] snapshots, int count) {
        for (int i = 0; i < count; i++) {
            JournalBlockSnapshot snapshot = snapshots[i];
            DiskBlock block = disk.getBlock(snapshot.getIndex());
            if (!block.isFree() && sameOptionalValue(block.getOwnerFileId(), snapshot.getOwnerFileId())) {
                disk.freeBlock(snapshot.getIndex());
            }
        }
    }

    private void freeBlocksIfOwnedBy(SimulatedDisk disk, String ownerFileId, int[] blockIndexes) {
        for (int blockIndex : blockIndexes) {
            DiskBlock block = disk.getBlock(blockIndex);
            if (!block.isFree() && ownerFileId.equals(block.getOwnerFileId())) {
                disk.freeBlock(blockIndex);
            }
        }
    }

    private FsNode createNodeFromSnapshot(JournalNodeSnapshot snapshot) {
        if (snapshot.isDirectory()) {
            if (snapshot.isRoot()) {
                return DirectoryNode.createRoot(
                        snapshot.getNodeId(),
                        snapshot.getOwnerUserId(),
                        toPermissions(snapshot.isPublicReadable()));
            }
            return new DirectoryNode(
                    snapshot.getNodeId(),
                    snapshot.getName(),
                    snapshot.getOwnerUserId(),
                    toPermissions(snapshot.isPublicReadable()));
        }
        return new FileNode(
                snapshot.getNodeId(),
                snapshot.getName(),
                snapshot.getOwnerUserId(),
                toPermissions(snapshot.isPublicReadable()),
                snapshot.getSizeInBlocks(),
                snapshot.getFirstBlockIndex(),
                snapshot.getColorId(),
                snapshot.isSystemFile());
    }

    private void attachRecoveredSubtree(
            Object[] rebuiltNodes,
            JournalNodeSnapshot[] subtreeSnapshots,
            String deletedDirectoryId) {
        int attachedCount = 1;
        while (attachedCount < rebuiltNodes.length) {
            boolean progressed = false;
            for (JournalNodeSnapshot snapshot : subtreeSnapshots) {
                if (snapshot.getNodeId().equals(deletedDirectoryId)) {
                    continue;
                }
                FsNode node = (FsNode) findRecoveredNode(rebuiltNodes, snapshot.getNodeId());
                if (node.isAttached()) {
                    continue;
                }
                FsNode parent = (FsNode) findRecoveredNode(rebuiltNodes, snapshot.getParentNodeId());
                if (!(parent instanceof DirectoryNode directory)) {
                    continue;
                }
                directory.addChild(node);
                progressed = true;
                attachedCount++;
            }
            if (!progressed) {
                throw new IllegalArgumentException("failed to rebuild deleted directory subtree");
            }
        }
    }

    private Object findRecoveredNode(Object[] nodes, String nodeId) {
        for (Object object : nodes) {
            FsNode node = (FsNode) object;
            if (node.getId().equals(nodeId)) {
                return node;
            }
        }
        throw new IllegalArgumentException("recovered node not found: " + nodeId);
    }

    private AccessPermissions toPermissions(boolean publicReadable) {
        return publicReadable ? AccessPermissions.publicReadAccess() : AccessPermissions.privateAccess();
    }

    private boolean sameOptionalValue(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}
