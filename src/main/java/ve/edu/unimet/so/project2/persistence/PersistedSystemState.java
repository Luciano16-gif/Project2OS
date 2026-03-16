package ve.edu.unimet.so.project2.persistence;

import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.filesystem.FsNodeType;
import ve.edu.unimet.so.project2.journal.JournalStatus;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;
import ve.edu.unimet.so.project2.session.Role;

public record PersistedSystemState(
        DiskSchedulingPolicy policy,
        int headBlock,
        DiskHeadDirection headDirection,
        FileSystemNodeData[] fileSystemNodes,
        DiskBlockData[] diskBlocks,
        UserData[] users,
        String currentUserId,
        JournalEntryData[] journalEntries) {

    public record FileSystemNodeData(
            String id,
            String parentId,
            FsNodeType type,
            String name,
            String path,
            String ownerUserId,
            boolean publicReadable,
            int sizeInBlocks,
            int firstBlockIndex,
            String colorId,
            boolean systemFile) {
    }

    public record DiskBlockData(
            int index,
            boolean free,
            String ownerFileId,
            int nextBlockIndex,
            boolean systemReserved) {
    }

    public record UserData(
            String userId,
            String username,
            Role role) {
    }

    public record JournalEntryData(
            String transactionId,
            IoOperationType operationType,
            String targetPath,
            JournalStatus status,
            UndoData undoData,
            String targetNodeId,
            String ownerUserId,
            String description) {
    }

    public record UndoData(
            String kind,
            String createdNodeId,
            String deletedDirectoryId,
            String parentDirectoryId,
            String parentDirectoryPath,
            int[] allocatedBlockIndexes,
            NodeSnapshotData deletedFileSnapshot,
            NodeSnapshotData[] subtreeNodeSnapshots,
            BlockSnapshotData[] blockSnapshots,
            String targetNodeId,
            String oldName,
            String newName) {
    }

    public record NodeSnapshotData(
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
    }

    public record BlockSnapshotData(
            int index,
            String ownerFileId,
            int nextBlockIndex,
            boolean systemReserved) {
    }
}
