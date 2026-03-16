package ve.edu.unimet.so.project2.persistence;

import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.disk.DiskBlock;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.AccessPermissions;
import ve.edu.unimet.so.project2.filesystem.DirectoryNode;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FileSystemCatalog;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.filesystem.FsNodeType;
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
import ve.edu.unimet.so.project2.session.SessionContext;
import ve.edu.unimet.so.project2.session.User;
import ve.edu.unimet.so.project2.session.UserStore;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

public final class SystemStateMapper {

    public PersistedSystemState export(
            DiskSchedulingPolicy policy,
            SimulatedDisk disk,
            SimulationApplicationState applicationState,
            JournalManager journalManager) {
        FsNode[] nodes = applicationState.getFileSystemCatalog().getAllNodesSnapshot();
        PersistedSystemState.FileSystemNodeData[] persistedNodes =
                new PersistedSystemState.FileSystemNodeData[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            persistedNodes[i] = toPersistedNode(nodes[i]);
        }

        PersistedSystemState.DiskBlockData[] persistedBlocks =
                new PersistedSystemState.DiskBlockData[disk.getTotalBlocks()];
        for (int i = 0; i < persistedBlocks.length; i++) {
            DiskBlock block = disk.getBlock(i);
            persistedBlocks[i] = new PersistedSystemState.DiskBlockData(
                    block.getIndex(),
                    block.isFree(),
                    block.getOwnerFileId(),
                    block.getNextBlockIndex(),
                    block.isSystemReserved());
        }

        User[] users = applicationState.getUserStore().getUsersSnapshot();
        PersistedSystemState.UserData[] persistedUsers = new PersistedSystemState.UserData[users.length];
        for (int i = 0; i < users.length; i++) {
            persistedUsers[i] = new PersistedSystemState.UserData(
                    users[i].getUserId(),
                    users[i].getUsername(),
                    users[i].getRole());
        }

        Object[] entries = journalManager.getEntriesSnapshot();
        PersistedSystemState.JournalEntryData[] persistedEntries =
                new PersistedSystemState.JournalEntryData[entries.length];
        for (int i = 0; i < entries.length; i++) {
            persistedEntries[i] = toPersistedEntry((JournalEntry) entries[i]);
        }

        return new PersistedSystemState(
                policy,
                disk.getHead().getCurrentBlock(),
                disk.getHead().getDirection(),
                persistedNodes,
                persistedBlocks,
                persistedUsers,
                applicationState.getSessionContext().getCurrentUserId(),
                persistedEntries);
    }

    public LoadedSystemState importState(PersistedSystemState persistedState) {
        if (persistedState == null) {
            throw new IllegalArgumentException("persistedState cannot be null");
        }
        if (persistedState.policy() == null) {
            throw new IllegalArgumentException("persisted policy cannot be null");
        }

        UserStore userStore = rebuildUserStore(persistedState.users());
        SessionContext sessionContext = new SessionContext(userStore.requireById(persistedState.currentUserId()));
        FileSystemCatalog fileSystemCatalog = rebuildFileSystem(persistedState.fileSystemNodes());
        SimulationApplicationState applicationState = new SimulationApplicationState(
                fileSystemCatalog,
                userStore,
                sessionContext,
                deriveNextNodeNumber(fileSystemCatalog),
                deriveNextColorNumber(fileSystemCatalog));
        SimulatedDisk disk = rebuildDisk(
                persistedState.diskBlocks(),
                persistedState.headBlock(),
                persistedState.headDirection());
        JournalManager journalManager = rebuildJournal(persistedState.journalEntries());
        return new LoadedSystemState(disk, applicationState, journalManager, persistedState.policy());
    }

    private PersistedSystemState.FileSystemNodeData toPersistedNode(FsNode node) {
        if (node instanceof FileNode file) {
            return new PersistedSystemState.FileSystemNodeData(
                    file.getId(),
                    file.getParent() == null ? null : file.getParent().getId(),
                    file.getType(),
                    file.getName(),
                    file.getPath(),
                    file.getOwnerUserId(),
                    file.getPermissions().isPublicReadable(),
                    file.getSizeInBlocks(),
                    file.getFirstBlockIndex(),
                    file.getColorId(),
                    file.isSystemFile());
        }

        DirectoryNode directory = requireDirectoryNode(node);
        return new PersistedSystemState.FileSystemNodeData(
                directory.getId(),
                directory.getParent() == null ? null : directory.getParent().getId(),
                directory.getType(),
                directory.getName(),
                directory.getPath(),
                directory.getOwnerUserId(),
                directory.getPermissions().isPublicReadable(),
                0,
                JournalNodeSnapshot.NO_BLOCK,
                null,
                false);
    }

    private PersistedSystemState.JournalEntryData toPersistedEntry(JournalEntry entry) {
        return new PersistedSystemState.JournalEntryData(
                entry.getTransactionId(),
                entry.getOperationType(),
                entry.getTargetPath(),
                entry.getStatus(),
                toPersistedUndoData(entry.getUndoData()),
                entry.getTargetNodeId(),
                entry.getOwnerUserId(),
                entry.getDescription());
    }

    private PersistedSystemState.UndoData toPersistedUndoData(JournalUndoData undoData) {
        if (undoData instanceof CreateFileUndoData createFileUndoData) {
            return new PersistedSystemState.UndoData(
                    "CREATE_FILE",
                    createFileUndoData.getCreatedFileId(),
                    null,
                    createFileUndoData.getParentDirectoryId(),
                    createFileUndoData.getParentDirectoryPath(),
                    createFileUndoData.getAllocatedBlockIndexesSnapshot(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        if (undoData instanceof CreateDirectoryUndoData createDirectoryUndoData) {
            return new PersistedSystemState.UndoData(
                    "CREATE_DIRECTORY",
                    createDirectoryUndoData.getCreatedDirectoryId(),
                    null,
                    createDirectoryUndoData.getParentDirectoryId(),
                    createDirectoryUndoData.getParentDirectoryPath(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        if (undoData instanceof DeleteFileUndoData deleteFileUndoData) {
            return new PersistedSystemState.UndoData(
                    "DELETE_FILE",
                    null,
                    null,
                    deleteFileUndoData.getParentDirectoryId(),
                    deleteFileUndoData.getParentDirectoryPath(),
                    null,
                    toPersistedNodeSnapshot(deleteFileUndoData.getDeletedFileSnapshot()),
                    null,
                    toPersistedBlockSnapshots(deleteFileUndoData.getBlockChainSnapshotsSnapshot()),
                    null,
                    null,
                    null);
        }
        if (undoData instanceof DeleteDirectoryUndoData deleteDirectoryUndoData) {
            return new PersistedSystemState.UndoData(
                    "DELETE_DIRECTORY",
                    null,
                    deleteDirectoryUndoData.getDeletedDirectoryId(),
                    deleteDirectoryUndoData.getParentDirectoryId(),
                    deleteDirectoryUndoData.getParentDirectoryPath(),
                    null,
                    null,
                    toPersistedNodeSnapshots(deleteDirectoryUndoData.getSubtreeNodeSnapshotsSnapshot()),
                    toPersistedBlockSnapshots(deleteDirectoryUndoData.getAssociatedBlockSnapshotsSnapshot()),
                    null,
                    null,
                    null);
        }
        if (!(undoData instanceof UpdateRenameUndoData updateRenameUndoData)) {
            String undoType = (undoData == null) ? "null" : undoData.getClass().getSimpleName();
            throw new IllegalArgumentException("unsupported undoData type: " + undoType);
        }
        return new PersistedSystemState.UndoData(
                "UPDATE_RENAME",
                null,
                null,
                updateRenameUndoData.getParentDirectoryId(),
                updateRenameUndoData.getParentDirectoryPath(),
                null,
                null,
                null,
                null,
                updateRenameUndoData.getTargetNodeId(),
                updateRenameUndoData.getOldName(),
                updateRenameUndoData.getNewName());
    }

    private PersistedSystemState.NodeSnapshotData toPersistedNodeSnapshot(JournalNodeSnapshot snapshot) {
        return new PersistedSystemState.NodeSnapshotData(
                snapshot.getNodeId(),
                snapshot.getNodeType(),
                snapshot.getName(),
                snapshot.getOwnerUserId(),
                snapshot.getParentNodeId(),
                snapshot.getNodePath(),
                snapshot.isPublicReadable(),
                snapshot.getSizeInBlocks(),
                snapshot.getFirstBlockIndex(),
                snapshot.getColorId(),
                snapshot.isSystemFile());
    }

    private PersistedSystemState.NodeSnapshotData[] toPersistedNodeSnapshots(JournalNodeSnapshot[] snapshots) {
        PersistedSystemState.NodeSnapshotData[] persisted =
                new PersistedSystemState.NodeSnapshotData[snapshots.length];
        for (int i = 0; i < snapshots.length; i++) {
            persisted[i] = toPersistedNodeSnapshot(snapshots[i]);
        }
        return persisted;
    }

    private PersistedSystemState.BlockSnapshotData[] toPersistedBlockSnapshots(JournalBlockSnapshot[] snapshots) {
        PersistedSystemState.BlockSnapshotData[] persisted =
                new PersistedSystemState.BlockSnapshotData[snapshots.length];
        for (int i = 0; i < snapshots.length; i++) {
            persisted[i] = new PersistedSystemState.BlockSnapshotData(
                    snapshots[i].getIndex(),
                    snapshots[i].getOwnerFileId(),
                    snapshots[i].getNextBlockIndex(),
                    snapshots[i].isSystemReserved());
        }
        return persisted;
    }

    private UserStore rebuildUserStore(PersistedSystemState.UserData[] users) {
        if (users == null || users.length == 0) {
            throw new IllegalArgumentException("users cannot be null or empty");
        }
        UserStore userStore = new UserStore();
        for (PersistedSystemState.UserData user : users) {
            userStore.addUser(new User(user.userId(), user.username(), user.role()));
        }
        return userStore;
    }

    private FileSystemCatalog rebuildFileSystem(PersistedSystemState.FileSystemNodeData[] persistedNodes) {
        if (persistedNodes == null || persistedNodes.length == 0) {
            throw new IllegalArgumentException("fileSystemNodes cannot be null or empty");
        }

        PersistedSystemState.FileSystemNodeData rootData = null;
        Object[] nodes = new Object[persistedNodes.length];
        for (int i = 0; i < persistedNodes.length; i++) {
            PersistedSystemState.FileSystemNodeData nodeData = persistedNodes[i];
            if (nodeData.parentId() == null) {
                if (rootData != null) {
                    throw new IllegalArgumentException("filesystem must contain exactly one root node");
                }
                rootData = nodeData;
            }
            nodes[i] = createDetachedNode(nodeData);
        }
        if (rootData == null || rootData.type() != FsNodeType.DIRECTORY || !"/".equals(rootData.path())) {
            throw new IllegalArgumentException("filesystem must contain a valid root directory");
        }

        DirectoryNode root = requireDirectoryNode(findNodeById(nodes, rootData.id()));
        int attachedCount = 1;
        while (attachedCount < nodes.length) {
            boolean progressed = false;
            for (PersistedSystemState.FileSystemNodeData nodeData : persistedNodes) {
                if (nodeData.parentId() == null) {
                    continue;
                }
                FsNode node = findNodeById(nodes, nodeData.id());
                if (node.isAttached()) {
                    continue;
                }
                FsNode parent = findNodeById(nodes, nodeData.parentId());
                if (parent == null) {
                    throw new IllegalArgumentException("parent node not found: " + nodeData.parentId());
                }
                if (!(parent instanceof DirectoryNode directory) || !parent.isAttached()) {
                    continue;
                }
                directory.addChild(node);
                progressed = true;
                attachedCount++;
            }
            if (!progressed) {
                throw new IllegalArgumentException("filesystem hierarchy contains detached or cyclic nodes");
            }
        }

        FileSystemCatalog catalog = new FileSystemCatalog(root);
        for (PersistedSystemState.FileSystemNodeData nodeData : persistedNodes) {
            FsNode rebuiltNode = catalog.requireById(nodeData.id());
            if (!rebuiltNode.getPath().equals(nodeData.path())) {
                throw new IllegalArgumentException("persisted path does not match rebuilt path for node: " + nodeData.id());
            }
        }
        return catalog;
    }

    private FsNode createDetachedNode(PersistedSystemState.FileSystemNodeData nodeData) {
        AccessPermissions permissions = nodeData.publicReadable()
                ? AccessPermissions.publicReadAccess()
                : AccessPermissions.privateAccess();
        if (nodeData.parentId() == null) {
            return DirectoryNode.createRoot(nodeData.id(), nodeData.ownerUserId(), permissions);
        }
        if (nodeData.type() == FsNodeType.DIRECTORY) {
            return new DirectoryNode(nodeData.id(), nodeData.name(), nodeData.ownerUserId(), permissions);
        }
        return new FileNode(
                nodeData.id(),
                nodeData.name(),
                nodeData.ownerUserId(),
                permissions,
                nodeData.sizeInBlocks(),
                nodeData.firstBlockIndex(),
                nodeData.colorId(),
                nodeData.systemFile());
    }

    private SimulatedDisk rebuildDisk(
            PersistedSystemState.DiskBlockData[] blocks,
            int headBlock,
            ve.edu.unimet.so.project2.disk.DiskHeadDirection headDirection) {
        if (blocks == null || blocks.length == 0) {
            throw new IllegalArgumentException("diskBlocks cannot be null or empty");
        }
        SimulatedDisk disk = new SimulatedDisk(blocks.length, headBlock, headDirection);
        boolean[] seenIndexes = new boolean[blocks.length];
        for (PersistedSystemState.DiskBlockData block : blocks) {
            if (block.index() < 0 || block.index() >= blocks.length) {
                throw new IllegalArgumentException("disk block index out of range: " + block.index());
            }
            if (seenIndexes[block.index()]) {
                throw new IllegalArgumentException("duplicate disk block index: " + block.index());
            }
            seenIndexes[block.index()] = true;
            if (!block.free()) {
                disk.allocateBlock(block.index(), block.ownerFileId(), block.nextBlockIndex(), block.systemReserved());
            }
        }
        for (int i = 0; i < seenIndexes.length; i++) {
            if (!seenIndexes[i]) {
                throw new IllegalArgumentException("missing persisted disk block index: " + i);
            }
        }
        return disk;
    }

    private JournalManager rebuildJournal(PersistedSystemState.JournalEntryData[] entries) {
        JournalManager journalManager = new JournalManager();
        if (entries == null) {
            return journalManager;
        }
        for (PersistedSystemState.JournalEntryData entry : entries) {
            journalManager.restoreEntry(
                    entry.transactionId(),
                    entry.operationType(),
                    entry.targetPath(),
                    entry.status(),
                    toUndoData(entry.undoData()),
                    entry.targetNodeId(),
                    entry.ownerUserId(),
                    entry.description());
        }
        return journalManager;
    }

    private JournalUndoData toUndoData(PersistedSystemState.UndoData undoData) {
        if (undoData == null || undoData.kind() == null) {
            throw new IllegalArgumentException("journal undoData cannot be null");
        }
        return switch (undoData.kind()) {
            case "CREATE_FILE" -> new CreateFileUndoData(
                    undoData.createdNodeId(),
                    undoData.parentDirectoryId(),
                    undoData.parentDirectoryPath(),
                    undoData.allocatedBlockIndexes());
            case "CREATE_DIRECTORY" -> new CreateDirectoryUndoData(
                    undoData.createdNodeId(),
                    undoData.parentDirectoryId(),
                    undoData.parentDirectoryPath());
            case "DELETE_FILE" -> new DeleteFileUndoData(
                    toNodeSnapshot(undoData.deletedFileSnapshot()),
                    undoData.parentDirectoryId(),
                    undoData.parentDirectoryPath(),
                    toBlockSnapshots(undoData.blockSnapshots()));
            case "DELETE_DIRECTORY" -> new DeleteDirectoryUndoData(
                    undoData.deletedDirectoryId(),
                    undoData.parentDirectoryId(),
                    undoData.parentDirectoryPath(),
                    toNodeSnapshots(undoData.subtreeNodeSnapshots()),
                    toBlockSnapshots(undoData.blockSnapshots()));
            case "UPDATE_RENAME" -> new UpdateRenameUndoData(
                    undoData.targetNodeId(),
                    undoData.parentDirectoryId(),
                    undoData.parentDirectoryPath(),
                    undoData.oldName(),
                    undoData.newName());
            default -> throw new IllegalArgumentException("unsupported persisted undo kind: " + undoData.kind());
        };
    }

    private JournalNodeSnapshot toNodeSnapshot(PersistedSystemState.NodeSnapshotData nodeSnapshot) {
        if (nodeSnapshot == null) {
            throw new IllegalArgumentException("nodeSnapshot cannot be null");
        }
        return new JournalNodeSnapshot(
                nodeSnapshot.nodeId(),
                nodeSnapshot.nodeType(),
                nodeSnapshot.name(),
                nodeSnapshot.ownerUserId(),
                nodeSnapshot.parentNodeId(),
                nodeSnapshot.nodePath(),
                nodeSnapshot.publicReadable(),
                nodeSnapshot.sizeInBlocks(),
                nodeSnapshot.firstBlockIndex(),
                nodeSnapshot.colorId(),
                nodeSnapshot.systemFile());
    }

    private JournalNodeSnapshot[] toNodeSnapshots(PersistedSystemState.NodeSnapshotData[] snapshots) {
        if (snapshots == null) {
            throw new IllegalArgumentException("nodeSnapshots cannot be null");
        }
        JournalNodeSnapshot[] result = new JournalNodeSnapshot[snapshots.length];
        for (int i = 0; i < snapshots.length; i++) {
            result[i] = toNodeSnapshot(snapshots[i]);
        }
        return result;
    }

    private JournalBlockSnapshot[] toBlockSnapshots(PersistedSystemState.BlockSnapshotData[] snapshots) {
        if (snapshots == null) {
            throw new IllegalArgumentException("blockSnapshots cannot be null");
        }
        JournalBlockSnapshot[] result = new JournalBlockSnapshot[snapshots.length];
        for (int i = 0; i < snapshots.length; i++) {
            result[i] = new JournalBlockSnapshot(
                    snapshots[i].index(),
                    snapshots[i].ownerFileId(),
                    snapshots[i].nextBlockIndex(),
                    snapshots[i].systemReserved());
        }
        return result;
    }

    private long deriveNextNodeNumber(FileSystemCatalog catalog) {
        long maxNodeNumber = 0L;
        FsNode[] nodes = catalog.getAllNodesSnapshot();
        for (FsNode node : nodes) {
            maxNodeNumber = Math.max(maxNodeNumber, extractNumericSuffix(node.getId(), "NODE-"));
        }
        return Math.max(1L, maxNodeNumber + 1L);
    }

    private long deriveNextColorNumber(FileSystemCatalog catalog) {
        long maxColorNumber = 0L;
        FsNode[] nodes = catalog.getAllNodesSnapshot();
        for (FsNode node : nodes) {
            if (node instanceof FileNode file && file.getColorId() != null) {
                maxColorNumber = Math.max(maxColorNumber, extractNumericSuffix(file.getColorId(), "COLOR-"));
            }
        }
        return Math.max(1L, maxColorNumber + 1L);
    }

    private long extractNumericSuffix(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) {
            return 0L;
        }
        String suffix = value.substring(prefix.length());
        if (suffix.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private FsNode findNodeById(Object[] nodes, String nodeId) {
        for (Object object : nodes) {
            FsNode node = (FsNode) object;
            if (node.getId().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }

    private DirectoryNode requireDirectoryNode(FsNode node) {
        if (!(node instanceof DirectoryNode directory)) {
            String nodeType = (node == null) ? "null" : node.getType().name();
            throw new IllegalArgumentException("expected directory node but found: " + nodeType);
        }
        return directory;
    }
}
