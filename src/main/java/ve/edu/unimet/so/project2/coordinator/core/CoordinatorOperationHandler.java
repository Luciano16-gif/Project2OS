package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.coordinator.channel.DiskServiceResult;
import ve.edu.unimet.so.project2.process.ProcessControlBlock;

@FunctionalInterface
public interface CoordinatorOperationHandler {

    /**
     * Applies the logical operation in the coordinator thread after disk service
     * finishes. The handler must not perform scheduling, journal transitions or
     * lock management.
     */
    OperationApplyResult apply(
            PreparedOperationCommand command,
            ProcessControlBlock process,
            DiskServiceResult diskServiceResult);
}
