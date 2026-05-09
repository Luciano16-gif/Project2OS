package ve.edu.unimet.so.project2.scenario;

import ve.edu.unimet.so.project2.application.ApplicationOperationIntent;
import ve.edu.unimet.so.project2.application.CreateFileIntent;
import ve.edu.unimet.so.project2.application.DeleteIntent;
import ve.edu.unimet.so.project2.application.ReadIntent;
import ve.edu.unimet.so.project2.application.RenameIntent;
import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.DirectoryNode;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.process.IoOperationType;

public record ScenarioOperationIntent(
        int startBlock,
        IoOperationType operationType,
        int sequence,
        String createName,
        Integer createBlocks) implements ApplicationOperationIntent {

    public ScenarioOperationIntent(int startBlock, IoOperationType operationType, int sequence) {
        this(startBlock, operationType, sequence, null, null);
    }

    public ScenarioOperationIntent {
        if (startBlock < 0) {
            throw new IllegalArgumentException("startBlock cannot be negative");
        }
        if (operationType == null) {
            throw new IllegalArgumentException("operationType cannot be null");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }

    public ApplicationOperationIntent resolve(
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        return switch (operationType) {
            case CREATE -> new CreateFileIntent("/", requireCreateName(), requireCreateBlocks(), false, false);
            case READ -> {
                FileNode targetFile = requireTargetFile(applicationState, disk);
                yield new ReadIntent(targetFile.getPath());
            }
            case DELETE -> {
                FileNode targetFile = requireTargetFile(applicationState, disk);
                yield new DeleteIntent(targetFile.getPath());
            }
            case UPDATE -> {
                FileNode targetFile = requireTargetFile(applicationState, disk);
                yield new RenameIntent(targetFile.getPath(), buildScenarioRename(targetFile));
            }
        };
    }

    public int getStartBlock() { return startBlock; }
    public IoOperationType getOperationType() { return operationType; }
    public int getRequestedSizeInBlocks() { return operationType == IoOperationType.CREATE ? requireCreateBlocks() : 0; }

    public String describeTargetPath(
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        if (operationType == IoOperationType.CREATE) {
            return "/" + requireCreateName();
        }
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
        requireNonNull(applicationState, "applicationState");
        requireNonNull(disk, "disk");
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

    private String requireCreateName() {
        if (createName == null || createName.isBlank()) {
            throw new IllegalArgumentException("scenario CREATE request requires a non-blank name");
        }
        return createName;
    }

    private int requireCreateBlocks() {
        if (createBlocks == null || createBlocks <= 0) {
            throw new IllegalArgumentException("scenario CREATE request requires positive blocks");
        }
        return createBlocks;
    }

    private static void requireNonNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
    }
}
