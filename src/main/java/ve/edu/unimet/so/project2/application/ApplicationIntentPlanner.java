package ve.edu.unimet.so.project2.application;

import ve.edu.unimet.so.project2.coordinator.core.OperationApplyResult;
import ve.edu.unimet.so.project2.coordinator.core.PreparedJournalData;
import ve.edu.unimet.so.project2.coordinator.core.PreparedOperationCommand;
import ve.edu.unimet.so.project2.disk.DiskBlock;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.AccessPermissions;
import ve.edu.unimet.so.project2.filesystem.DirectoryNode;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FileSystemCatalog;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.filesystem.FsNodeType;
import ve.edu.unimet.so.project2.journal.undo.CreateDirectoryUndoData;
import ve.edu.unimet.so.project2.journal.undo.CreateFileUndoData;
import ve.edu.unimet.so.project2.journal.undo.DeleteDirectoryUndoData;
import ve.edu.unimet.so.project2.journal.undo.DeleteFileUndoData;
import ve.edu.unimet.so.project2.journal.undo.JournalBlockSnapshot;
import ve.edu.unimet.so.project2.journal.undo.JournalNodeSnapshot;
import ve.edu.unimet.so.project2.journal.undo.UpdateRenameUndoData;
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.session.User;

public final class ApplicationIntentPlanner {

    private final SimulationApplicationState applicationState;
    private final SimulatedDisk disk;
    private final PermissionService permissionService;

    public ApplicationIntentPlanner(
            SimulationApplicationState applicationState,
            SimulatedDisk disk,
            PermissionService permissionService) {
        if (applicationState == null) {
            throw new IllegalArgumentException("applicationState cannot be null");
        }
        if (disk == null) {
            throw new IllegalArgumentException("disk cannot be null");
        }
        if (permissionService == null) {
            throw new IllegalArgumentException("permissionService cannot be null");
        }
        this.applicationState = applicationState;
        this.disk = disk;
        this.permissionService = permissionService;
    }

    public PreparedOperationCommand plan(
            ApplicationOperationIntent intent,
            String requestId,
            String processId) {
        if (intent instanceof CreateFileIntent createFileIntent) {
            return planCreateFile(createFileIntent, requestId, processId);
        }
        if (intent instanceof CreateDirectoryIntent createDirectoryIntent) {
            return planCreateDirectory(createDirectoryIntent, requestId, processId);
        }
        if (intent instanceof ReadIntent readIntent) {
            return planRead(readIntent, requestId, processId);
        }
        if (intent instanceof RenameIntent renameIntent) {
            return planRename(renameIntent, requestId, processId);
        }
        if (intent instanceof DeleteIntent deleteIntent) {
            return planDelete(deleteIntent, requestId, processId);
        }
        throw new IllegalArgumentException("unsupported application intent: " + intent.getClass().getSimpleName());
    }

    private PreparedOperationCommand planCreateFile(
            CreateFileIntent intent,
            String requestId,
            String processId) {
        FileSystemCatalog catalog = applicationState.getFileSystemCatalog();
        DirectoryNode parentDirectory = catalog.requireDirectoryByPath(intent.getParentDirectoryPath());
        User actor = applicationState.getSessionContext().getCurrentUser();
        permissionService.ensureCanCreate(actor, parentDirectory, intent.isSystemFile());
        validateNodeName(intent.getFileName(), "fileName");

        String targetPath = FileSystemCatalog.buildChildPath(parentDirectory.getPath(), intent.getFileName());
        if (catalog.findByPath(targetPath) != null) {
            throw new IllegalArgumentException("a node already exists at path: " + targetPath);
        }
        if (!disk.hasFreeBlocks(intent.getSizeInBlocks())) {
            throw new IllegalArgumentException("not enough free disk blocks for CREATE file");
        }

        String nodeId = applicationState.nextNodeId();
        String colorId = applicationState.nextColorId();
        int[] allocatedBlocks = selectFreeBlocks(intent.getSizeInBlocks());
        applicationState.reserveBlockIndexes(allocatedBlocks);
        PreparedJournalData journalData = new PreparedJournalData(
                new CreateFileUndoData(nodeId, parentDirectory.getId(), parentDirectory.getPath(), allocatedBlocks),
                nodeId,
                actor.getUserId(),
                "create file " + targetPath);

        return new PreparedOperationCommand(
                requestId,
                processId,
                actor.getUserId(),
                IoOperationType.CREATE,
                FsNodeType.FILE,
                targetPath,
                nodeId,
                allocatedBlocks[0],
                intent.getSizeInBlocks(),
                null,
                journalData,
                (command, process, diskResult) -> applyCreateFile(
                        nodeId,
                        intent,
                        targetPath,
                        parentDirectory.getId(),
                        colorId,
                        allocatedBlocks,
                        actor.getUserId()));
    }

    private OperationApplyResult applyCreateFile(
            String nodeId,
            CreateFileIntent intent,
            String targetPath,
            String parentDirectoryId,
            String colorId,
            int[] allocatedBlocks,
            String ownerUserId) {
        FileSystemCatalog catalog = applicationState.getFileSystemCatalog();
        if (catalog.findById(nodeId) != null || catalog.findByPath(targetPath) != null) {
            return OperationApplyResult.failed("target file already exists");
        }
        if (!areBlocksStillFree(allocatedBlocks)) {
            return OperationApplyResult.failed("reserved disk blocks are no longer available");
        }

        DirectoryNode parentDirectory = catalog.requireDirectoryById(parentDirectoryId);
        validateNodeName(intent.getFileName(), "fileName");

        int allocatedCount = 0;
        try {
            for (int i = 0; i < allocatedBlocks.length; i++) {
                int nextBlock = (i == allocatedBlocks.length - 1) ? DiskBlock.NO_NEXT_BLOCK : allocatedBlocks[i + 1];
                disk.allocateBlock(allocatedBlocks[i], nodeId, nextBlock, intent.isSystemFile());
                allocatedCount++;
            }

            FileNode file = new FileNode(
                    nodeId,
                    intent.getFileName(),
                    ownerUserId,
                    intent.isPublicReadable() ? AccessPermissions.publicReadAccess() : AccessPermissions.privateAccess(),
                    intent.getSizeInBlocks(),
                    allocatedBlocks[0],
                    colorId,
                    intent.isSystemFile());
            catalog.addNode(parentDirectory, file);
            return OperationApplyResult.success();
        } catch (RuntimeException exception) {
            rollbackAllocatedBlocks(allocatedBlocks, allocatedCount);
            return OperationApplyResult.failed(exception.getMessage());
        }
    }

    private PreparedOperationCommand planCreateDirectory(
            CreateDirectoryIntent intent,
            String requestId,
            String processId) {
        FileSystemCatalog catalog = applicationState.getFileSystemCatalog();
        DirectoryNode parentDirectory = catalog.requireDirectoryByPath(intent.getParentDirectoryPath());
        User actor = applicationState.getSessionContext().getCurrentUser();
        permissionService.ensureCanCreate(actor, parentDirectory, false);
        validateNodeName(intent.getDirectoryName(), "directoryName");

        String targetPath = FileSystemCatalog.buildChildPath(parentDirectory.getPath(), intent.getDirectoryName());
        if (catalog.findByPath(targetPath) != null) {
            throw new IllegalArgumentException("a node already exists at path: " + targetPath);
        }

        String nodeId = applicationState.nextNodeId();
        PreparedJournalData journalData = new PreparedJournalData(
                new CreateDirectoryUndoData(nodeId, parentDirectory.getId(), parentDirectory.getPath()),
                nodeId,
                actor.getUserId(),
                "create directory " + targetPath);

        return new PreparedOperationCommand(
                requestId,
                processId,
                actor.getUserId(),
                IoOperationType.CREATE,
                FsNodeType.DIRECTORY,
                targetPath,
                nodeId,
                disk.getHead().getCurrentBlock(),
                0,
                null,
                journalData,
                (command, process, diskResult) -> applyCreateDirectory(
                        nodeId,
                        intent,
                        targetPath,
                        parentDirectory.getId(),
                        actor.getUserId()));
    }

    private OperationApplyResult applyCreateDirectory(
            String nodeId,
            CreateDirectoryIntent intent,
            String targetPath,
            String parentDirectoryId,
            String ownerUserId) {
        FileSystemCatalog catalog = applicationState.getFileSystemCatalog();
        if (catalog.findById(nodeId) != null || catalog.findByPath(targetPath) != null) {
            return OperationApplyResult.failed("target directory already exists");
        }

        DirectoryNode parentDirectory = catalog.requireDirectoryById(parentDirectoryId);
        DirectoryNode directory = new DirectoryNode(
                nodeId,
                intent.getDirectoryName(),
                ownerUserId,
                intent.isPublicReadable() ? AccessPermissions.publicReadAccess() : AccessPermissions.privateAccess());
        catalog.addNode(parentDirectory, directory);
        return OperationApplyResult.success();
    }

    private PreparedOperationCommand planRead(
            ReadIntent intent,
            String requestId,
            String processId) {
        FileNode file = applicationState.getFileSystemCatalog().requireFileByPath(intent.getTargetPath());
        User actor = applicationState.getSessionContext().getCurrentUser();
        permissionService.ensureCanRead(actor, file);

        return new PreparedOperationCommand(
                requestId,
                processId,
                actor.getUserId(),
                IoOperationType.READ,
                FsNodeType.FILE,
                file.getPath(),
                file.getId(),
                file.getFirstBlockIndex(),
                0,
                LockType.SHARED,
                null,
                (command, process, diskResult) -> OperationApplyResult.success());
    }

    private PreparedOperationCommand planRename(
            RenameIntent intent,
            String requestId,
            String processId) {
        FileSystemCatalog catalog = applicationState.getFileSystemCatalog();
        FsNode targetNode = catalog.requireByPath(intent.getTargetPath());
        User actor = applicationState.getSessionContext().getCurrentUser();
        permissionService.ensureCanModify(actor, targetNode);
        if (targetNode.isRoot()) {
            throw new IllegalArgumentException("root cannot be renamed");
        }

        DirectoryNode parent = targetNode.getParent();
        PreparedJournalData journalData = new PreparedJournalData(
                new UpdateRenameUndoData(
                        targetNode.getId(),
                        parent.getId(),
                        parent.getPath(),
                        targetNode.getName(),
                        intent.getNewName()),
                targetNode.getId(),
                actor.getUserId(),
                "rename " + targetNode.getPath());

        return new PreparedOperationCommand(
                requestId,
                processId,
                actor.getUserId(),
                IoOperationType.UPDATE,
                targetNode.getType(),
                targetNode.getPath(),
                targetNode.getId(),
                resolveTargetBlock(targetNode),
                0,
                targetNode instanceof FileNode ? LockType.EXCLUSIVE : null,
                journalData,
                (command, process, diskResult) -> applyRename(targetNode.getId(), intent.getNewName()));
    }

    private OperationApplyResult applyRename(String nodeId, String newName) {
        FsNode targetNode = applicationState.getFileSystemCatalog().requireById(nodeId);
        targetNode.rename(newName);
        return OperationApplyResult.success();
    }

    private PreparedOperationCommand planDelete(
            DeleteIntent intent,
            String requestId,
            String processId) {
        FileSystemCatalog catalog = applicationState.getFileSystemCatalog();
        FsNode targetNode = catalog.requireByPath(intent.getTargetPath());
        User actor = applicationState.getSessionContext().getCurrentUser();
        permissionService.ensureCanModify(actor, targetNode);
        if (targetNode.isRoot()) {
            throw new IllegalArgumentException("root cannot be deleted");
        }
        ensureCanDeleteTargetSubtree(actor, targetNode);

        PreparedJournalData journalData = buildDeleteJournalData(actor.getUserId(), targetNode);
        return new PreparedOperationCommand(
                requestId,
                processId,
                actor.getUserId(),
                IoOperationType.DELETE,
                targetNode.getType(),
                targetNode.getPath(),
                targetNode.getId(),
                resolveTargetBlock(targetNode),
                0,
                targetNode instanceof FileNode ? LockType.EXCLUSIVE : null,
                journalData,
                (command, process, diskResult) -> applyDelete(targetNode.getId()));
    }

    private PreparedJournalData buildDeleteJournalData(String actorUserId, FsNode targetNode) {
        if (targetNode instanceof FileNode file) {
            JournalNodeSnapshot nodeSnapshot = buildNodeSnapshot(file);
            JournalBlockSnapshot[] blockSnapshots = buildFileBlockSnapshots(file);
            return new PreparedJournalData(
                    new DeleteFileUndoData(
                            nodeSnapshot,
                            file.getParent().getId(),
                            file.getParent().getPath(),
                            blockSnapshots),
                    file.getId(),
                    actorUserId,
                    "delete file " + file.getPath());
        }

        FsNode[] subtreeNodes = applicationState.getFileSystemCatalog().getSubtreeSnapshot(targetNode);
        JournalNodeSnapshot[] nodeSnapshots = buildNodeSnapshots(subtreeNodes);
        JournalBlockSnapshot[] associatedBlocks = buildAssociatedBlockSnapshots(subtreeNodes);
        return new PreparedJournalData(
                new DeleteDirectoryUndoData(
                        targetNode.getId(),
                        targetNode.getParent().getId(),
                        targetNode.getParent().getPath(),
                        nodeSnapshots,
                        associatedBlocks),
                targetNode.getId(),
                actorUserId,
                    "delete directory " + targetNode.getPath());
    }

    private void ensureCanDeleteTargetSubtree(User actor, FsNode targetNode) {
        FsNode[] subtreeNodes = applicationState.getFileSystemCatalog().getSubtreeSnapshot(targetNode);
        for (FsNode node : subtreeNodes) {
            permissionService.ensureCanModify(actor, node);
        }
    }

    private OperationApplyResult applyDelete(String nodeId) {
        FileSystemCatalog catalog = applicationState.getFileSystemCatalog();
        FsNode targetNode = catalog.requireById(nodeId);

        if (targetNode instanceof FileNode file) {
            freeFileBlocks(file);
            catalog.removeNode(file);
            return OperationApplyResult.success();
        }

        FsNode[] subtree = catalog.getSubtreeSnapshot(targetNode);
        for (FsNode node : subtree) {
            if (node instanceof FileNode file) {
                freeFileBlocks(file);
            }
        }
        catalog.removeNode(targetNode);
        return OperationApplyResult.success();
    }

    private int resolveTargetBlock(FsNode targetNode) {
        if (targetNode instanceof FileNode file) {
            return file.getFirstBlockIndex();
        }
        return disk.getHead().getCurrentBlock();
    }

    private int[] selectFreeBlocks(int requiredBlocks) {
        int[] result = new int[requiredBlocks];
        boolean[] used = new boolean[disk.getTotalBlocks()];
        int searchIndex = 0;
        for (int i = 0; i < requiredBlocks; i++) {
            int blockIndex = findNextUnusedFreeBlock(searchIndex, used);
            if (blockIndex == SimulatedDisk.NO_FREE_BLOCK) {
                throw new IllegalStateException("unable to allocate requested block chain");
            }
            result[i] = blockIndex;
            used[blockIndex] = true;
            searchIndex = (blockIndex + 1) % disk.getTotalBlocks();
        }
        return result;
    }

    private int findNextUnusedFreeBlock(int startIndex, boolean[] used) {
        for (int offset = 0; offset < disk.getTotalBlocks(); offset++) {
            int candidate = (startIndex + offset) % disk.getTotalBlocks();
            if (!used[candidate]
                    && disk.getBlock(candidate).isFree()
                    && !applicationState.isReservedBlock(candidate)) {
                return candidate;
            }
        }
        return SimulatedDisk.NO_FREE_BLOCK;
    }

    private boolean areBlocksStillFree(int[] allocatedBlocks) {
        for (int blockIndex : allocatedBlocks) {
            if (!disk.getBlock(blockIndex).isFree()) {
                return false;
            }
        }
        return true;
    }

    private void rollbackAllocatedBlocks(int[] allocatedBlocks, int allocatedCount) {
        for (int i = 0; i < allocatedCount; i++) {
            disk.freeBlock(allocatedBlocks[i]);
        }
    }

    private void freeFileBlocks(FileNode file) {
        JournalBlockSnapshot[] snapshots = buildFileBlockSnapshots(file);
        for (JournalBlockSnapshot snapshot : snapshots) {
            disk.freeBlock(snapshot.getIndex());
        }
    }

    private JournalNodeSnapshot buildNodeSnapshot(FsNode node) {
        if (node instanceof FileNode file) {
            return new JournalNodeSnapshot(
                    file.getId(),
                    file.getType(),
                    file.getName(),
                    file.getOwnerUserId(),
                    file.getParent().getId(),
                    file.getPath(),
                    file.getPermissions().isPublicReadable(),
                    file.getSizeInBlocks(),
                    file.getFirstBlockIndex(),
                    file.getColorId(),
                    file.isSystemFile());
        }

        DirectoryNode directory = (DirectoryNode) node;
        return new JournalNodeSnapshot(
                directory.getId(),
                directory.getType(),
                directory.getName(),
                directory.getOwnerUserId(),
                directory.isRoot() ? null : directory.getParent().getId(),
                directory.getPath(),
                directory.getPermissions().isPublicReadable(),
                0,
                JournalNodeSnapshot.NO_BLOCK,
                null,
                false);
    }

    private JournalNodeSnapshot[] buildNodeSnapshots(FsNode[] nodes) {
        JournalNodeSnapshot[] snapshots = new JournalNodeSnapshot[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            snapshots[i] = buildNodeSnapshot(nodes[i]);
        }
        return snapshots;
    }

    private JournalBlockSnapshot[] buildAssociatedBlockSnapshots(FsNode[] nodes) {
        int totalBlocks = 0;
        for (FsNode node : nodes) {
            if (node instanceof FileNode file) {
                totalBlocks += file.getSizeInBlocks();
            }
        }

        JournalBlockSnapshot[] snapshots = new JournalBlockSnapshot[totalBlocks];
        int index = 0;
        for (FsNode node : nodes) {
            if (node instanceof FileNode file) {
                JournalBlockSnapshot[] fileSnapshots = buildFileBlockSnapshots(file);
                for (JournalBlockSnapshot snapshot : fileSnapshots) {
                    snapshots[index++] = snapshot;
                }
            }
        }
        return snapshots;
    }

    private JournalBlockSnapshot[] buildFileBlockSnapshots(FileNode file) {
        JournalBlockSnapshot[] snapshots = new JournalBlockSnapshot[file.getSizeInBlocks()];
        int currentIndex = file.getFirstBlockIndex();
        for (int i = 0; i < snapshots.length; i++) {
            DiskBlock block = disk.getBlock(currentIndex);
            snapshots[i] = new JournalBlockSnapshot(
                    block.getIndex(),
                    block.getOwnerFileId(),
                    block.getNextBlockIndex(),
                    block.isSystemReserved());
            currentIndex = block.getNextBlockIndex();
        }
        return snapshots;
    }

    private void validateNodeName(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        if (value.contains("/")) {
            throw new IllegalArgumentException(fieldName + " cannot contain /");
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be . or ..");
        }
    }
}
