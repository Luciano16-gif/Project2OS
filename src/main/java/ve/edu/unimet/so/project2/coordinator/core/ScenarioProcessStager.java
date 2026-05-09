package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.FsNodeType;
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.process.IoRequest;
import ve.edu.unimet.so.project2.process.ProcessControlBlock;
import ve.edu.unimet.so.project2.scenario.ScenarioOperationIntent;

final class ScenarioProcessStager {

    ScenarioReadyRegistration[] stageReadyProcesses(
            ScenarioOperationIntent[] intents,
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        requireApplicationState(applicationState);
        requireDisk(disk);
        requireScenarioIntents(intents, disk);

        ScenarioReadyRegistration[] registrations = new ScenarioReadyRegistration[intents.length];
        long arrivalOrder = 0L;
        long creationTick = 0L;
        for (int i = 0; i < intents.length; i++) {
            registrations[i] = new ScenarioReadyRegistration(
                    createReadyScenarioProcess(
                            intents[i],
                            "SCN-REQ-" + (i + 1),
                            "SCN-PROC-" + (i + 1),
                            applicationState,
                            disk,
                            arrivalOrder++,
                            creationTick),
                    intents[i]);
            creationTick += 2L;
        }
        return registrations;
    }

    private void requireScenarioIntents(ScenarioOperationIntent[] intents, SimulatedDisk disk) {
        if (intents == null) {
            throw new IllegalArgumentException("intents cannot be null");
        }
        for (int i = 0; i < intents.length; i++) {
            ScenarioOperationIntent intent = intents[i];
            if (intent == null) {
                throw new IllegalArgumentException("intent at index " + i + " cannot be null");
            }
            if (!disk.isValidIndex(intent.getStartBlock())) {
                throw new IllegalArgumentException("targetBlock is out of disk range: " + intent.getStartBlock());
            }
        }
    }

    private ProcessControlBlock createReadyScenarioProcess(
            ScenarioOperationIntent intent,
            String requestId,
            String processId,
            SimulationApplicationState applicationState,
            SimulatedDisk disk,
            long arrivalOrder,
            long creationTick) {
        String ownerUserId = applicationState.getSessionContext().getCurrentUserId();
        String placeholderTargetPath = intent.describeTargetPath(applicationState, disk);
        String placeholderTargetNodeId = "SCENARIO-POS-" + intent.getStartBlock();
        LockType requiredLockType = switch (intent.getOperationType()) {
            case READ -> LockType.SHARED;
            case CREATE -> null;
            case UPDATE, DELETE -> LockType.EXCLUSIVE;
        };

        IoRequest request = new IoRequest(
                requestId,
                processId,
                intent.getOperationType(),
                FsNodeType.FILE,
                placeholderTargetPath,
                placeholderTargetNodeId,
                intent.getStartBlock(),
                intent.getRequestedSizeInBlocks(),
                ownerUserId,
                arrivalOrder);
        ProcessControlBlock process = new ProcessControlBlock(
                processId,
                ownerUserId,
                request,
                FsNodeType.FILE,
                placeholderTargetNodeId,
                placeholderTargetPath,
                intent.getStartBlock(),
                requiredLockType,
                creationTick);
        process.markReady(creationTick + 1L);
        return process;
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
