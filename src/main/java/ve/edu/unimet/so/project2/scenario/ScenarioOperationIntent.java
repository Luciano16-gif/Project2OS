package ve.edu.unimet.so.project2.scenario;

import ve.edu.unimet.so.project2.application.ApplicationOperationIntent;
import ve.edu.unimet.so.project2.application.DeleteIntent;
import ve.edu.unimet.so.project2.application.ReadIntent;
import ve.edu.unimet.so.project2.application.RenameIntent;
import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.DirectoryNode;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.process.IoOperationType;

public final class ScenarioOperationIntent implements ApplicationOperationIntent {

    private final int startBlock;
    private final IoOperationType operationType;
    private final int sequence;

    public ScenarioOperationIntent(int startBlock, IoOperationType operationType, int sequence) {
        if (startBlock < 0) {
            throw new IllegalArgumentException("startBlock cannot be negative");
        }
        if (operationType == null) {
            throw new IllegalArgumentException("operationType cannot be null");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        this.startBlock = startBlock;
        this.operationType = operationType;
        this.sequence = sequence;
    }

    public ApplicationOperationIntent resolve(
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        FileNode targetFile = requireTargetFile(applicationState, disk);
        return switch (operationType) {
            case READ -> new ReadIntent(targetFile.getPath());
            case DELETE -> new DeleteIntent(targetFile.getPath());
            case UPDATE -> new RenameIntent(targetFile.getPath(), buildScenarioRename(targetFile));
            default -> throw new IllegalArgumentException(
                "external scenarios do not support " + operationType + " requests");
        };
    }

    public int getStartBlock() {
        return startBlock;
    }

    public IoOperationType getOperationType() {
        return operationType;
    }

    public String describeTargetPath(
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        FileNode targetFile = findTargetFile(applicationState, disk);
        if (targetFile != null) {
            return targetFile.getPath();
        }
        return "/scenario-pos-" + startBlock;
    }

    private FileNode requireTargetFile(
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        FileNode targetFile = findTargetFile(applicationState, disk);
        if (targetFile == null) {
            throw new IllegalArgumentException("scenario request references unknown block position: " + startBlock);
        }
        return targetFile;
    }

    private FileNode findTargetFile(
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        requireApplicationState(applicationState);
        requireDisk(disk);
        if (!disk.isValidIndex(startBlock)) {
            return null;
        }

        String ownerFileId = disk.getBlock(startBlock).getOwnerFileId();
        if (ownerFileId == null) {
            return null;
        }

        FsNode node = applicationState.getFileSystemCatalog().findById(ownerFileId);
        return (node instanceof FileNode file) ? file : null;
    }

    private String buildScenarioRename(FileNode targetFile) {
        DirectoryNode parentDirectory = targetFile.getParent();
        if (parentDirectory == null) {
            throw new IllegalArgumentException("scenario target file is detached: " + targetFile.getId());
        }

        String baseName = targetFile.getName() + "-updated-" + sequence;
        if (!parentDirectory.hasChildNamed(baseName)) {
            return baseName;
        }

        int suffix = 1;
        while (parentDirectory.hasChildNamed(baseName + "-" + suffix)) {
            suffix++;
        }
        return baseName + "-" + suffix;
    }

    private void requireApplicationState(SimulationApplicationState applicationState) {
        if (applicationState == null) {
            throw new IllegalArgumentException("applicationState cannot be null");
        }
    }

    private void requireDisk(SimulatedDisk disk) {
        if (disk == null) {
            throw new IllegalArgumentException("disk cannot be null");
        }
    }
}
