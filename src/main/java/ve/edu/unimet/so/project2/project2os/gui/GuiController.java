package ve.edu.unimet.so.project2.project2os.gui;

import ve.edu.unimet.so.project2.application.*;
import ve.edu.unimet.so.project2.coordinator.core.SimulationCoordinator;
import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class GuiController {

    private final SimulationCoordinator coordinator;
    private final MainFrame mainFrame;
    private final Timer refreshTimer;
    private boolean recoveryPopupShown;
    private final Set<String> notifiedIntentFailures;

    private static final String ARMED_TOOLTIP = "El próximo commit journaled disparará un fallo simulado";

    public GuiController(SimulationCoordinator coordinator, MainFrame mainFrame) {
        this.coordinator = coordinator;
        this.mainFrame = mainFrame;
        this.recoveryPopupShown = false;
        this.notifiedIntentFailures = new HashSet<>();

        // GUI policy: start in paused/manual mode.
        runCoordinatorAction("Error configurando modo inicial", () -> coordinator.setStepModeEnabled(true));

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
                runCoordinatorAction("Error cambiando política",
                        () -> coordinator.changePolicy(DiskSchedulingPolicy.valueOf(policyStr)));
            }
        });

        mainFrame.getBtnSaveSystem().addActionListener(e -> handleSaveSystem());
        mainFrame.getBtnLoadSystem().addActionListener(e -> handleLoadSystem());
        mainFrame.getBtnLoadScenario().addActionListener(e -> handleLoadScenario());
        mainFrame.getBtnAdvanced().addActionListener(e -> handleAdvancedOptions());

        mainFrame.getBtnStats().addActionListener(e -> showStatisticsDialog());

        mainFrame.getBtnPlay().addActionListener(e -> {
            runCoordinatorAction("Error reanudando simulación", () -> coordinator.setStepModeEnabled(false));
            if (!refreshTimer.isRunning()) {
                refreshTimer.start();
            }
            updatePlaybackLabel();
        });

        mainFrame.getBtnPause().addActionListener(e -> {
            runCoordinatorAction("Error pausando simulación", () -> coordinator.setStepModeEnabled(true));
            if (!refreshTimer.isRunning()) {
                refreshTimer.start();
            }
            updatePlaybackLabel();
        });

        mainFrame.getBtnStep().addActionListener(e -> {
            runCoordinatorAction("Error avanzando simulación por paso", () -> coordinator.stepSimulationOnce());
            if (!refreshTimer.isRunning()) {
                refreshTimer.start();
            }
            updatePlaybackLabel();
        });

        mainFrame.getComboPlaybackSpeed().addActionListener(e -> {
            String selected = (String) mainFrame.getComboPlaybackSpeed().getSelectedItem();
            int delay = parseDelayMillis(selected, 0);
            runCoordinatorAction("Error actualizando velocidad de ejecución",
                    () -> coordinator.changeExecutionDelay(delay));
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
            if (!isDirectoryPath(parent)) {
                showError("La ruta seleccionada no es un directorio. Seleccione una carpeta para crear el archivo.");
                return;
            }
            String name = JOptionPane.showInputDialog(mainFrame, "Nombre del archivo:");
            if (name == null)
                return;
            if (name.isBlank()) {
                showError("El nombre del archivo no puede estar vacío.");
                return;
            }
            String sizeStr = JOptionPane.showInputDialog(mainFrame, "Tamaño en bloques:");
            if (sizeStr == null)
                return;
            if (sizeStr.isBlank()) {
                showError("El tamaño en bloques no puede estar vacío.");
                return;
            }

            try {
                int size = parseRequiredPositiveInt(sizeStr, "La cantidad de bloques debe ser un número entero mayor que 0.");
                int freeBlocks = countFreeBlocks();
                if (size > freeBlocks) {
                    showError("No hay bloques suficientes para crear el archivo.\n"
                            + "Solicitados: " + size + " | Disponibles: " + freeBlocks);
                    return;
                }
                coordinator.submitIntent(new CreateFileIntent(parent, name, size, false, false));
            } catch (IllegalArgumentException ex) {
                showError(ex.getMessage());
            } catch (Exception ex) {
                showOperationError("crear archivo", ex);
            }
        });

        mainFrame.getBtnCreateDir().addActionListener(e -> {
            String parent = mainFrame.getFileSystemTreePanel().getSelectedNodePath();
            if (parent == null) {
                showError("Seleccione un directorio destino en el árbol.");
                return;
            }
            if (!isDirectoryPath(parent)) {
                showError("La ruta seleccionada no es un directorio. Seleccione una carpeta para crear el directorio.");
                return;
            }
            String name = JOptionPane.showInputDialog(mainFrame, "Nombre del directorio:");
            if (name == null)
                return;
            if (name.isBlank()) {
                showError("El nombre del directorio no puede estar vacío.");
                return;
            }
            try {
                coordinator.submitIntent(new CreateDirectoryIntent(parent, name, false));
            } catch (Exception ex) {
                showOperationError("crear directorio", ex);
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
                showOperationError("leer", ex);
            }
        });

        mainFrame.getBtnRename().addActionListener(e -> {
            String target = mainFrame.getFileSystemTreePanel().getSelectedNodePath();
            if (target == null) {
                showError("Seleccione un nodo en el árbol.");
                return;
            }
            String name = JOptionPane.showInputDialog(mainFrame, "Nuevo nombre:");
            if (name == null)
                return;
            if (name.isBlank()) {
                showError("El nuevo nombre no puede estar vacío.");
                return;
            }
            try {
                coordinator.submitIntent(new RenameIntent(target, name));
            } catch (Exception ex) {
                showOperationError("renombrar", ex);
            }
        });

        mainFrame.getBtnDelete().addActionListener(e -> {
            String target = mainFrame.getFileSystemTreePanel().getSelectedNodePath();
            if (target == null) {
                showError("Seleccione un nodo en el árbol.");
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(mainFrame, "¿Seguro que desea eliminar " + target + "?",
                    "Confirmar Eliminación", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                try {
                    coordinator.submitIntent(new DeleteIntent(target));
                } catch (Exception ex) {
                    showOperationError("eliminar", ex);
                }
            }
        });

        mainFrame.getBtnSimularFallo().addActionListener(e -> {
            runCoordinatorAction("Error armando fallo simulado", () -> {
                coordinator.armSimulatedFailure();
                refreshFromSnapshot();
            });
        });

        mainFrame.getBtnRecovery().addActionListener(e -> {
            runCoordinatorAction("Error ejecutando recovery", () -> {
                coordinator.recoverPendingJournalEntries();
                refreshFromSnapshot();
                JOptionPane.showMessageDialog(mainFrame, "Recovery completado.", "Recovery", JOptionPane.INFORMATION_MESSAGE);
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
            JOptionPane.showMessageDialog(mainFrame, "Sistema guardado en:\n" + selected, "Guardado",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showAdministrativeError("guardar el sistema", ex);
        }
    }

    private void handleLoadSystem() {
        Path selected = chooseFileToOpen("Cargar estado del sistema");
        if (selected == null) {
            return;
        }
        try {
            coordinator.loadSystem(selected);
            coordinator.setStepModeEnabled(true);
            refreshFromSnapshot();
            JOptionPane.showMessageDialog(mainFrame, "Sistema cargado desde:\n" + selected, "Carga completada",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showAdministrativeError("cargar el sistema", ex);
        }
    }

    private void handleLoadScenario() {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (!isCoordinatorIdle(snapshot)) {
            showError(buildBusyScenarioMessage(snapshot));
            return;
        }

        Path selected = chooseFileToOpen("Cargar escenario JSON");
        if (selected == null) {
            return;
        }
        try {
            coordinator.loadScenario(selected);
            coordinator.setStepModeEnabled(true);
            refreshFromSnapshot();
            JOptionPane.showMessageDialog(mainFrame, "Escenario cargado desde:\n" + selected, "Escenario cargado",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showAdministrativeError("cargar el escenario", ex);
        }
    }

    private void handleAdvancedOptions() {
        String[] options = new String[] {
                "Resetear simulación",
                "Cambiar cantidad de bloques",
                "Cambiar dirección del cabezal",
                "Cancelar"
        };
        int selection = JOptionPane.showOptionDialog(
                mainFrame,
                "Seleccione una acción avanzada",
                "Opciones Avanzadas",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[0]);

        if (selection == 0) {
            handleResetSimulation();
        } else if (selection == 1) {
            handleChangeBlockCount();
        } else if (selection == 2) {
            handleChangeHeadDirection();
        }
    }

    private void handleResetSimulation() {
        int confirm = JOptionPane.showConfirmDialog(
                mainFrame,
                "Se reseteará la simulación al estado inicial.\n¿Desea continuar?",
                "Confirmar Reset",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            coordinator.resetSimulation();
            refreshFromSnapshot();
            JOptionPane.showMessageDialog(
                    mainFrame,
                    "La simulación fue reseteada correctamente.",
                    "Reset completado",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showAdministrativeError("resetear la simulación", ex);
        }
    }

    private void handleChangeBlockCount() {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (hasSimulationRun(snapshot)) {
            showError("Solo puedes cambiar la cantidad de bloques si la simulación no ha corrido nada.\n"
                    + "Esto aplica al inicio o justo después de un reset.");
            return;
        }

        Integer[] options = {50, 100, 150, 200, 300, 400, 500};
        int currentBlocks = snapshot != null ? snapshot.getDiskBlocksSnapshot().length : 100;

        Integer selectedOption = (Integer) JOptionPane.showInputDialog(
                mainFrame,
                "Seleccione la nueva cantidad de bloques:",
                "Cambiar cantidad de bloques",
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                currentBlocks > 0 ? currentBlocks : 100);

        if (selectedOption == null) {
            return;
        }

        try {
            coordinator.resetSimulation(selectedOption);
            refreshFromSnapshot();
            JOptionPane.showMessageDialog(
                    mainFrame,
                    "Cantidad de bloques actualizada a " + selectedOption + ".",
                    "Bloques actualizados",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showAdministrativeError("cambiar la cantidad de bloques", ex);
        }
    }

    private void handleChangeHeadDirection() {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (snapshot == null) {
            showError("No hay snapshot disponible.");
            return;
        }

        DiskHeadDirection currentDirection = snapshot.getHeadDirection();
        String[] options = {"UP", "DOWN"};
        String currentSelection = currentDirection.toString();

        String selectedOption = (String) JOptionPane.showInputDialog(
                mainFrame,
                "Seleccione la nueva dirección del cabezal:",
                "Cambiar dirección del cabezal",
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                currentSelection);

        if (selectedOption == null) {
            return;
        }

        try {
            DiskHeadDirection newDirection = DiskHeadDirection.valueOf(selectedOption);
            coordinator.changeHeadDirection(newDirection);
            refreshFromSnapshot();
            JOptionPane.showMessageDialog(
                    mainFrame,
                    "Dirección del cabezal cambiada a " + newDirection + ".",
                    "Dirección actualizada",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showAdministrativeError("cambiar la dirección del cabezal", ex);
        }
    }

    private boolean hasSimulationRun(SimulationSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return snapshot.getTerminatedProcessesSnapshot().length > 0
                || snapshot.getDispatchHistorySnapshot().length > 0
                || snapshot.getJournalEntriesSnapshot().length > 0
                || snapshot.getTotalSeekDistance() > 0;
    }

    private boolean isCoordinatorIdle(SimulationSnapshot snapshot) {
        if (snapshot == null) {
            return true;
        }
        boolean hasRunning = snapshot.getRunningProcessSnapshot() != null;
        boolean hasNew = snapshot.getNewProcessesSnapshot().length > 0;
        boolean hasReady = snapshot.getReadyProcessesSnapshot().length > 0;
        boolean hasBlocked = snapshot.getBlockedProcessesSnapshot().length > 0;
        return !(hasRunning || hasNew || hasReady || hasBlocked);
    }

    private String buildBusyScenarioMessage(SimulationSnapshot snapshot) {
        if (snapshot == null) {
            return "No se puede cargar el escenario en este momento. Intente nuevamente en unos segundos.";
        }

        int newCount = snapshot.getNewProcessesSnapshot().length;
        int readyCount = snapshot.getReadyProcessesSnapshot().length;
        int blockedCount = snapshot.getBlockedProcessesSnapshot().length;
        int runningCount = snapshot.getRunningProcessSnapshot() != null ? 1 : 0;

        return "No se puede cargar el escenario porque la simulación aún no está idle.\n"
                + "Procesos activos: " + (newCount + readyCount + blockedCount + runningCount) + "\n"
                + "(Nuevo=" + newCount
                + ", Listo=" + readyCount
                + ", Bloqueado=" + blockedCount
                + ", Ejecutando=" + runningCount + ")\n\n"
                + "Presione Play para que termine la cola actual y vuelva a intentar.";
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
            return 0;
        } else if (normalized.contains("rápido") || normalized.contains("rapido")) {
            return 100;
        } else if (normalized.contains("medio")) {
            return 500;
        } else if (normalized.contains("lento")) {
            return 1000;
        }
        return fallback;
    }

    private void updatePlaybackLabel() {
        updatePlaybackLabel((int) coordinator.getExecutionDelayMillis());
    }

    private void updatePlaybackLabel(int executionDelay) {
        String mode = coordinator.isStepModeEnabled() ? "Pausado / Manual" : "Reproduciendo";
        String speedText = executionDelay <= 0 ? "Instantáneo" : executionDelay + " ms";
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

    private void updateActionButtonsBySystemState(boolean recoveryRequired) {
        boolean enableGeneralActions = !recoveryRequired;

        // En recovery quarantine solo se permite recovery/load system/estadisticas.
        mainFrame.getBtnAdmin().setEnabled(enableGeneralActions);
        mainFrame.getBtnUser().setEnabled(enableGeneralActions);
        mainFrame.getBtnPlay().setEnabled(enableGeneralActions);
        mainFrame.getBtnPause().setEnabled(enableGeneralActions);
        mainFrame.getBtnStep().setEnabled(enableGeneralActions);
        mainFrame.getComboPlaybackSpeed().setEnabled(enableGeneralActions);
        mainFrame.getComboPolicy().setEnabled(enableGeneralActions);
        mainFrame.getBtnPolicyChange().setEnabled(enableGeneralActions);
        mainFrame.getBtnSimularFallo().setEnabled(enableGeneralActions);
        mainFrame.getBtnAdvanced().setEnabled(enableGeneralActions);

        mainFrame.getBtnCreateFile().setEnabled(enableGeneralActions);
        mainFrame.getBtnCreateDir().setEnabled(enableGeneralActions);
        mainFrame.getBtnRead().setEnabled(enableGeneralActions);
        mainFrame.getBtnRename().setEnabled(enableGeneralActions);
        mainFrame.getBtnDelete().setEnabled(enableGeneralActions);
        mainFrame.getBtnSaveSystem().setEnabled(enableGeneralActions);
        mainFrame.getBtnLoadScenario().setEnabled(enableGeneralActions);

        mainFrame.getBtnStats().setEnabled(true);
        mainFrame.getBtnLoadSystem().setEnabled(true);
        mainFrame.getBtnRecovery().setEnabled(true);
    }

    private void updateFailureUxState(boolean failureArmed, boolean recoveryRequired) {
        if (recoveryRequired) {
            mainFrame.getLblSystemState().setText("Estado del Sistema: Recovery requerido");
            mainFrame.getLblSystemState().setForeground(DarkTheme.ACCENT_RED);
            mainFrame.getLblSystemState().setToolTipText("Se requiere ejecutar Recovery antes de continuar");
            if (!recoveryPopupShown) {
                recoveryPopupShown = true;
                JOptionPane.showMessageDialog(
                        mainFrame,
                        "Se produjo un fallo simulado. Debe ejecutar Recovery antes de continuar.",
                        "Fallo simulado",
                        JOptionPane.WARNING_MESSAGE);
            }
            return;
        }

        recoveryPopupShown = false;
        if (failureArmed) {
            mainFrame.getLblSystemState().setText("Estado del Sistema: Fallo armado");
            mainFrame.getLblSystemState().setForeground(DarkTheme.ACCENT_YELLOW);
            mainFrame.getLblSystemState().setToolTipText(ARMED_TOOLTIP);
            return;
        }

        mainFrame.getLblSystemState().setText("Estado del Sistema: Normal");
        mainFrame.getLblSystemState().setForeground(DarkTheme.FG_PRIMARY);
        mainFrame.getLblSystemState().setToolTipText(null);
    }

    private void refreshFromSnapshot() {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (snapshot == null)
            return;

        // Keep playback label aligned with the actual coordinator state.
        updatePlaybackLabel();

        boolean recoveryRequired = coordinator.isRecoveryQuarantineActive();
        boolean failureArmed = coordinator.isSimulatedFailureArmed() && !recoveryRequired;

        showAsyncIntentFailurePopups(snapshot, recoveryRequired);

        updateActionButtonsBySystemState(recoveryRequired);
        updateFailureUxState(failureArmed, recoveryRequired);

        mainFrame.getFileSystemTreePanel().updateFromSnapshot(snapshot.getFileSystemNodesSnapshot());
        mainFrame.getDiskVisualizationPanel().updateFromSnapshot(snapshot.getDiskBlocksSnapshot(),
                snapshot.getFileSystemNodesSnapshot(), snapshot.getHeadBlock());
        mainFrame.getJournalPanel().updateFromSnapshot(snapshot.getJournalEntriesSnapshot());
        mainFrame.getProcessQueuePanel().updateFromSnapshot(
                snapshot.getRunningProcessSnapshot(),
                snapshot.getReadyProcessesSnapshot(),
                snapshot.getBlockedProcessesSnapshot(),
                snapshot.getTerminatedProcessesSnapshot());
        mainFrame.getEventLogPanel().updateFromSnapshot(snapshot.getEventLogEntriesSnapshot());

        mainFrame.getLblStatusLeft().setText("Cabeza: Block " + snapshot.getHeadBlock() + " ("
                + snapshot.getHeadDirection() + ") | Política: " + snapshot.getPolicy());

        if (snapshot.getSessionSummary() != null) {
            mainFrame.getLblStatusCenter().setText("Sesión: " + snapshot.getSessionSummary().getCurrentUserId() + " / "
                    + snapshot.getSessionSummary().getCurrentRole());
        }

        String statusRight = "Seek total: " + snapshot.getTotalSeekDistance();
        if (recoveryRequired) {
            statusRight = statusRight + " | Modo: Recovery pendiente";
        }
        mainFrame.getLblStatusRight().setText(statusRight);
    }

    private void showAsyncIntentFailurePopups(SimulationSnapshot snapshot, boolean recoveryRequired) {
        if (recoveryRequired) {
            return;
        }

        SimulationSnapshot.ProcessSnapshot[] terminated = snapshot.getTerminatedProcessesSnapshot();
        Set<String> currentTerminatedIds = new HashSet<>();

        for (SimulationSnapshot.ProcessSnapshot process : terminated) {
            currentTerminatedIds.add(process.getProcessId());
        }

        for (SimulationSnapshot.ProcessSnapshot process : terminated) {
            if (!isManualIntentProcess(process)) {
                continue;
            }
            if (process.getResultStatus() != ve.edu.unimet.so.project2.process.ResultStatus.FAILED) {
                continue;
            }
            if (notifiedIntentFailures.contains(process.getProcessId())) {
                continue;
            }

            String operation = resolveOperationLabel(process.getOperationType());
            String friendlyMessage = toFriendlyErrorMessage(operation, process.getErrorMessage());
            showError(friendlyMessage);
            notifiedIntentFailures.add(process.getProcessId());
            break;
        }

        notifiedIntentFailures.retainAll(currentTerminatedIds);
    }

    private boolean isManualIntentProcess(SimulationSnapshot.ProcessSnapshot process) {
        String processId = process.getProcessId();
        return processId != null && processId.startsWith("INTENT-PROC-");
    }

    private String resolveOperationLabel(ve.edu.unimet.so.project2.process.IoOperationType operationType) {
        if (operationType == null) {
            return "procesar";
        }
        return switch (operationType) {
            case CREATE -> "crear";
            case READ -> "leer";
            case UPDATE -> "renombrar";
            case DELETE -> "eliminar";
        };
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(mainFrame, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private int parseRequiredPositiveInt(String rawValue, String validationMessage) {
        try {
            int value = Integer.parseInt(rawValue.trim());
            if (value <= 0) {
                throw new IllegalArgumentException(validationMessage);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(validationMessage);
        }
    }

    private int countFreeBlocks() {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (snapshot == null || snapshot.getDiskBlocksSnapshot() == null) {
            return 0;
        }
        int free = 0;
        for (SimulationSnapshot.DiskBlockSummary block : snapshot.getDiskBlocksSnapshot()) {
            if (block.isFree()) {
                free++;
            }
        }
        return free;
    }

    private boolean isDirectoryPath(String path) {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (snapshot == null || path == null) {
            return false;
        }
        for (SimulationSnapshot.FileSystemNodeSummary node : snapshot.getFileSystemNodesSnapshot()) {
            if (path.equals(node.getPath())) {
                return node.getType() == ve.edu.unimet.so.project2.filesystem.FsNodeType.DIRECTORY;
            }
        }
        return false;
    }

    private void showOperationError(String operation, Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }

        showError(toFriendlyErrorMessage(operation, message));
    }

    private void showAdministrativeError(String action, Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        showError(toFriendlyAdministrativeErrorMessage(action, message));
    }

    private String toFriendlyAdministrativeErrorMessage(String action, String rawMessage) {
        String message = rawMessage == null || rawMessage.isBlank() ? "Error desconocido" : rawMessage;
        String lower = message.toLowerCase();

        if (lower.contains("failed to load scenario")) {
            return "No se pudo cargar el escenario JSON. Verifica que el archivo exista y tenga formato válido.";
        }
        if (lower.contains("scenario initial_head is out of range") || lower.contains("initial_head")) {
            return "El escenario tiene un initial_head fuera del rango de bloques del disco.";
        }
        if (lower.contains("scenario request pos is out of range")) {
            return "El escenario contiene requests con posiciones de bloque fuera de rango.";
        }
        if (lower.contains("scenario system file") && lower.contains("out of range")) {
            return "El escenario define archivos de sistema con bloques fuera de rango.";
        }
        if (lower.contains("failed to load system state")) {
            return "No se pudo cargar el estado del sistema. El archivo puede estar corrupto o no ser válido.";
        }
        if (lower.contains("failed to save system state")) {
            return "No se pudo guardar el estado del sistema. Verifica permisos y ruta del archivo.";
        }
        if (lower.contains("path cannot be null")) {
            return "Debes seleccionar un archivo válido para completar esta acción.";
        }
        if (lower.contains("coordinator is awaiting recovery")) {
            return "No se puede " + action + " mientras el sistema está en modo de recovery pendiente.";
        }

        return "No se pudo " + action + ". Verifica los datos e inténtalo de nuevo.";
    }

    private String toFriendlyErrorMessage(String operation, String rawMessage) {
        String message = rawMessage == null || rawMessage.isBlank() ? "Error desconocido" : rawMessage;
        String lower = message.toLowerCase();

        if (lower.contains("cannot modify")) {
            return "No tienes permisos para " + operation + " este recurso porque no es tuyo.";
        }
        if (lower.contains("not enough free disk blocks")) {
            return "No hay bloques suficientes disponibles para completar la operación.";
        }
        if (lower.contains("path does not reference a directory")) {
            return "La ruta seleccionada no es un directorio válido para esta operación.";
        }
        if (lower.contains("path does not reference a file")) {
            return "La ruta seleccionada no es un archivo. Debes seleccionar un archivo para leer.";
        }
        if (lower.contains("name cannot contain /")
                || lower.contains("name cannot be . or ..")
                || lower.contains("name cannot be blank")) {
            return "El nombre ingresado no es válido. No puede ser vacío, '.' , '..' ni contener '/'.";
        }
        if (lower.contains("duplicate child name under parent") || lower.contains("already exists at path")) {
            return "Ya existe un archivo o directorio con ese nombre en la carpeta destino.";
        }
        if (lower.contains("already has requested name")) {
            return "El nombre solicitado ya estaba aplicado. La operación de renombrado no realizó cambios.";
        }
        if (lower.contains("root cannot be renamed")) {
            return "No se puede renombrar la raíz del sistema de archivos.";
        }
        if (lower.contains("root cannot be deleted")) {
            return "No se puede eliminar la raíz del sistema de archivos.";
        }
        if (lower.contains("node not found at path")) {
            return "La ruta seleccionada ya no existe. Actualiza la vista e inténtalo de nuevo.";
        }

        return "Error al " + operation + ": " + message;
    }
}
