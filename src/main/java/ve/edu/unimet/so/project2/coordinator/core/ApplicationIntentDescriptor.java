package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.application.ApplicationOperationIntent;
import ve.edu.unimet.so.project2.application.CreateDirectoryIntent;
import ve.edu.unimet.so.project2.application.CreateFileIntent;
import ve.edu.unimet.so.project2.application.DeleteIntent;
import ve.edu.unimet.so.project2.application.ReadIntent;
import ve.edu.unimet.so.project2.application.RenameIntent;
import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.application.SwitchSessionIntent;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.FileSystemCatalog;
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.scenario.ScenarioOperationIntent;

final class ApplicationIntentDescriptor {

    String describeTargetPath(
            ApplicationOperationIntent intent,
            SimulationApplicationState applicationState,
            SimulatedDisk disk) {
        requireIntent(intent);
        if (intent instanceof ScenarioOperationIntent scenarioIntent) {
            return scenarioIntent.describeTargetPath(applicationState, disk);
        }
        if (intent instanceof CreateFileIntent createFileIntent) {
            return FileSystemCatalog.buildChildPath(
                    createFileIntent.getParentDirectoryPath(),
                    createFileIntent.getFileName());
        }
        if (intent instanceof CreateDirectoryIntent createDirectoryIntent) {
            return FileSystemCatalog.buildChildPath(
                    createDirectoryIntent.getParentDirectoryPath(),
                    createDirectoryIntent.getDirectoryName());
        }
        if (intent instanceof ReadIntent readIntent) {
            return readIntent.getTargetPath();
        }
        if (intent instanceof RenameIntent renameIntent) {
            return renameIntent.getTargetPath();
        }
        if (intent instanceof DeleteIntent deleteIntent) {
            return deleteIntent.getTargetPath();
        }
        return "/";
    }

    IoOperationType inferOperationType(ApplicationOperationIntent intent) {
        requireIntent(intent);
        if (intent instanceof CreateFileIntent || intent instanceof CreateDirectoryIntent) {
            return IoOperationType.CREATE;
        }
        if (intent instanceof ReadIntent) {
            return IoOperationType.READ;
        }
        if (intent instanceof RenameIntent) {
            return IoOperationType.UPDATE;
        }
        if (intent instanceof DeleteIntent) {
            return IoOperationType.DELETE;
        }
        if (intent instanceof ScenarioOperationIntent scenarioIntent) {
            return scenarioIntent.getOperationType();
        }
        return null;
    }

    LockType inferRequiredLockType(ApplicationOperationIntent intent) {
        IoOperationType operationType = inferOperationType(intent);
        if (operationType == null || operationType == IoOperationType.CREATE) {
            return null;
        }
        if (operationType == IoOperationType.READ) {
            return LockType.SHARED;
        }
        return LockType.EXCLUSIVE;
    }

    String inferOwnerUserId(
            ApplicationOperationIntent intent,
            SimulationApplicationState applicationState) {
        requireIntent(intent);
        requireApplicationState(applicationState);
        if (intent instanceof SwitchSessionIntent switchSessionIntent) {
            return switchSessionIntent.getTargetUserId();
        }
        return applicationState.getSessionContext().getCurrentUserId();
    }

    private void requireIntent(ApplicationOperationIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("intent cannot be null");
        }
    }

    private void requireApplicationState(SimulationApplicationState applicationState) {
        if (applicationState == null) {
            throw new IllegalArgumentException("applicationState cannot be null");
        }
    }
}
