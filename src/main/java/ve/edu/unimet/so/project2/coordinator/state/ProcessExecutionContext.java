package ve.edu.unimet.so.project2.coordinator.state;

import ve.edu.unimet.so.project2.coordinator.core.PreparedOperationCommand;
import ve.edu.unimet.so.project2.process.ProcessControlBlock;

public final class ProcessExecutionContext {

    private final ProcessControlBlock process;
    private final PreparedOperationCommand command;
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
        this.transactionId = null;
    }

    public ProcessControlBlock getProcess() {
        return process;
    }

    public PreparedOperationCommand getCommand() {
        return command;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
}
