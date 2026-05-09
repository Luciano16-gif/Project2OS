package ve.edu.unimet.so.project2.coordinator.core;

import java.util.concurrent.CountDownLatch;
import ve.edu.unimet.so.project2.coordinator.command.CoordinatorCommand;

final class CoordinatorCommandCompletion implements CoordinatorCommand {

    private final CoordinatorCommand delegate;
    private final CountDownLatch completionLatch;
    private RuntimeException failure;

    CoordinatorCommandCompletion(CoordinatorCommand delegate) {
        this.delegate = delegate;
        this.completionLatch = new CountDownLatch(1);
        this.failure = null;
    }

    @Override
    public void execute() {
        try {
            delegate.execute();
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            completionLatch.countDown();
        }
    }

    void awaitCompletion() {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    completionLatch.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
