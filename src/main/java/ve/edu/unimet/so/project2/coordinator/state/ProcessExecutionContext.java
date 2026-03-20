package ve.edu.unimet.so.project2.coordinator.state;

import ve.edu.unimet.so.project2.application.ApplicationOperationIntent;
import ve.edu.unimet.so.project2.coordinator.core.PreparedOperationCommand;
import ve.edu.unimet.so.project2.process.ProcessControlBlock;

public final class ProcessExecutionContext {

    private ProcessControlBlock process;
    private PreparedOperationCommand command;
    private ApplicationOperationIntent deferredIntent;
    private String deferredActorUserId;
    private String transactionId;

    public ProcessExecutionContext(ProcessControlBlock process, PreparedOperationCommand command) {
        if (process == null) {
            throw new IllegalArgumentException("process cannot be null");
        }
        if (command == null) {
            throw new IllegalArgumentException("command cannot be null");
        }
        this.process = process;
        this.command = command;
        this.deferredIntent = null;
        this.deferredActorUserId = null;
        this.transactionId = null;
    }

    public ProcessExecutionContext(ProcessControlBlock process, ApplicationOperationIntent deferredIntent) {
        this(process, deferredIntent, null);
    }

    public ProcessExecutionContext(
            ProcessControlBlock process,
            ApplicationOperationIntent deferredIntent,
            String deferredActorUserId) {
        if (process == null) {
            throw new IllegalArgumentException("process cannot be null");
        }
        if (deferredIntent == null) {
            throw new IllegalArgumentException("deferredIntent cannot be null");
        }
        this.process = process;
        this.command = null;
        this.deferredIntent = deferredIntent;
        this.deferredActorUserId = deferredActorUserId;
        this.transactionId = null;
    }

    public ProcessControlBlock getProcess() {
        return process;
    }

    public PreparedOperationCommand getCommand() {
        if (command == null) {
            throw new IllegalStateException("process command has not been resolved yet");
        }
        return command;
    }

    public boolean hasDeferredIntent() {
        return deferredIntent != null;
    }

    public ApplicationOperationIntent getDeferredIntent() {
        if (deferredIntent == null) {
            throw new IllegalStateException("process does not have a deferred intent");
        }
        return deferredIntent;
    }

    public String getDeferredActorUserId() {
        return deferredActorUserId;
    }

    public void resolveDeferredIntent(ProcessControlBlock process, PreparedOperationCommand command) {
        if (process == null) {
            throw new IllegalArgumentException("process cannot be null");
        }
        if (command == null) {
            throw new IllegalArgumentException("command cannot be null");
        }
        this.process = process;
        this.command = command;
        this.deferredIntent = null;
        this.deferredActorUserId = null;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
}
