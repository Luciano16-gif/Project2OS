package ve.edu.unimet.so.project2.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.nio.file.Path;
import java.util.Map;
import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.disk.DiskBlock;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.AccessPermissions;
import ve.edu.unimet.so.project2.filesystem.DirectoryNode;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FileSystemCatalog;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.session.Role;
import ve.edu.unimet.so.project2.session.SessionContext;
import ve.edu.unimet.so.project2.session.User;
import ve.edu.unimet.so.project2.session.UserStore;

public final class ScenarioLoader {

    private final ObjectMapper objectMapper;

    public ScenarioLoader() {
        this.objectMapper = new ObjectMapper();
    }

    public LoadedScenarioState load(
            Path path,
            int totalBlocks,
            DiskHeadDirection headDirection) {
        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }
        if (totalBlocks <= 0) {
            throw new IllegalArgumentException("totalBlocks must be positive");
        }
        if (headDirection == null) {
            throw new IllegalArgumentException("headDirection cannot be null");
        }

        ExternalScenarioDocument document;
        try {
            document = objectMapper.readValue(path.toFile(), ExternalScenarioDocument.class);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load scenario: " + path, exception);
        }

        validateDocument(document, totalBlocks);
        SimulatedDisk disk = new SimulatedDisk(totalBlocks, document.initialHead(), headDirection);
        SimulationApplicationState applicationState = createScenarioApplicationState();
        loadSystemFiles(document.systemFiles(), applicationState, disk);
        validateRequestTargets(document.requests(), disk);
        ScenarioOperationIntent[] scenarioIntents = buildScenarioIntents(document.requests());
        return new LoadedScenarioState(disk, applicationState, scenarioIntents);
    }

    private void validateDocument(ExternalScenarioDocument document, int totalBlocks) {
        if (document == null) {
            throw new IllegalArgumentException("scenario document cannot be null");
        }
        if (document.initialHead() == null || document.initialHead() < 0 || document.initialHead() >= totalBlocks) {
            throw new IllegalArgumentException("scenario initial_head is out of range");
        }
        if (document.requests() == null) {
            throw new IllegalArgumentException("scenario requests cannot be null");
        }
        if (document.systemFiles() == null) {
            throw new IllegalArgumentException("scenario system_files cannot be null");
        }
        for (ExternalScenarioDocument.RequestData request : document.requests()) {
            if (request == null || request.pos() == null || request.pos() < 0 || request.pos() >= totalBlocks) {
                throw new IllegalArgumentException("scenario request pos is out of range");
            }
            parseOperation(request.op());
        }
        for (Map.Entry<String, ExternalScenarioDocument.SystemFileData> entry : document.systemFiles().entrySet()) {
            int startBlock = parseStartBlock(entry.getKey(), totalBlocks);
            ExternalScenarioDocument.SystemFileData systemFileData = entry.getValue();
            if (systemFileData == null) {
                throw new IllegalArgumentException("scenario system file entry cannot be null");
            }
            if (systemFileData.name() == null || systemFileData.name().isBlank()) {
                throw new IllegalArgumentException("scenario system file name cannot be blank");
            }
            if (systemFileData.name().contains("/") || ".".equals(systemFileData.name()) || "..".equals(systemFileData.name())) {
                throw new IllegalArgumentException("scenario system file name is invalid: " + systemFileData.name());
            }
            if (systemFileData.blocks() == null || systemFileData.blocks() <= 0) {
                throw new IllegalArgumentException("scenario system file blocks must be positive");
            }
            if (startBlock >= totalBlocks) {
                throw new IllegalArgumentException("scenario system file startBlock is out of range");
            }
        }
    }

    private SimulationApplicationState createScenarioApplicationState() {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        User user2 = new User("user-2", "User Two", Role.USER);

        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);
        userStore.addUser(user2);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        return new SimulationApplicationState(
                new FileSystemCatalog(root),
                userStore,
                new SessionContext(admin),
                1L,
                1L);
    }

    private void loadSystemFiles(
            LinkedHashMap<String, ExternalScenarioDocument.SystemFileData> systemFiles,
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        boolean[] reservedFirstBlocks = new boolean[disk.getTotalBlocks()];
        for (String startBlockKey : systemFiles.keySet()) {
            reservedFirstBlocks[parseStartBlock(startBlockKey, disk.getTotalBlocks())] = true;
        }

        for (Map.Entry<String, ExternalScenarioDocument.SystemFileData> entry : systemFiles.entrySet()) {
            int startBlock = parseStartBlock(entry.getKey(), disk.getTotalBlocks());
            ExternalScenarioDocument.SystemFileData systemFileData = entry.getValue();
            int[] blockChain = buildScenarioChain(
                    startBlock,
                    systemFileData.blocks(),
                    disk,
                    reservedFirstBlocks);
            allocateChain(disk, applicationState.nextNodeId(), blockChain, true);
            FileNode file = new FileNode(
                    disk.getBlock(startBlock).getOwnerFileId(),
                    systemFileData.name(),
                    applicationState.getSessionContext().getCurrentUserId(),
                    AccessPermissions.privateAccess(),
                    systemFileData.blocks(),
                    startBlock,
                    applicationState.nextColorId(),
                    true);
            applicationState.getFileSystemCatalog().addNode(
                    applicationState.getFileSystemCatalog().getRoot(),
                    file);
        }
    }

    private ScenarioOperationIntent[] buildScenarioIntents(ExternalScenarioDocument.RequestData[] requests) {
        ScenarioOperationIntent[] intents = new ScenarioOperationIntent[requests.length];
        for (int i = 0; i < requests.length; i++) {
            intents[i] = new ScenarioOperationIntent(
                    requests[i].pos(),
                    parseOperation(requests[i].op()),
                    i + 1);
        }
        return intents;
    }

    private void validateRequestTargets(ExternalScenarioDocument.RequestData[] requests, SimulatedDisk disk) {
        for (ExternalScenarioDocument.RequestData request : requests) {
            DiskBlock targetBlock = disk.getBlock(request.pos());
            if (targetBlock.isFree() || targetBlock.getOwnerFileId() == null || targetBlock.getOwnerFileId().isBlank()) {
                throw new IllegalArgumentException(
                        "scenario request pos does not belong to any reconstructed file block: " + request.pos());
            }
        }
    }

    private int[] buildScenarioChain(
            int startBlock,
            int blocks,
            SimulatedDisk disk,
            boolean[] reservedFirstBlocks) {
        if (!disk.getBlock(startBlock).isFree()) {
            throw new IllegalArgumentException("scenario system file startBlock is already occupied: " + startBlock);
        }
        int[] chain = new int[blocks];
        boolean[] used = new boolean[disk.getTotalBlocks()];
        chain[0] = startBlock;
        used[startBlock] = true;

        int searchStart = (startBlock + 1) % disk.getTotalBlocks();
        for (int i = 1; i < blocks; i++) {
            int blockIndex = findNextFreeScenarioBlock(searchStart, used, reservedFirstBlocks, disk);
            if (blockIndex == SimulatedDisk.NO_FREE_BLOCK) {
                throw new IllegalArgumentException("scenario system file chain cannot be constructed");
            }
            chain[i] = blockIndex;
            used[blockIndex] = true;
            searchStart = (blockIndex + 1) % disk.getTotalBlocks();
        }
        return chain;
    }

    private int findNextFreeScenarioBlock(
            int startIndex,
            boolean[] used,
            boolean[] reservedFirstBlocks,
            SimulatedDisk disk) {
        for (int offset = 0; offset < disk.getTotalBlocks(); offset++) {
            int candidate = (startIndex + offset) % disk.getTotalBlocks();
            if (!used[candidate]
                    && !reservedFirstBlocks[candidate]
                    && disk.getBlock(candidate).isFree()) {
                return candidate;
            }
        }
        return SimulatedDisk.NO_FREE_BLOCK;
    }

    private void allocateChain(SimulatedDisk disk, String ownerFileId, int[] chain, boolean systemReserved) {
        for (int i = 0; i < chain.length; i++) {
            int nextBlock = (i == chain.length - 1) ? DiskBlock.NO_NEXT_BLOCK : chain[i + 1];
            disk.allocateBlock(chain[i], ownerFileId, nextBlock, systemReserved);
        }
    }

    private int parseStartBlock(String key, int totalBlocks) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("scenario system file key cannot be blank");
        }
        try {
            int parsed = Integer.parseInt(key);
            if (parsed < 0 || parsed >= totalBlocks) {
                throw new IllegalArgumentException("scenario system file startBlock is out of range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("scenario system file key must be numeric: " + key);
        }
    }

    private IoOperationType parseOperation(String rawOperation) {
        if (rawOperation == null || rawOperation.isBlank()) {
            throw new IllegalArgumentException("scenario request op cannot be blank");
        }
        String normalized = rawOperation.trim().toUpperCase();
        return switch (normalized) {
            case "READ", "R" -> IoOperationType.READ;
            case "UPDATE", "U" -> IoOperationType.UPDATE;
            case "DELETE", "D" -> IoOperationType.DELETE;
            default -> throw new IllegalArgumentException("unsupported scenario operation: " + rawOperation);
        };
    }
}
