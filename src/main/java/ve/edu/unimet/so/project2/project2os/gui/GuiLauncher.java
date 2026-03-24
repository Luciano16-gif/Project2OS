package ve.edu.unimet.so.project2.project2os.gui;

import ve.edu.unimet.so.project2.coordinator.core.SimulationCoordinator;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.locking.LockTable;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

public class GuiLauncher {

    public static void launch() {
        DarkTheme.applyGlobalTheme();

        // 1. Initialize backend components
        SimulatedDisk disk = new SimulatedDisk(100, 0, DiskHeadDirection.UP);
        LockTable lockTable = new LockTable();
        JournalManager journalManager = new JournalManager();
        
        SimulationCoordinator coordinator = new SimulationCoordinator(
                disk, lockTable, journalManager, DiskSchedulingPolicy.FIFO);
                
        // 2. Start coordinator
        coordinator.start();

        // 3. Initialize GUI components
        MainFrame mainFrame = new MainFrame();
        @SuppressWarnings("unused")
        GuiController controller = new GuiController(coordinator, mainFrame);
        
        // 4. Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(coordinator::shutdown));

        // 5. Show window
        mainFrame.setVisible(true);
    }
}
