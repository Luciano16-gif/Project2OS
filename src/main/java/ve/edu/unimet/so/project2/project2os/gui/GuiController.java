package ve.edu.unimet.so.project2.project2os.gui;

import java.awt.Component;
import java.awt.Cursor;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import javax.swing.AbstractButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import ve.edu.unimet.so.project2.application.ApplicationOperationIntent;
import ve.edu.unimet.so.project2.application.CreateDirectoryIntent;
import ve.edu.unimet.so.project2.application.CreateFileIntent;
import ve.edu.unimet.so.project2.application.DeleteIntent;
import ve.edu.unimet.so.project2.application.ReadIntent;
import ve.edu.unimet.so.project2.application.RenameIntent;
import ve.edu.unimet.so.project2.coordinator.core.SimulationCoordinator;
import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot;
import ve.edu.unimet.so.project2.disk.DiskHeadDirection;
import ve.edu.unimet.so.project2.filesystem.FsNodeType;
import ve.edu.unimet.so.project2.process.IoOperationType;
import ve.edu.unimet.so.project2.process.ResultStatus;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

public class GuiController {

    private static final String ARMED_TOOLTIP = "El proximo commit journaled disparara un fallo simulado";
    private static final int REFRESH_DELAY_MILLIS = 150;

    private final SimulationCoordinator coordinator;
    private final MainFrame mainFrame;
    private final Timer refreshTimer;
    private final Set<String> notifiedIntentFailures = new HashSet<>();

    private boolean recoveryPopupShown;
    private volatile boolean administrativeTaskRunning;

    public GuiController(SimulationCoordinator coordinator, MainFrame mainFrame) {
        this.coordinator = coordinator;
        this.mainFrame = mainFrame;
        runCoordinatorAction("Error configurando modo inicial", () -> coordinator.setStepModeEnabled(true));
        this.refreshTimer = new Timer(REFRESH_DELAY_MILLIS, e -> refreshFromSnapshot());
        setupListeners();
        refreshTimer.start();
        updatePlaybackLabel();
    }

    private void setupListeners() {
        bindSessionButton(mainFrame.btnAdmin, "admin", "Error cambiando a sesion Admin");
        bindSessionButton(mainFrame.btnUser, "user-1", "Error cambiando a sesion Usuario");

        mainFrame.btnPolicyChange.addActionListener(e -> {
            String policy = (String) mainFrame.comboPolicy.getSelectedItem();
            if (policy != null) {
                runCoordinatorAction(
                        "Error cambiando politica",
                        () -> coordinator.changePolicy(DiskSchedulingPolicy.valueOf(policy)));
            }
        });

        mainFrame.btnSaveSystem.addActionListener(e -> handleSaveSystem());
        mainFrame.btnLoadSystem.addActionListener(e -> handleLoadSystem());
        mainFrame.btnLoadScenario.addActionListener(e -> handleLoadScenario());
        mainFrame.btnAdvanced.addActionListener(e -> handleAdvancedOptions());
        mainFrame.btnStats.addActionListener(e -> showStatisticsDialog());

        bindPlaybackButton(mainFrame.btnPlay, "Error reanudando simulacion", () -> coordinator.setStepModeEnabled(false));
        bindPlaybackButton(mainFrame.btnPause, "Error pausando simulacion", () -> coordinator.setStepModeEnabled(true));
        bindPlaybackButton(mainFrame.btnStep, "Error avanzando simulacion por paso", coordinator::stepSimulationOnce);

        mainFrame.comboPlaybackSpeed.addActionListener(e -> {
            int delay = parseDelayMillis((String) mainFrame.comboPlaybackSpeed.getSelectedItem(), 0);
            runCoordinatorAction("Error actualizando velocidad de ejecucion", () -> coordinator.changeExecutionDelay(delay));
            refreshTimer.setDelay(REFRESH_DELAY_MILLIS);
            refreshTimer.setInitialDelay(REFRESH_DELAY_MILLIS);
            updatePlaybackLabel(delay);
        });

        mainFrame.btnCreateFile.addActionListener(e -> handleCreateFile());
        mainFrame.btnCreateDir.addActionListener(e -> handleCreateDirectory());
        mainFrame.btnRead.addActionListener(e -> handleSimpleIntent(
                "Seleccione un archivo en el arbol.",
                "leer",
                ReadIntent::new));
        mainFrame.btnRename.addActionListener(e -> handleRename());
        mainFrame.btnDelete.addActionListener(e -> handleDelete());

        mainFrame.btnSimularFallo.addActionListener(e -> runCoordinatorAction("Error armando fallo simulado", () -> {
            coordinator.armSimulatedFailure();
            refreshFromSnapshot();
        }));
        mainFrame.btnRecovery.addActionListener(e -> runAdministrativeActionAsync(
                "ejecutar recovery",
                coordinator::recoverPendingJournalEntries,
                () -> showInfo("Recovery completado.", "Recovery")));
    }

    private void handleCreateFile() {
        String parent = requireSelectedDirectoryPath(
                "Seleccione un directorio destino en el arbol.",
                "La ruta seleccionada no es un directorio. Seleccione una carpeta para crear el archivo.");
        if (parent == null) {
            return;
        }

        String name = promptNonBlank("Nombre del archivo:", "El nombre del archivo no puede estar vacio.");
        String sizeRaw = promptNonBlank("Tamano en bloques:", "El tamano en bloques no puede estar vacio.");
        if (name == null || sizeRaw == null) {
            return;
        }

        try {
            int size = parseRequiredPositiveInt(sizeRaw, "La cantidad de bloques debe ser un numero entero mayor que 0.");
            int freeBlocks = countFreeBlocks();
            if (size > freeBlocks) {
                showError("No hay bloques suficientes para crear el archivo.\nSolicitados: " + size + " | Disponibles: " + freeBlocks);
                return;
            }
            submitIntent("crear archivo", new CreateFileIntent(parent, name, size, false, false));
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private void handleCreateDirectory() {
        String parent = requireSelectedDirectoryPath(
                "Seleccione un directorio destino en el arbol.",
                "La ruta seleccionada no es un directorio. Seleccione una carpeta para crear el directorio.");
        if (parent == null) {
            return;
        }

        String name = promptNonBlank("Nombre del directorio:", "El nombre del directorio no puede estar vacio.");
        if (name != null) {
            submitIntent("crear directorio", new CreateDirectoryIntent(parent, name, false));
        }
    }

    private void handleRename() {
        String target = requireSelectedNodePath("Seleccione un nodo en el arbol.");
        String name = promptNonBlank("Nuevo nombre:", "El nuevo nombre no puede estar vacio.");
        if (target != null && name != null) {
            submitIntent("renombrar", new RenameIntent(target, name));
        }
    }

    private void handleDelete() {
        String target = requireSelectedNodePath("Seleccione un nodo en el arbol.");
        if (target == null) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(
                mainFrame,
                "Seguro que desea eliminar " + target + "?",
                "Confirmar Eliminacion",
                JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            submitIntent("eliminar", new DeleteIntent(target));
        }
    }

    private void handleSimpleIntent(String missingMessage, String operation, Function<String, ApplicationOperationIntent> factory) {
        String target = requireSelectedNodePath(missingMessage);
        if (target != null) {
            submitIntent(operation, factory.apply(target));
        }
    }

    private void handleSaveSystem() {
        Path selected = chooseAdministrativePath(() -> chooseFileToSave("Guardar estado del sistema", "system-state.json"));
        if (selected != null) {
            runAdministrativeActionAsync(
                    "guardar el sistema",
                    () -> coordinator.saveSystem(selected),
                    () -> showInfo("Sistema guardado en:\n" + selected, "Guardado"));
        }
    }

    private void handleLoadSystem() {
        Path selected = chooseAdministrativePath(() -> chooseFileToOpen("Cargar estado del sistema"));
        if (selected != null) {
            runAdministrativeActionAsync(
                    "cargar el sistema",
                    () -> coordinator.loadSystem(selected),
                    () -> showInfo("Sistema cargado desde:\n" + selected, "Carga completada"));
        }
    }

    private void handleLoadScenario() {
        if (!ensureAdministrativeSlot()) {
            return;
        }
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (!isCoordinatorIdle(snapshot)) {
            showError(buildBusyScenarioMessage(snapshot));
            return;
        }
        Path selected = chooseAdministrativePath(() -> chooseFileToOpen("Cargar escenario JSON"));
        if (selected != null) {
            runAdministrativeActionAsync(
                    "cargar el escenario",
                    () -> coordinator.loadScenario(selected),
                    () -> showInfo("Escenario cargado desde:\n" + selected, "Escenario cargado"));
        }
    }

    private void handleAdvancedOptions() {
        String[] options = {
            "Resetear simulacion",
            "Cambiar cantidad de bloques",
            "Cambiar direccion del cabezal",
            "Cancelar"
        };
        int selection = JOptionPane.showOptionDialog(
                mainFrame,
                "Seleccione una accion avanzada",
                "Opciones Avanzadas",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[0]);
        switch (selection) {
            case 0 -> handleResetSimulation();
            case 1 -> handleChangeBlockCount();
            case 2 -> handleChangeHeadDirection();
            default -> { }
        }
    }

    private void handleResetSimulation() {
        int confirm = JOptionPane.showConfirmDialog(
                mainFrame,
                "Se reseteara la simulacion al estado inicial.\nDesea continuar?",
                "Confirmar Reset",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.YES_OPTION) {
            runAdministrativeActionAsync(
                    "resetear la simulacion",
                    coordinator::resetSimulation,
                    () -> showInfo("La simulacion fue reseteada correctamente.", "Reset completado"));
        }
    }

    private void handleChangeBlockCount() {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (hasSimulationRun(snapshot)) {
            showError("Solo puedes cambiar la cantidad de bloques si la simulacion no ha corrido nada.\nEsto aplica al inicio o justo despues de un reset.");
            return;
        }

        Integer[] options = {50, 100, 150, 200, 300, 400, 500};
        int currentBlocks = snapshot == null ? 100 : snapshot.getDiskBlocksSnapshot().length;
        Integer selected = (Integer) JOptionPane.showInputDialog(
                mainFrame,
                "Seleccione la nueva cantidad de bloques:",
                "Cambiar cantidad de bloques",
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                currentBlocks > 0 ? currentBlocks : 100);
        if (selected != null) {
            runAdministrativeActionAsync(
                    "cambiar la cantidad de bloques",
                    () -> coordinator.resetSimulation(selected),
                    () -> showInfo("Cantidad de bloques actualizada a " + selected + ".", "Bloques actualizados"));
        }
    }

    private void handleChangeHeadDirection() {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (snapshot == null) {
            showError("No hay snapshot disponible.");
            return;
        }

        String selected = (String) JOptionPane.showInputDialog(
                mainFrame,
                "Seleccione la nueva direccion del cabezal:",
                "Cambiar direccion del cabezal",
                JOptionPane.QUESTION_MESSAGE,
                null,
                new String[] {"UP", "DOWN"},
                snapshot.getHeadDirection().toString());
        if (selected == null) {
            return;
        }

        try {
            DiskHeadDirection direction = DiskHeadDirection.valueOf(selected);
            runAdministrativeActionAsync(
                    "cambiar la direccion del cabezal",
                    () -> coordinator.changeHeadDirection(direction),
                    () -> showInfo("Direccion del cabezal cambiada a " + direction + ".", "Direccion actualizada"));
        } catch (Exception exception) {
            showAdministrativeError("cambiar la direccion del cabezal", exception);
        }
    }

    private void bindSessionButton(AbstractButton button, String userId, String errorPrefix) {
        button.addActionListener(e -> runCoordinatorAction(errorPrefix, () -> {
            coordinator.switchSession(userId);
            refreshFromSnapshot();
        }));
    }

    private void bindPlaybackButton(AbstractButton button, String errorPrefix, Runnable action) {
        button.addActionListener(e -> {
            runCoordinatorAction(errorPrefix, action);
            ensureRefreshTimerRunning();
            updatePlaybackLabel();
        });
    }

    private boolean ensureAdministrativeSlot() {
        if (!administrativeTaskRunning) {
            return true;
        }
        showError("Ya hay una operacion administrativa en progreso. Espera a que termine.");
        return false;
    }

    private void runAdministrativeActionAsync(String action, Runnable backgroundAction, Runnable onSuccess) {
        if (!ensureAdministrativeSlot()) {
            return;
        }

        administrativeTaskRunning = true;
        setAdministrativeUiBusy(true);
        new SwingWorker<Void, Void>() {
            private Exception failure;

            @Override
            protected Void doInBackground() {
                try {
                    backgroundAction.run();
                } catch (Exception exception) {
                    failure = exception;
                }
                return null;
            }

            @Override
            protected void done() {
                administrativeTaskRunning = false;
                setAdministrativeUiBusy(false);
                refreshFromSnapshot();
                if (failure != null) {
                    showAdministrativeError(action, failure);
                    return;
                }
                if (onSuccess != null) {
                    onSuccess.run();
                }
            }
        }.execute();
    }

    private void ensureRefreshTimerRunning() {
        if (!refreshTimer.isRunning()) {
            refreshTimer.start();
        }
    }

    private void setAdministrativeUiBusy(boolean busy) {
        mainFrame.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private void submitIntent(String operation, ApplicationOperationIntent intent) {
        try {
            coordinator.submitIntent(intent);
        } catch (Exception exception) {
            showOperationError(operation, exception);
        }
    }

    private String requireSelectedNodePath(String missingMessage) {
        String selected = mainFrame.fileSystemTreePanel.getSelectedNodePath();
        if (selected == null) {
            showError(missingMessage);
        }
        return selected;
    }

    private String requireSelectedDirectoryPath(String missingMessage, String invalidMessage) {
        String selected = requireSelectedNodePath(missingMessage);
        if (selected == null) {
            return null;
        }
        if (isDirectoryPath(selected)) {
            return selected;
        }
        showError(invalidMessage);
        return null;
    }

    private String promptNonBlank(String prompt, String emptyMessage) {
        String value = JOptionPane.showInputDialog(mainFrame, prompt);
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            showError(emptyMessage);
            return null;
        }
        return value;
    }

    private Path chooseAdministrativePath(Supplier<Path> chooser) {
        return ensureAdministrativeSlot() ? chooser.get() : null;
    }

    private boolean hasSimulationRun(SimulationSnapshot snapshot) {
        return snapshot != null
                && (snapshot.getTerminatedProcessesSnapshot().length > 0
                || snapshot.getDispatchHistorySnapshot().length > 0
                || snapshot.getJournalEntriesSnapshot().length > 0
                || snapshot.getTotalSeekDistance() > 0);
    }

    private boolean isCoordinatorIdle(SimulationSnapshot snapshot) {
        return snapshot == null || (
                snapshot.getRunningProcessSnapshot() == null
                && snapshot.getNewProcessesSnapshot().length == 0
                && snapshot.getReadyProcessesSnapshot().length == 0
                && snapshot.getBlockedProcessesSnapshot().length == 0);
    }

    private String buildBusyScenarioMessage(SimulationSnapshot snapshot) {
        if (snapshot == null) {
            return "No se puede cargar el escenario en este momento. Intente nuevamente en unos segundos.";
        }
        int newCount = snapshot.getNewProcessesSnapshot().length;
        int readyCount = snapshot.getReadyProcessesSnapshot().length;
        int blockedCount = snapshot.getBlockedProcessesSnapshot().length;
        int runningCount = snapshot.getRunningProcessSnapshot() == null ? 0 : 1;
        int total = newCount + readyCount + blockedCount + runningCount;
        return "No se puede cargar el escenario porque la simulacion aun no esta idle.\n"
                + "Procesos activos: " + total + "\n"
                + "(Nuevo=" + newCount
                + ", Listo=" + readyCount
                + ", Bloqueado=" + blockedCount
                + ", Ejecutando=" + runningCount + ")\n\n"
                + "Presione Play para que termine la cola actual y vuelva a intentar.";
    }

    private void showStatisticsDialog() {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (snapshot == null) {
            showError("No hay snapshot disponible todavia.");
            return;
        }
        int totalBlocks = snapshot.getDiskBlocksSnapshot().length;
        int freeBlocks = countFreeBlocks(snapshot);
        int usedBlocks = totalBlocks - freeBlocks;
        String message = "Bloques usados: " + usedBlocks + " / " + totalBlocks + "\n"
                + "Bloques libres: " + freeBlocks + "\n"
                + "Seek total: " + snapshot.getTotalSeekDistance() + "\n"
                + "Procesos completados: " + snapshot.getTerminatedProcessesSnapshot().length + "\n"
                + "Tiempo de simulacion (tick): " + estimateSimulationTick(snapshot);
        showInfo(message, "Estadisticas del Sistema");
    }

    private long estimateSimulationTick(SimulationSnapshot snapshot) {
        long maxTick = 0L;
        for (SimulationSnapshot.EventLogEntrySummary entry : snapshot.getEventLogEntriesSnapshot()) {
            maxTick = Math.max(maxTick, entry.getTick());
        }
        return maxTick;
    }

    private Path chooseFileToOpen(String title) {
        return chooseJsonPath(buildJsonFileChooser(title), chooser -> chooser.showOpenDialog(mainFrame));
    }

    private Path chooseFileToSave(String title, String defaultName) {
        JFileChooser chooser = buildJsonFileChooser(title);
        chooser.setSelectedFile(new java.io.File(defaultName));
        Path path = chooseJsonPath(chooser, fileChooser -> fileChooser.showSaveDialog(mainFrame));
        if (path == null) {
            return null;
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".json") ? path : path.resolveSibling(path.getFileName() + ".json");
    }

    private Path chooseJsonPath(JFileChooser chooser, ToIntFunction<JFileChooser> dialogOpener) {
        int result = dialogOpener.applyAsInt(chooser);
        if (result != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return null;
        }
        return chooser.getSelectedFile().toPath();
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
        }
        if (normalized.contains("rapido") || normalized.contains("rÃ¡pido") || normalized.contains("rápido")) {
            return 100;
        }
        if (normalized.contains("medio")) {
            return 500;
        }
        if (normalized.contains("lento")) {
            return 1000;
        }
        return fallback;
    }

    private void updatePlaybackLabel() {
        updatePlaybackLabel((int) coordinator.getExecutionDelayMillis());
    }

    private void updatePlaybackLabel(int executionDelay) {
        String speedText = executionDelay <= 0 ? "Instantaneo" : executionDelay + " ms";
        if (executionDelay >= 1000) {
            speedText = (executionDelay / 1000) + " Seg";
        }
        String mode = coordinator.isStepModeEnabled() ? "Pausado / Manual" : "Reproduciendo";
        mainFrame.lblCycle.setText(mode + " (" + speedText + ")");
    }

    private void runCoordinatorAction(String errorPrefix, Runnable action) {
        try {
            action.run();
        } catch (Exception exception) {
            showError(errorPrefix + ": " + exception.getMessage());
        }
    }

    private void updateActionButtonsBySystemState(boolean recoveryRequired) {
        boolean enableGeneralActions = !recoveryRequired && !administrativeTaskRunning;
        setEnabled(enableGeneralActions,
                mainFrame.btnAdmin,
                mainFrame.btnUser,
                mainFrame.btnPlay,
                mainFrame.btnPause,
                mainFrame.btnStep,
                mainFrame.comboPlaybackSpeed,
                mainFrame.comboPolicy,
                mainFrame.btnPolicyChange,
                mainFrame.btnSimularFallo,
                mainFrame.btnAdvanced,
                mainFrame.btnCreateFile,
                mainFrame.btnCreateDir,
                mainFrame.btnRead,
                mainFrame.btnRename,
                mainFrame.btnDelete,
                mainFrame.btnSaveSystem,
                mainFrame.btnLoadScenario);
        setEnabled(true, mainFrame.btnStats, mainFrame.btnLoadSystem, mainFrame.btnRecovery);
    }

    private void updateFailureUxState(boolean failureArmed, boolean recoveryRequired) {
        if (recoveryRequired) {
            mainFrame.lblSystemState.setText("Estado del Sistema: Recovery requerido");
            mainFrame.lblSystemState.setForeground(DarkTheme.ACCENT_RED);
            mainFrame.lblSystemState.setToolTipText("Se requiere ejecutar Recovery antes de continuar");
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
            mainFrame.lblSystemState.setText("Estado del Sistema: Fallo armado");
            mainFrame.lblSystemState.setForeground(DarkTheme.ACCENT_YELLOW);
            mainFrame.lblSystemState.setToolTipText(ARMED_TOOLTIP);
            return;
        }

        mainFrame.lblSystemState.setText("Estado del Sistema: Normal");
        mainFrame.lblSystemState.setForeground(DarkTheme.FG_PRIMARY);
        mainFrame.lblSystemState.setToolTipText(null);
    }

    private void refreshFromSnapshot() {
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (snapshot == null) {
            return;
        }

        updatePlaybackLabel();
        boolean recoveryRequired = coordinator.isRecoveryQuarantineActive();
        boolean failureArmed = coordinator.isSimulatedFailureArmed() && !recoveryRequired;
        showAsyncIntentFailurePopups(snapshot, recoveryRequired);
        updateActionButtonsBySystemState(recoveryRequired);
        updateFailureUxState(failureArmed, recoveryRequired);

        mainFrame.fileSystemTreePanel.updateFromSnapshot(snapshot.getFileSystemNodesSnapshot());
        mainFrame.diskVisualizationPanel.updateFromSnapshot(
                snapshot.getDiskBlocksSnapshot(),
                snapshot.getFileSystemNodesSnapshot(),
                snapshot.getHeadBlock());
        mainFrame.journalPanel.updateFromSnapshot(snapshot.getJournalEntriesSnapshot());
        mainFrame.processQueuePanel.updateFromSnapshot(
                snapshot.getRunningProcessSnapshot(),
                snapshot.getReadyProcessesSnapshot(),
                snapshot.getBlockedProcessesSnapshot(),
                snapshot.getTerminatedProcessesSnapshot());
        mainFrame.eventLogPanel.updateFromSnapshot(snapshot.getEventLogEntriesSnapshot());

        mainFrame.lblStatusLeft.setText(
                "Cabeza: Block " + snapshot.getHeadBlock() + " (" + snapshot.getHeadDirection() + ") | Politica: " + snapshot.getPolicy());
        if (snapshot.getSessionSummary() != null) {
            mainFrame.lblStatusCenter.setText(
                    "Sesion: " + snapshot.getSessionSummary().getCurrentUserId() + " / " + snapshot.getSessionSummary().getCurrentRole());
        }

        String statusRight = "Seek total: " + snapshot.getTotalSeekDistance();
        if (recoveryRequired) {
            statusRight += " | Modo: Recovery pendiente";
        }
        mainFrame.lblStatusRight.setText(statusRight);
    }

    private void showAsyncIntentFailurePopups(SimulationSnapshot snapshot, boolean recoveryRequired) {
        if (recoveryRequired) {
            return;
        }

        Set<String> currentTerminatedIds = new HashSet<>();
        for (SimulationSnapshot.ProcessSnapshot process : snapshot.getTerminatedProcessesSnapshot()) {
            currentTerminatedIds.add(process.getProcessId());
            if (!isManualIntentProcess(process)
                    || process.getResultStatus() != ResultStatus.FAILED
                    || notifiedIntentFailures.contains(process.getProcessId())) {
                continue;
            }
            showError(toFriendlyErrorMessage(
                    resolveOperationLabel(process.getOperationType()),
                    process.getErrorMessage()));
            notifiedIntentFailures.add(process.getProcessId());
            break;
        }
        notifiedIntentFailures.retainAll(currentTerminatedIds);
    }

    private boolean isManualIntentProcess(SimulationSnapshot.ProcessSnapshot process) {
        return process.getProcessId() != null && process.getProcessId().startsWith("INTENT-PROC-");
    }

    private String resolveOperationLabel(IoOperationType operationType) {
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

    private void showInfo(String message, String title) {
        JOptionPane.showMessageDialog(mainFrame, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(mainFrame, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private int parseRequiredPositiveInt(String rawValue, String validationMessage) {
        try {
            int value = Integer.parseInt(rawValue.trim());
            if (value <= 0) {
                throw new IllegalArgumentException(validationMessage);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(validationMessage);
        }
    }

    private int countFreeBlocks() {
        return countFreeBlocks(coordinator.getLatestSnapshot());
    }

    private int countFreeBlocks(SimulationSnapshot snapshot) {
        if (snapshot == null) {
            return 0;
        }
        int freeBlocks = 0;
        for (SimulationSnapshot.DiskBlockSummary block : snapshot.getDiskBlocksSnapshot()) {
            if (block.isFree()) {
                freeBlocks++;
            }
        }
        return freeBlocks;
    }

    private boolean isDirectoryPath(String path) {
        if (path == null) {
            return false;
        }
        SimulationSnapshot snapshot = coordinator.getLatestSnapshot();
        if (snapshot == null) {
            return false;
        }
        for (SimulationSnapshot.FileSystemNodeSummary node : snapshot.getFileSystemNodesSnapshot()) {
            if (path.equals(node.getPath())) {
                return node.getType() == FsNodeType.DIRECTORY;
            }
        }
        return false;
    }

    private void showOperationError(String operation, Exception exception) {
        showMappedError(operation, exception, this::toFriendlyErrorMessage);
    }

    private void showAdministrativeError(String action, Exception exception) {
        showMappedError(action, exception, this::toFriendlyAdministrativeErrorMessage);
    }

    private void showMappedError(
            String action,
            Exception exception,
            BiFunction<String, String, String> mapper) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        showError(mapper.apply(action, message));
    }

    private String toFriendlyAdministrativeErrorMessage(String action, String rawMessage) {
        String message = rawMessage == null || rawMessage.isBlank() ? "Error desconocido" : rawMessage;
        String lower = message.toLowerCase();

        if (lower.contains("failed to load scenario")) {
            return "No se pudo cargar el escenario JSON. Verifica que el archivo exista y tenga formato valido.";
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
            return "No se pudo cargar el estado del sistema. El archivo puede estar corrupto o no ser valido.";
        }
        if (lower.contains("failed to save system state")) {
            return "No se pudo guardar el estado del sistema. Verifica permisos y ruta del archivo.";
        }
        if (lower.contains("path cannot be null")) {
            return "Debes seleccionar un archivo valido para completar esta accion.";
        }
        if (lower.contains("coordinator is awaiting recovery")) {
            return "No se puede " + action + " mientras el sistema esta en modo de recovery pendiente.";
        }
        return "No se pudo " + action + ". Verifica los datos e intentalo de nuevo.";
    }

    private void setEnabled(boolean enabled, Component... components) {
        for (Component component : components) {
            component.setEnabled(enabled);
        }
    }

    private String toFriendlyErrorMessage(String operation, String rawMessage) {
        String message = rawMessage == null || rawMessage.isBlank() ? "Error desconocido" : rawMessage;
        String lower = message.toLowerCase();

        if (lower.contains("cannot modify")) {
            return "No tienes permisos para " + operation + " este recurso porque no es tuyo.";
        }
        if (lower.contains("not enough free disk blocks")) {
            return "No hay bloques suficientes disponibles para completar la operacion.";
        }
        if (lower.contains("path does not reference a directory")) {
            return "La ruta seleccionada no es un directorio valido para esta operacion.";
        }
        if (lower.contains("path does not reference a file")) {
            return "La ruta seleccionada no es un archivo. Debes seleccionar un archivo para leer.";
        }
        if (lower.contains("name cannot contain /")
                || lower.contains("name cannot be . or ..")
                || lower.contains("name cannot be blank")) {
            return "El nombre ingresado no es valido. No puede ser vacio, '.', '..' ni contener '/'.";
        }
        if (lower.contains("duplicate child name under parent") || lower.contains("already exists at path")) {
            return "Ya existe un archivo o directorio con ese nombre en la carpeta destino.";
        }
        if (lower.contains("already has requested name")) {
            return "El nombre solicitado ya estaba aplicado. La operacion de renombrado no realizo cambios.";
        }
        if (lower.contains("root cannot be renamed")) {
            return "No se puede renombrar la raiz del sistema de archivos.";
        }
        if (lower.contains("root cannot be deleted")) {
            return "No se puede eliminar la raiz del sistema de archivos.";
        }
        if (lower.contains("node not found at path")) {
            return "La ruta seleccionada ya no existe. Actualiza la vista e intentalo de nuevo.";
        }
        return "Error al " + operation + ": " + message;
    }
}
