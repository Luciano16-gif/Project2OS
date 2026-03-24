package ve.edu.unimet.so.project2.project2os.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ve.edu.unimet.so.project2.coordinator.core.SimulationCoordinator;
import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.locking.LockTable;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiControllerSmokeTest {

    private SimulationCoordinator coordinator;
    private MainFrame mainFrame;

    @BeforeEach
    void setUp() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "GUI smoke tests require a graphical environment");

        coordinator = new SimulationCoordinator(
                new SimulatedDisk(100, 10, DiskHeadDirection.UP),
                new LockTable(),
                new JournalManager(),
                DiskSchedulingPolicy.FIFO);
        coordinator.start();

        onEdt(() -> {
            mainFrame = new MainFrame();
            new GuiController(coordinator, mainFrame);
            mainFrame.setVisible(false);
        });
    }

    @AfterEach
    void tearDown() {
        if (mainFrame != null) {
            onEdt(() -> mainFrame.dispose());
        }
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    @Test
    void playbackButtonsToggleManualMode() {
        onEdt(() -> mainFrame.getBtnPause().doClick());
        waitForCondition(() -> coordinator.isStepModeEnabled(), "pause should enable manual mode");

        onEdt(() -> mainFrame.getBtnPlay().doClick());
        waitForCondition(() -> !coordinator.isStepModeEnabled(), "play should disable manual mode");

        onEdt(() -> mainFrame.getBtnStep().doClick());
        waitForCondition(coordinator::isStepModeEnabled, "step should force manual mode");
    }

    @Test
    void speedSelectorUpdatesCoordinatorDelay() {
        onEdt(() -> mainFrame.getComboPlaybackSpeed().setSelectedItem("Rápido (100ms)"));
        waitForCondition(() -> coordinator.getExecutionDelayMillis() == 100L, "fast preset should set 100ms delay");

        onEdt(() -> mainFrame.getComboPlaybackSpeed().setSelectedItem("Lento (1 Seg)"));
        waitForCondition(() -> coordinator.getExecutionDelayMillis() == 1000L, "slow preset should set 1000ms delay");

        onEdt(() -> mainFrame.getComboPlaybackSpeed().setSelectedItem("Instantáneo"));
        waitForCondition(() -> coordinator.getExecutionDelayMillis() == 0L, "instant preset should set 0ms delay");
    }

    @Test
    void policyAndSessionButtonsDispatchCoordinatorCommands() {
        onEdt(() -> {
            mainFrame.getComboPolicy().setSelectedItem("C_SCAN");
            mainFrame.getBtnPolicyChange().doClick();
        });
        waitForCondition(() -> {
            SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
            return snapshot != null && snapshot.getPolicy() == DiskSchedulingPolicy.C_SCAN;
        }, "policy button should update coordinator policy");

        onEdt(() -> mainFrame.getBtnAdmin().doClick());
        waitForCondition(() -> {
            SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
            return snapshot != null
                    && snapshot.getSessionSummary() != null
                    && snapshot.getSessionSummary().getCurrentRole() != null
                    && "ADMIN".equals(snapshot.getSessionSummary().getCurrentRole().name());
        }, "admin button should switch to ADMIN session");

        onEdt(() -> mainFrame.getBtnUser().doClick());
        waitForCondition(() -> {
            SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
            return snapshot != null
                    && snapshot.getSessionSummary() != null
                    && snapshot.getSessionSummary().getCurrentRole() != null
                    && "USER".equals(snapshot.getSessionSummary().getCurrentRole().name());
        }, "user button should switch to USER session");

        SimulationSnapshot latest = coordinator.getLatestSnapshot();
        assertEquals(DiskSchedulingPolicy.C_SCAN, latest.getPolicy());
    }

    private void onEdt(Runnable runnable) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                runnable.run();
                return;
            }
            SwingUtilities.invokeAndWait(runnable);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("EDT execution interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError("EDT execution failed", exception.getCause());
        }
    }

    private void waitForCondition(BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("wait interrupted", exception);
            }
        }
        throw new AssertionError("Timeout: " + message);
    }
}
