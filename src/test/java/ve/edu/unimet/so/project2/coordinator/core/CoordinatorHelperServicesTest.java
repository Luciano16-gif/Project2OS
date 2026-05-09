package ve.edu.unimet.so.project2.coordinator.core;

import org.junit.jupiter.api.Test;
import ve.edu.unimet.so.project2.application.CreateDirectoryIntent;
import ve.edu.unimet.so.project2.application.CreateFileIntent;
import ve.edu.unimet.so.project2.application.DeleteIntent;
import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.application.SwitchSessionIntent;
import ve.edu.unimet.so.project2.disk.DiskBlock;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.AccessPermissions;
import ve.edu.unimet.so.project2.filesystem.DirectoryNode;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.scenario.ScenarioOperationIntent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoordinatorHelperServicesTest {

    @Test
    void diskAllocationConsistencyServiceHydratesMissingCatalogAllocation() {
        SimulationApplicationState applicationState = SimulationApplicationState.createDefault();
        DirectoryNode home = applicationState.getFileSystemCatalog().requireDirectoryByPath("/home-user-1");
        FileNode file = new FileNode(
                "NODE-100",
                "hydrated.bin",
                "user-1",
                AccessPermissions.privateAccess(),
                2,
                3,
                "COLOR-1",
                false);
        applicationState.getFileSystemCatalog().addNode(home, file);

        SimulatedDisk disk = new SimulatedDisk(12, 0, DiskHeadDirection.UP);

        new DiskAllocationConsistencyService().synchronizeDiskWithCatalog(applicationState, disk);

        assertEquals("NODE-100", disk.getBlock(3).getOwnerFileId());
        assertEquals(4, disk.getBlock(3).getNextBlockIndex());
        assertEquals("NODE-100", disk.getBlock(4).getOwnerFileId());
        assertEquals(DiskBlock.NO_NEXT_BLOCK, disk.getBlock(4).getNextBlockIndex());
        assertEquals(2, disk.countOccupiedBlocks());
    }

    @Test
    void diskAllocationConsistencyServiceRejectsOrphanedOccupiedBlocks() {
        SimulationApplicationState applicationState = SimulationApplicationState.createDefault();
        SimulatedDisk disk = new SimulatedDisk(8, 0, DiskHeadDirection.UP);
        disk.allocateBlock(6, "orphan-file", DiskBlock.NO_NEXT_BLOCK, false);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DiskAllocationConsistencyService().validateDiskMatchesCatalog(applicationState, disk));

        assertEquals("disk contains occupied block without filesystem owner: 6", exception.getMessage());
    }

    @Test
    void helperServicesDescribeAndStageScenarioRequestsConsistently() {
        SimulationApplicationState applicationState = SimulationApplicationState.createDefault();
        DirectoryNode home = applicationState.getFileSystemCatalog().requireDirectoryByPath("/home-user-1");
        FileNode existingFile = new FileNode(
                "NODE-200",
                "scenario.bin",
                "user-1",
                AccessPermissions.privateAccess(),
                1,
                2,
                "COLOR-2",
                false);
        applicationState.getFileSystemCatalog().addNode(home, existingFile);

        SimulatedDisk disk = new SimulatedDisk(10, 0, DiskHeadDirection.UP);
        disk.allocateBlock(2, "NODE-200", DiskBlock.NO_NEXT_BLOCK, false);

        ApplicationIntentDescriptor descriptor = new ApplicationIntentDescriptor();
        assertEquals("/home-user-1/report.txt", descriptor.describeTargetPath(
                new CreateFileIntent("/home-user-1", "report.txt", 1, false, false),
                applicationState,
                disk));
        assertEquals("/home-user-1/archive", descriptor.describeTargetPath(
                new CreateDirectoryIntent("/home-user-1", "archive", false),
                applicationState,
                disk));
        assertEquals(IoOperationType.DELETE, descriptor.inferOperationType(new DeleteIntent("/home-user-1/report.txt")));
        assertEquals(LockType.EXCLUSIVE, descriptor.inferRequiredLockType(new DeleteIntent("/home-user-1/report.txt")));
        assertEquals("user-2", descriptor.inferOwnerUserId(new SwitchSessionIntent("user-2"), applicationState));

        ScenarioReadyRegistration[] registrations = new ScenarioProcessStager().stageReadyProcesses(
                new ScenarioOperationIntent[] {
                    new ScenarioOperationIntent(2, IoOperationType.READ, 1),
                    new ScenarioOperationIntent(5, IoOperationType.CREATE, 2, "created.bin", 2)
                },
                applicationState,
                disk);

        assertEquals("SCN-REQ-1", registrations[0].process().getRequest().getRequestId());
        assertEquals("/home-user-1/scenario.bin", registrations[0].process().getTargetPath());
        assertEquals(LockType.SHARED, registrations[0].process().getRequiredLockType());
        assertEquals("SCN-REQ-2", registrations[1].process().getRequest().getRequestId());
        assertEquals("/created.bin", registrations[1].process().getTargetPath());
        assertNull(registrations[1].process().getRequiredLockType());
        assertEquals(2, registrations.length);
    }
}
