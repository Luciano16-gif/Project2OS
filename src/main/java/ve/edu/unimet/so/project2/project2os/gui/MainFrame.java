package ve.edu.unimet.so.project2.project2os.gui;

import javax.swing.*;
import java.awt.*;

public class MainFrame extends JFrame {

    private JButton btnAdmin, btnUser;
    private JComboBox<String> comboPolicy;
    private JButton btnPolicyChange;
    private JButton btnSaveSystem, btnLoadSystem, btnLoadScenario;
    private JButton btnPlay, btnPause, btnStep;
    private JButton btnAdvanced;
    private JComboBox<String> comboPlaybackSpeed;
    private JLabel lblCycle;

    private JButton btnCreateFile, btnCreateDir, btnRead, btnRename, btnDelete, btnStats;
    private JButton btnSimularFallo, btnRecovery;
    private JLabel lblSystemState;

    private FileSystemTreePanel fileSystemTreePanel;
    private DiskVisualizationPanel diskVisualizationPanel;
    private ProcessQueuePanel processQueuePanel;
    private JournalPanel journalPanel;
    private EventLogPanel eventLogPanel;

    private JLabel lblStatusLeft, lblStatusCenter, lblStatusRight;

    public MainFrame() {
        setTitle("Simulador de Sistema de Archivos - SO 2425-2");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setBackground(DarkTheme.BG_DARK);
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        // TOP TOOLBAR
        JPanel topToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
        topToolbar.setBackground(DarkTheme.BG_HEADER);

        JPanel authPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        authPanel.setOpaque(false);
        JLabel lblAuth = new JLabel("Controles   ");
        lblAuth.setForeground(DarkTheme.FG_PRIMARY);
        authPanel.add(lblAuth);
        
        btnAdmin = DarkTheme.styledButton("Administrador");
        btnUser = DarkTheme.styledButton("Usuario");
        authPanel.add(btnAdmin);
        authPanel.add(btnUser);
        topToolbar.add(authPanel);

        btnCreateFile = DarkTheme.styledButton("Crear Archivo");
        btnCreateDir = DarkTheme.styledButton("Crear Directorio");
        btnRead = DarkTheme.styledButton("Leer");
        btnRename = DarkTheme.styledButton("Renombrar");
        btnDelete = DarkTheme.styledButton("Eliminar");
        btnStats = DarkTheme.styledButton("Estadísticas");
        btnSaveSystem = DarkTheme.styledButton("Guardar");
        btnLoadSystem = DarkTheme.styledButton("Cargar");
        btnLoadScenario = DarkTheme.styledButton("Escenario JSON");
        topToolbar.add(btnCreateFile);
        topToolbar.add(btnCreateDir);
        topToolbar.add(btnRead);
        topToolbar.add(btnRename);
        topToolbar.add(btnDelete);
        topToolbar.add(btnStats);
        topToolbar.add(btnSaveSystem);
        topToolbar.add(btnLoadSystem);
        topToolbar.add(btnLoadScenario);

        btnPlay = DarkTheme.styledButton("▶");
        btnPlay.setForeground(DarkTheme.ACCENT_GREEN);
        btnPause = DarkTheme.styledButton("⏸");
        btnPause.setForeground(DarkTheme.ACCENT_RED);
        btnStep = DarkTheme.styledButton("Step by Step");
        comboPlaybackSpeed = new JComboBox<>(new String[]{
            "Instantáneo",
            "Rápido (100ms)", 
            "Medio (500ms)", 
            "Lento (1 Seg)"
        });
        comboPlaybackSpeed.setSelectedItem("Instantáneo");
        topToolbar.add(btnPlay);
        topToolbar.add(btnPause);
        topToolbar.add(btnStep);
        topToolbar.add(new JLabel("Velocidad:"));
        topToolbar.add(comboPlaybackSpeed);

        lblCycle = new JLabel("Reproduciendo (Instantáneo)");
        lblCycle.setForeground(DarkTheme.FG_PRIMARY);
        topToolbar.add(Box.createHorizontalStrut(50));
        topToolbar.add(lblCycle);

        add(topToolbar, BorderLayout.NORTH);

        // ----------------- CENTER LAYOUT -----------------
        fileSystemTreePanel = new FileSystemTreePanel();
        diskVisualizationPanel = new DiskVisualizationPanel(100);
        journalPanel = new JournalPanel();
        processQueuePanel = new ProcessQueuePanel();
        eventLogPanel = new EventLogPanel();

        JPanel leftPanel = DarkTheme.styledPanel("Sistema de Archivos");
        leftPanel.setLayout(new BorderLayout());
        leftPanel.add(fileSystemTreePanel, BorderLayout.CENTER);
        
        JPanel logContainer = DarkTheme.styledPanel("Log de Eventos");
        logContainer.setLayout(new BorderLayout());
        logContainer.add(eventLogPanel, BorderLayout.CENTER);
        JButton btnClearLog = DarkTheme.styledButton("Limpiar");
        JPanel logBottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        logBottom.setOpaque(false);
        logBottom.add(btnClearLog);
        logContainer.add(logBottom, BorderLayout.SOUTH);
        
        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, leftPanel, logContainer);
        leftSplit.setResizeWeight(0.65);
        leftSplit.setDividerSize(4);

        JPanel diskContainer = DarkTheme.styledPanel("Disk Visualization");
        diskContainer.setLayout(new BorderLayout());
        
        JPanel policyOverlay = new JPanel(new FlowLayout(FlowLayout.LEFT));
        policyOverlay.setOpaque(false);
        comboPolicy = new JComboBox<>(new String[]{"FIFO", "SSTF", "SCAN", "C_SCAN"});
        btnPolicyChange = DarkTheme.styledButton("Aplicar Política");
        btnAdvanced = DarkTheme.styledButton("Avanzado");
        JLabel lblPlan = new JLabel("Planificador:");
        lblPlan.setForeground(DarkTheme.FG_PRIMARY);
        policyOverlay.add(lblPlan);
        policyOverlay.add(comboPolicy);
        policyOverlay.add(btnPolicyChange);
        policyOverlay.add(btnAdvanced);
        diskContainer.add(policyOverlay, BorderLayout.NORTH);
        diskContainer.add(diskVisualizationPanel, BorderLayout.CENTER);

        JPanel rightTop = DarkTheme.styledPanel("Journal");
        rightTop.setLayout(new BorderLayout());
        rightTop.add(journalPanel, BorderLayout.CENTER);
        
        JPanel rightTopBottom = new JPanel(new GridLayout(2, 1, 0, 5));
        rightTopBottom.setOpaque(false);
        rightTopBottom.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        btnSimularFallo = DarkTheme.styledButton("Simular Fallo");
        lblSystemState = new JLabel("Estado del Sistema: Normal", SwingConstants.CENTER);
        lblSystemState.setForeground(DarkTheme.FG_PRIMARY);
        rightTopBottom.add(btnSimularFallo);
        rightTopBottom.add(lblSystemState);
        rightTop.add(rightTopBottom, BorderLayout.SOUTH);

        JPanel rightBottom = DarkTheme.styledPanel("Cola de Procesos");
        rightBottom.setLayout(new BorderLayout());
        rightBottom.add(processQueuePanel, BorderLayout.CENTER);

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, rightTop, rightBottom);
        rightSplit.setResizeWeight(0.5);
        rightSplit.setDividerSize(4);

        JSplitPane centerRightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, diskContainer, rightSplit);
        centerRightSplit.setResizeWeight(0.7);
        centerRightSplit.setDividerSize(4);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, centerRightSplit);
        mainSplit.setResizeWeight(0.20);
        mainSplit.setDividerSize(4);

        add(mainSplit, BorderLayout.CENTER);

        // STATUS BAR
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(DarkTheme.BG_HEADER);
        statusBar.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));

        lblStatusLeft = new JLabel("Cargando...");
        lblStatusCenter = new JLabel("Sesión...", SwingConstants.CENTER);
        lblStatusRight = new JLabel("Cargando...");
        lblStatusLeft.setForeground(DarkTheme.FG_PRIMARY);
        lblStatusCenter.setForeground(DarkTheme.FG_PRIMARY);
        lblStatusRight.setForeground(DarkTheme.FG_PRIMARY);

        statusBar.add(lblStatusLeft, BorderLayout.WEST);
        statusBar.add(lblStatusCenter, BorderLayout.CENTER);
        JPanel rightStatusContainer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightStatusContainer.setOpaque(false);
        rightStatusContainer.add(lblStatusRight);
        btnRecovery = DarkTheme.styledButton("Recovery");
        rightStatusContainer.add(btnRecovery);
        statusBar.add(rightStatusContainer, BorderLayout.EAST);

        add(statusBar, BorderLayout.SOUTH);

        btnClearLog.addActionListener(e -> eventLogPanel.clearLog());
    }

    public JButton getBtnAdmin() { return btnAdmin; }
    public JButton getBtnUser() { return btnUser; }
    public JComboBox<String> getComboPolicy() { return comboPolicy; }
    public JButton getBtnPolicyChange() { return btnPolicyChange; }
    public JButton getBtnSaveSystem() { return btnSaveSystem; }
    public JButton getBtnLoadSystem() { return btnLoadSystem; }
    public JButton getBtnLoadScenario() { return btnLoadScenario; }
    public JButton getBtnPlay() { return btnPlay; }
    public JButton getBtnPause() { return btnPause; }
    public JButton getBtnStep() { return btnStep; }
    public JButton getBtnAdvanced() { return btnAdvanced; }
    public JComboBox<String> getComboPlaybackSpeed() { return comboPlaybackSpeed; }
    public JLabel getLblCycle() { return lblCycle; }
    public JButton getBtnCreateFile() { return btnCreateFile; }
    public JButton getBtnCreateDir() { return btnCreateDir; }
    public JButton getBtnRead() { return btnRead; }
    public JButton getBtnRename() { return btnRename; }
    public JButton getBtnDelete() { return btnDelete; }
    public JButton getBtnStats() { return btnStats; }
    public JButton getBtnSimularFallo() { return btnSimularFallo; }
    public JButton getBtnRecovery() { return btnRecovery; }
    public JLabel getLblSystemState() { return lblSystemState; }

    public FileSystemTreePanel getFileSystemTreePanel() { return fileSystemTreePanel; }
    public DiskVisualizationPanel getDiskVisualizationPanel() { return diskVisualizationPanel; }
    public ProcessQueuePanel getProcessQueuePanel() { return processQueuePanel; }
    public JournalPanel getJournalPanel() { return journalPanel; }
    public EventLogPanel getEventLogPanel() { return eventLogPanel; }

    public JLabel getLblStatusLeft() { return lblStatusLeft; }
    public JLabel getLblStatusCenter() { return lblStatusCenter; }
    public JLabel getLblStatusRight() { return lblStatusRight; }
}
