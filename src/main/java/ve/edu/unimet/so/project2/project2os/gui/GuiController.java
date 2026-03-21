package ve.edu.unimet.so.project2.project2os.gui;

import ve.edu.unimet.so.project2.application.*;
import ve.edu.unimet.so.project2.coordinator.core.SimulationCoordinator;
import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.file.Path;

public class GuiController {

    private final SimulationCoordinator coordinator;
    private final MainFrame mainFrame;
    private final Timer refreshTimer;

    public GuiController(SimulationCoordinator coordinator, MainFrame mainFrame) {
        this.coordinator = coordinator;
        this.mainFrame = mainFrame;

        // Refresh at 150ms intervals
        this.refreshTimer = new Timer(150, e -> refreshFromSnapshot());
        
        setupListeners();
        refreshTimer.start();
        updatePlaybackLabel();
    }

    private void setupListeners() {
        mainFrame.getBtnAdmin().addActionListener(e -> runCoordinatorAction("Error cambiando a sesión Admin", () -> {
            coordinator.switchSession("admin");
            refreshFromSnapshot();
        }));
        mainFrame.getBtnUser().addActionListener(e -> runCoordinatorAction("Error cambiando a sesión Usuario", () -> {
            coordinator.switchSession("user-1");
            refreshFromSnapshot();
        }));
        
        mainFrame.getBtnPolicyChange().addActionListener(e -> {
            String policyStr = (String) mainFrame.getComboPolicy().getSelectedItem();
            if (policyStr != null) {
                runCoordinatorAction("Error cambiando política", () -> coordinator.changePolicy(DiskSchedulingPolicy.valueOf(policyStr)));
            }
        });

        mainFrame.getBtnSaveSystem().addActionListener(e -> handleSaveSystem());
        mainFrame.getBtnLoadSystem().addActionListener(e -> handleLoadSystem());
        mainFrame.getBtnLoadScenario().addActionListener(e -> handleLoadScenario());

        mainFrame.getBtnStats().addActionListener(e -> showStatisticsDialog());

        mainFrame.getBtnPlay().addActionListener(e -> {
            if (!refreshTimer.isRunning()) {
                refreshTimer.start();
            }
            updatePlaybackLabel();
        });

        mainFrame.getBtnPause().addActionListener(e -> {
            if (refreshTimer.isRunning()) {
                refreshTimer.stop();
            }
            updatePlaybackLabel();
        });

        mainFrame.getComboPlaybackSpeed().addActionListener(e -> {
            String selected = (String) mainFrame.getComboPlaybackSpeed().getSelectedItem();
            int delay = parseDelayMillis(selected, 2);
            runCoordinatorAction("Error actualizando velocidad de ejecución", () -> coordinator.changeExecutionDelay(delay));
            refreshTimer.setDelay(150);
            refreshTimer.setInitialDelay(150);
            updatePlaybackLabel(delay);
        });

        mainFrame.getBtnCreateFile().addActionListener(e -> {
            String parent = mainFrame.getFileSystemTreePanel().getSelectedNodePath();
            if (parent == null) {
                showError("Seleccione un directorio destino en el árbol.");
                return;
            }
            String name = JOptionPane.showInputDialog(mainFrame, "Nombre del archivo:");
            if (name == null || name.isBlank()) return;
            String sizeStr = JOptionPane.showInputDialog(mainFrame, "Tamaño en bloques:");
            if (sizeStr == null || sizeStr.isBlank()) return;
            
            try {
                int size = Integer.parseInt(sizeStr);
                coordinator.submitIntent(new CreateFileIntent(parent, name, size, false, false));
            } catch (Exception ex) {
                showError("Error: " + ex.getMessage());
            }
        });

        mainFrame.getBtnCreateDir().addActionListener(e -> {
            String parent = mainFrame.getFileSystemTreePanel().getSelectedNodePath();
            if (parent == null) {
                showError("Seleccione un directorio destino en el árbol.");
                return;
            }
            String name = JOptionPane.showInputDialog(mainFrame, "Nombre del directorio:");
            if (name == null || name.isBlank()) return;
            try {
                coordinator.submitIntent(new CreateDirectoryIntent(parent, name, false));
            } catch (Exception ex) {
                showError("Error: " + ex.getMessage());
            }
        });

        mainFrame.getBtnRead().addActionListener(e -> {
            String target = mainFrame.getFileSystemTreePanel().getSelectedNodePath();
            if (target == null) {
                showError("Seleccione un archivo en el árbol.");
                return;
            }
            try {
                coordinator.submitIntent(new ReadIntent(target));
            } catch (Exception ex) {
                showError("Error: " + ex.getMessage());
            }
        });

        mainFrame.getBtnRename().addActionListener(e -> {
            String target = mainFrame.getFileSystemTreePanel().getSelectedNodePath();
            if (target == null) {
                showError("Seleccione un nodo en el árbol.");
                return;
            }
            String name = JOptionPane.showInputDialog(mainFrame, "Nuevo nombre:");
            if (name == null || name.isBlank()) return;
            try {
                coordinator.submitIntent(new RenameIntent(target, name));
            } catch (Exception ex) {
                showError("Error: " + ex.getMessage());
            }
        });

        mainFrame.getBtnDelete().addActionListener(e -> {
            String target = mainFrame.getFileSystemTreePanel().getSelectedNodePath();
            if (target == null) {
                showError("Seleccione un nodo en el árbol.");
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(mainFrame, "¿Seguro que desea eliminar " + target + "?", "Confirmar Eliminación", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                try {
                    coordinator.submitIntent(new DeleteIntent(target));
                } catch (Exception ex) {
                    showError("Error: " + ex.getMessage());
                }
            }
        });

        mainFrame.getBtnSimularFallo().addActionListener(e -> {
            runCoordinatorAction("Error armando fallo simulado", () -> {
                coordinator.armSimulatedFailure();
                mainFrame.getLblSystemState().setText("Estado del Sistema: FALLO ARMADO");
                mainFrame.getLblSystemState().setForeground(DarkTheme.ACCENT_RED);
            });
        });

        mainFrame.getBtnRecovery().addActionListener(e -> {
            runCoordinatorAction("Error ejecutando recovery", () -> {
                coordinator.recoverPendingJournalEntries();
                mainFrame.getLblSystemState().setText("Estado del Sistema: Normal");
                mainFrame.getLblSystemState().setForeground(DarkTheme.FG_PRIMARY);
            });
        });
    }

    private void handleSaveSystem() {
        Path selected = chooseFileToSave("Guardar estado del sistema", "system-state.json");
        if (selected == null) {
            return;
        }
        try {
            coordinator.saveSystem(selected);
            refreshFromSnapshot();
            JOptionPane.showMessageDialog(mainFrame, "Sistema guardado en:\n" + selected, "Guardado", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showError("Error guardando sistema: " + ex.getMessage());
        }
    }

    private void handleLoadSystem() {
        Path selected = chooseFileToOpen("Cargar estado del sistema");
        if (selected == null) {
            return;
        }
        try {
            coordinator.loadSystem(selected);
            refreshFromSnapshot();
            JOptionPane.showMessageDialog(mainFrame, "Sistema cargado desde:\n" + selected, "Carga completada", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showError("Error cargando sistema: " + ex.getMessage());
        }
    }

    private void handleLoadScenario() {
        Path selected = chooseFileToOpen("Cargar escenario JSON");
        if (selected == null) {
            return;
        }
        try {
            coordinator.loadScenario(selected);
            refreshFromSnapshot();
            JOptionPane.showMessageDialog(mainFrame, "Escenario cargado desde:\n" + selected, "Escenario cargado", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showError("Error cargando escenario: " + ex.getMessage());
        }
    }

    private void showStatisticsDialog() {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (snapshot == null) {
            showError("No hay snapshot disponible todavía.");
            return;
        }

        SimulationSnapshot.DiskBlockSummary[] blocks = snapshot.getDiskBlocksSnapshot();
        int usedBlocks = 0;
        for (SimulationSnapshot.DiskBlockSummary block : blocks) {
            if (!block.isFree()) {
                usedBlocks++;
            }
        }

        int totalBlocks = blocks.length;
        int freeBlocks = totalBlocks - usedBlocks;
        int completedProcesses = snapshot.getTerminatedProcessesSnapshot().length;
        long simulationTick = estimateSimulationTick(snapshot);

        String message = "Bloques usados: " + usedBlocks + " / " + totalBlocks + "\n"
                + "Bloques libres: " + freeBlocks + "\n"
                + "Seek total: " + snapshot.getTotalSeekDistance() + "\n"
                + "Procesos completados: " + completedProcesses + "\n"
                + "Tiempo de simulación (tick): " + simulationTick;

        JOptionPane.showMessageDialog(mainFrame, message, "Estadísticas del Sistema", JOptionPane.INFORMATION_MESSAGE);
    }

    private long estimateSimulationTick(SimulationSnapshot snapshot) {
        long maxTick = 0L;
        SimulationSnapshot.EventLogEntrySummary[] entries = snapshot.getEventLogEntriesSnapshot();
        for (SimulationSnapshot.EventLogEntrySummary entry : entries) {
            if (entry.getTick() > maxTick) {
                maxTick = entry.getTick();
            }
        }
        return maxTick;
    }

    private Path chooseFileToOpen(String title) {
        JFileChooser chooser = buildJsonFileChooser(title);
        int result = chooser.showOpenDialog(mainFrame);
        if (result != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return null;
        }
        return chooser.getSelectedFile().toPath();
    }

    private Path chooseFileToSave(String title, String defaultName) {
        JFileChooser chooser = buildJsonFileChooser(title);
        chooser.setSelectedFile(new java.io.File(defaultName));
        int result = chooser.showSaveDialog(mainFrame);
        if (result != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return null;
        }

        Path selectedPath = chooser.getSelectedFile().toPath();
        String fileName = selectedPath.getFileName() != null ? selectedPath.getFileName().toString().toLowerCase() : "";
        if (!fileName.endsWith(".json")) {
            selectedPath = selectedPath.resolveSibling(selectedPath.getFileName() + ".json");
        }
        return selectedPath;
    }

    private JFileChooser buildJsonFileChooser(String title) {
        JFileChooser chooser = new JFileChooser(System.getProperty("user.dir"));
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter("Archivos JSON (*.json)", "json"));
        chooser.setAcceptAllFileFilterUsed(true);
        return chooser;
    }

    private int parseDelayMillis(String label, int fallback) {
        if (label == null || label.isBlank()) {
            return fallback;
        }
        String normalized = label.trim().toLowerCase();
        if (normalized.contains("instant")) {
            return 2;
        } else if (normalized.contains("rápido") || normalized.contains("rapido")) {
            return 100;
        } else if (normalized.contains("medio")) {
            return 500;
        } else if (normalized.contains("lento")) {
            return 1000;
        } else if (normalized.contains("paso")) {
            return 3000;
        }
        return fallback;
    }

    private void updatePlaybackLabel() {
        updatePlaybackLabel((int) coordinator.getExecutionDelayMillis());
    }

    private void updatePlaybackLabel(int executionDelay) {
        String mode = refreshTimer.isRunning() ? "Reproduciendo" : "Pausado";
        String speedText = executionDelay <= 2 ? "Instantáneo" : executionDelay + " ms";
        if (executionDelay >= 1000) {
            speedText = (executionDelay / 1000) + " Seg";
        }
        mainFrame.getLblCycle().setText(mode + " (" + speedText + ")");
    }

    private void runCoordinatorAction(String errorPrefix, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            showError(errorPrefix + ": " + ex.getMessage());
        }
    }

    private void updateCrudButtonsByRole(SimulationSnapshot snapshot) {
        boolean adminSession = false;
        if (snapshot != null && snapshot.getSessionSummary() != null && snapshot.getSessionSummary().getCurrentRole() != null) {
            adminSession = "ADMIN".equalsIgnoreCase(snapshot.getSessionSummary().getCurrentRole().name());
        }

        mainFrame.getBtnCreateFile().setEnabled(adminSession);
        mainFrame.getBtnCreateDir().setEnabled(adminSession);
        mainFrame.getBtnRename().setEnabled(adminSession);
        mainFrame.getBtnDelete().setEnabled(adminSession);
        // Lectura y estadísticas siguen habilitadas para usuarios regulares.
        mainFrame.getBtnRead().setEnabled(true);
        mainFrame.getBtnStats().setEnabled(true);
    }

    private void refreshFromSnapshot() {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (snapshot == null) return;

        updateCrudButtonsByRole(snapshot);

        mainFrame.getFileSystemTreePanel().updateFromSnapshot(snapshot.getFileSystemNodesSnapshot());
        mainFrame.getDiskVisualizationPanel().updateFromSnapshot(snapshot.getDiskBlocksSnapshot(), snapshot.getFileSystemNodesSnapshot());
        mainFrame.getJournalPanel().updateFromSnapshot(snapshot.getJournalEntriesSnapshot());
        mainFrame.getProcessQueuePanel().updateFromSnapshot(
                snapshot.getRunningProcessSnapshot(),
                snapshot.getReadyProcessesSnapshot(),
                snapshot.getBlockedProcessesSnapshot(),
                snapshot.getTerminatedProcessesSnapshot()
        );
        mainFrame.getEventLogPanel().updateFromSnapshot(snapshot.getEventLogEntriesSnapshot());

        mainFrame.getLblStatusLeft().setText("Cabeza: Block " + snapshot.getHeadBlock() + " (" + snapshot.getHeadDirection() + ") | Política: " + snapshot.getPolicy());
        
        if (snapshot.getSessionSummary() != null) {
             mainFrame.getLblStatusCenter().setText("Sesión: " + snapshot.getSessionSummary().getCurrentUserId() + " / " + snapshot.getSessionSummary().getCurrentRole());
        }

        mainFrame.getLblStatusRight().setText("Seek total: " + snapshot.getTotalSeekDistance());
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(mainFrame, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
