package ve.edu.unimet.so.project2.scheduler;

import org.junit.jupiter.api.Test;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.filesystem.FsNodeType;
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.process.IoRequest;
import ve.edu.unimet.so.project2.process.ProcessControlBlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DiskSchedulerTest {

    private final DiskScheduler scheduler = new DiskScheduler();

    @Test
    void fifoUsesArrivalOrder() {
        SimulatedDisk disk = new SimulatedDisk(200, 50, DiskHeadDirection.UP);
        ProcessControlBlock older = createFileProcess("P1", "R1", 20, 1L, IoOperationType.READ, LockType.SHARED);
        ProcessControlBlock newer = createFileProcess("P2", "R2", 60, 2L, IoOperationType.READ, LockType.SHARED);

        DiskScheduleDecision decision = scheduler.selectNext(
                DiskSchedulingPolicy.FIFO,
                disk.getHead(),
                new ProcessControlBlock[] { newer, older },
                disk.getTotalBlocks());

        assertNotNull(decision);
        assertEquals("P1", decision.getSelectedProcessId());
        assertEquals(30, decision.getTraveledDistance());
    }

    @Test
    void sstfUsesDistanceThenArrivalThenRequestId() {
        SimulatedDisk disk = new SimulatedDisk(200, 50, DiskHeadDirection.UP);
        ProcessControlBlock farther = createFileProcess("P1", "R2", 60, 5L, IoOperationType.READ, LockType.SHARED);
        ProcessControlBlock closerWithEarlierArrival = createFileProcess("P2", "R3", 48, 2L, IoOperationType.READ, LockType.SHARED);
        ProcessControlBlock sameDistanceLaterArrival = createFileProcess("P3", "R1", 52, 7L, IoOperationType.READ, LockType.SHARED);

        DiskScheduleDecision decision = scheduler.selectNext(
                DiskSchedulingPolicy.SSTF,
                disk.getHead(),
                new ProcessControlBlock[] { farther, sameDistanceLaterArrival, closerWithEarlierArrival },
                disk.getTotalBlocks());

        assertNotNull(decision);
        assertEquals("P2", decision.getSelectedProcessId());
        assertEquals(2, decision.getTraveledDistance());
    }

    @Test
    void scanReversesDirectionUsingRealEdgeWhenNeeded() {
        SimulatedDisk disk = new SimulatedDisk(200, 50, DiskHeadDirection.UP);
        ProcessControlBlock lowerBlock = createFileProcess("P1", "R1", 20, 1L, IoOperationType.READ, LockType.SHARED);
        ProcessControlBlock anotherLowerBlock = createFileProcess("P2", "R2", 10, 2L, IoOperationType.READ, LockType.SHARED);

        DiskScheduleDecision decision = scheduler.selectNext(
                DiskSchedulingPolicy.SCAN,
                disk.getHead(),
                new ProcessControlBlock[] { lowerBlock, anotherLowerBlock },
                disk.getTotalBlocks());

        assertNotNull(decision);
        assertEquals("P1", decision.getSelectedProcessId());
        assertEquals(DiskHeadDirection.DOWN, decision.getResultingDirection());
        assertEquals((199 - 50) + (199 - 20), decision.getTraveledDistance());
    }

    @Test
    void cScanWrapsAndKeepsDirection() {
        SimulatedDisk disk = new SimulatedDisk(200, 50, DiskHeadDirection.UP);
        ProcessControlBlock wrappedTarget = createFileProcess("P1", "R1", 20, 1L, IoOperationType.READ, LockType.SHARED);
        ProcessControlBlock laterWrappedTarget = createFileProcess("P2", "R2", 40, 2L, IoOperationType.READ, LockType.SHARED);

        DiskScheduleDecision decision = scheduler.selectNext(
                DiskSchedulingPolicy.C_SCAN,
                disk.getHead(),
                new ProcessControlBlock[] { wrappedTarget, laterWrappedTarget },
                disk.getTotalBlocks());

        assertNotNull(decision);
        assertEquals("P1", decision.getSelectedProcessId());
        assertEquals(DiskHeadDirection.UP, decision.getResultingDirection());
        assertEquals((199 - 50) + 199 + 20, decision.getTraveledDistance());
    }

    private ProcessControlBlock createFileProcess(
            String processId,
            String requestId,
            int targetBlock,
            long arrivalOrder,
            IoOperationType operationType,
            LockType lockType) {
        IoRequest request = new IoRequest(
                requestId,
                processId,
                operationType,
                FsNodeType.FILE,
                "/file-" + processId,
                "file-" + processId,
                targetBlock,
                0,
                "user-1",
                arrivalOrder);
        ProcessControlBlock pcb = new ProcessControlBlock(
                processId,
                "user-1",
                request,
                FsNodeType.FILE,
                "file-" + processId,
                "/file-" + processId,
                targetBlock,
                lockType,
                arrivalOrder);
        pcb.markReady(arrivalOrder + 1);
        return pcb;
    }
}
