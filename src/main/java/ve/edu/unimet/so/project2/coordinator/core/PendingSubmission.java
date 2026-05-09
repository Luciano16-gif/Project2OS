package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.application.ApplicationOperationIntent;

record PendingSubmission(
        PreparedOperationCommand command,
        ApplicationOperationIntent intent,
        String requestId,
        String processId) {

    static PendingSubmission forCommand(PreparedOperationCommand command) {
        return new PendingSubmission(command, null, null, null);
    }

    static PendingSubmission forIntent(
            ApplicationOperationIntent intent,
            String requestId,
            String processId) {
        return new PendingSubmission(null, intent, requestId, processId);
    }
}
