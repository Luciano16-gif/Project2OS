package ve.edu.unimet.so.project2.project2os.gui;

import javax.swing.*;
import java.awt.*;
import javax.swing.table.DefaultTableModel;

public class MainFrame extends JFrame {

    public MainFrame() {
        setTitle("Project 2 OS - Simulador");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1024, 768);
        setLocationRelativeTo(null);
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        // --- Barra Superior (Norte) ---
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Modo: Administrador"));
        topPanel.add(Box.createHorizontalStrut(10));
        topPanel.add(new JLabel("Política de Disco:"));
        JComboBox<String> policyCombo = new JComboBox<>(new String[] { "FFIFO", "SSTF", "SCAN", "C-SCAN" });
        topPanel.add(policyCombo);
        topPanel.add(Box.createHorizontalStrut(10));
        topPanel.add(new JButton("Nuevo"));
        topPanel.add(new JButton("Abrir"));
        topPanel.add(new JButton("Guardar"));
        topPanel.add(Box.createHorizontalStrut(10));
        JCheckBox failCheckBox = new JCheckBox("Simular Fallo");
        topPanel.add(failCheckBox);
        add(topPanel, BorderLayout.NORTH);

        // --- Panel Izquierdo (Oeste) ---
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setPreferredSize(new Dimension(250, 0));
        leftPanel.setBorder(BorderFactory.createTitledBorder("Árbol de Directorios"));
        JTree directoryTree = new JTree(); // Placeholder tree
        leftPanel.add(new JScrollPane(directoryTree), BorderLayout.CENTER);

        // --- Panel Central (Centro) ---
        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        centerSplit.setResizeWeight(0.6); // 60% top, 40% bottom

        // Top Central: Bloques de Disco
        JPanel diskPanel = new JPanel(new BorderLayout());
        diskPanel.setBorder(BorderFactory.createTitledBorder("Bloques de Disco"));
        JLabel headLabel = new JLabel("Cabezal", SwingConstants.CENTER); // Placeholder for icon
        diskPanel.add(headLabel, BorderLayout.NORTH);

        // Let's create a grid of blocks for visualization
        JPanel blocksGrid = new JPanel(new GridLayout(10, 15, 2, 2)); // 150 blocks approx
        for (int i = 0; i < 150; i++) {
            JPanel block = new JPanel();
            block.setBackground(new Color(220, 220, 220));
            block.setBorder(BorderFactory.createLineBorder(Color.GRAY));
            blocksGrid.add(block);
        }

        // Wrap the grid in a panel that stays centered/top aligned inside the scroll
        // pane
        JPanel gridWrapper = new JPanel(new BorderLayout());
        gridWrapper.add(blocksGrid, BorderLayout.NORTH);

        diskPanel.add(new JScrollPane(gridWrapper), BorderLayout.CENTER);
        centerSplit.setTopComponent(diskPanel);

        // Bottom Central: Tabla de Asignacion de Archivos
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.setBorder(BorderFactory.createTitledBorder("Tabla de Asignación de Archivos"));
        String[] columns = { "Archivo", "Inicio", "Bloques" };
        Object[][] data = { { "", "", "" }, { "", "", "" }, { "", "", "" } };
        JTable fileTable = new JTable(new DefaultTableModel(data, columns));
        tablePanel.add(new JScrollPane(fileTable), BorderLayout.CENTER);
        centerSplit.setBottomComponent(tablePanel);

        // --- Panel Derecho (Este) ---
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
        rightPanel.setPreferredSize(new Dimension(250, 0));

        // Cola de Procesos
        JPanel processPanel = new JPanel(new BorderLayout());
        processPanel.setBorder(BorderFactory.createTitledBorder("Cola de Procesos"));
        JList<String> processList = new JList<>(new String[] { "Proceso 1", "Proceso 2" });
        processPanel.add(new JScrollPane(processList), BorderLayout.CENTER);
        rightPanel.add(processPanel);

        // Locks
        JPanel locksPanel = new JPanel(new BorderLayout());
        locksPanel.setBorder(BorderFactory.createTitledBorder("Locks"));
        JTextArea locksArea = new JTextArea();
        locksArea.setEditable(false);
        locksPanel.add(new JScrollPane(locksArea), BorderLayout.CENTER);
        rightPanel.add(locksPanel);

        // Log del Sistema
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Log del Sistema"));
        JTextArea logArea = new JTextArea();
        logArea.setEditable(false);
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        rightPanel.add(logPanel);

        // --- SplitPrincipal: Organizacion general ---
        JSplitPane rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerSplit, rightPanel);
        rightSplit.setResizeWeight(0.8);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightSplit);
        mainSplit.setResizeWeight(0.2);

        add(mainSplit, BorderLayout.CENTER);
    }
}
