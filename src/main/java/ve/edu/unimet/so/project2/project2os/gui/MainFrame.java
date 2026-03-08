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

        // ============================================
        // 1. BARRA SUPERIOR (NORTH)
        // ============================================
        JPanel topToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));

        // Bloque Autenticación
        JPanel authPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        authPanel.add(new JLabel("Usuario"));
        JTextField txtUser = new JTextField("admin", 6);
        txtUser.setEditable(false);
        authPanel.add(txtUser);
        authPanel.add(new JLabel("Modo"));
        authPanel.add(new JComboBox<>(new String[] { "ADMIN", "USER" }));
        authPanel.add(new JButton("Aplicar sesion"));
        topToolbar.add(authPanel);

        // Bloque Política
        JPanel policyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        policyPanel.add(new JLabel("Politica"));
        policyPanel.add(new JComboBox<>(new String[] { "SSTF", "FFIFO", "SCAN", "C-SCAN" }));
        policyPanel.add(new JButton("Cambiar"));
        topToolbar.add(policyPanel);

        // Bloque Ejecución
        JPanel execPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        execPanel.add(new JButton("Run"));
        execPanel.add(new JButton("Pause"));
        execPanel.add(new JButton("Step"));
        execPanel.add(new JLabel("||")); // separador visual
        JTextField txtPos = new JTextField("62", 3);
        execPanel.add(txtPos);
        execPanel.add(new JComboBox<>(new String[] { "UP", "DOWN" }));
        execPanel.add(new JButton("Aplicar"));
        topToolbar.add(execPanel);

        // Bloque Sistema
        JPanel sysPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        sysPanel.add(new JButton("Guardar"));
        sysPanel.add(new JButton("Cargar sistema"));
        sysPanel.add(new JButton("Cargar escenario"));
        sysPanel.add(new JButton("Simular fallo"));
        sysPanel.add(new JButton("Recovery"));
        topToolbar.add(sysPanel);

        // Bloque Ajustes
        JPanel adjPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        adjPanel.add(new JLabel("Delay ms"));
        adjPanel.add(new JTextField("500", 4));
        adjPanel.add(new JButton("Velocidad"));
        topToolbar.add(adjPanel);

        add(topToolbar, BorderLayout.NORTH);

        // ============================================
        // 2. ÁREA CENTRAL (JSplitPane Principal)
        // ============================================

        // --- 2.1 PANEL IZQUIERDO (West) ---
        // Arbol superior
        JPanel treePanel = new JPanel(new BorderLayout());
        treePanel.setBorder(BorderFactory.createTitledBorder("Arbol del sistema"));
        JTree directoryTree = new JTree();
        treePanel.add(new JScrollPane(directoryTree), BorderLayout.CENTER);

        // Detalles inferior
        JPanel detailsPanel = new JPanel(new BorderLayout());
        detailsPanel.setBorder(BorderFactory.createTitledBorder("Detalles"));
        JTextArea txtDetails = new JTextArea(
                "ID: N-35\nNombre: data_log.csv_upd_6\nRuta: /system/data_log.csv_upd_6\nTipo: FILE\nOwner: admin\nBloques: 28\nPrimer bloque: 131\nSistema: Si\nLectura publica: Si");
        txtDetails.setEditable(false);
        txtDetails.setOpaque(false);
        detailsPanel.add(txtDetails, BorderLayout.CENTER);

        // Botonera debajo de detalles
        JPanel fileButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fileButtonsPanel.add(new JButton("Crear archivo"));
        fileButtonsPanel.add(new JButton("Crear dir"));
        fileButtonsPanel.add(new JButton("Leer"));
        fileButtonsPanel.add(new JButton("Renombrar"));
        fileButtonsPanel.add(new JButton("Eliminar"));
        detailsPanel.add(fileButtonsPanel, BorderLayout.SOUTH);

        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treePanel, detailsPanel);
        leftSplit.setResizeWeight(0.6); // 60% arbol, 40% detalles
        leftSplit.setMinimumSize(new Dimension(250, 0));

        // --- 2.2 PANEL DERECHO (East) ---

        // 2.2.1 Disco Simulado (Top)
        JPanel diskPanel = new JPanel(new BorderLayout());
        diskPanel.setBorder(BorderFactory.createTitledBorder("Disco simulado"));
        JPanel blocksGrid = new JPanel(new GridLayout(6, 20, 2, 2)); // 120 blocks approx
        for (int i = 0; i < 120; i++) {
            JButton block = new JButton("<html><center>" + i + "<br>O</center></html>");
            block.setMargin(new Insets(1, 1, 1, 1));
            block.setFont(block.getFont().deriveFont(9f));
            if (i < 35)
                block.setBackground(new Color(230, 80, 50)); // Rojo
            else if (i < 70)
                block.setBackground(new Color(200, 200, 200)); // Gris
            else
                block.setBackground(new Color(255, 255, 255)); // Blanco
            blocksGrid.add(block);
        }
        JPanel gridWrapper = new JPanel(new BorderLayout());
        gridWrapper.add(blocksGrid, BorderLayout.NORTH);
        diskPanel.add(new JScrollPane(gridWrapper), BorderLayout.CENTER);

        // 2.2.2 Tabla de Asignación (Middle Top)
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.setBorder(BorderFactory.createTitledBorder("Tabla de asignacion"));
        String[] colAlloc = { "Archivo", "Owner", "Bloques", "Primer bloque", "Color", "Ruta" };
        JTable tableAlloc = new JTable(new DefaultTableModel(new Object[][] {
                { "boot_sect.bin", "admin", "2", "11", "#50A14F", "/system/boot_sect.bin" }
        }, colAlloc));
        tablePanel.add(new JScrollPane(tableAlloc), BorderLayout.CENTER);

        // 2.2.3 Procesos, locks y journal (Middle Bottom)
        JPanel procPanel = new JPanel(new GridLayout(1, 2, 5, 0)); // 2 tables side by side
        procPanel.setBorder(BorderFactory.createTitledBorder("Procesos, locks y journal"));

        String[] colProc = { "PID", "Operacion", "Objetivo", "Estado", "Lock", "Bloque", "Usuario", "Resultado",
                "Espera" };
        JTable tableProc = new JTable(new DefaultTableModel(new Object[][] {
                { "P-13", "READ", "/system/bo...", "TERMINAT...", "SHARED", "11", "admin", "SUCCESS", "NONE" }
        }, colProc));
        procPanel.add(new JScrollPane(tableProc));

        String[] colFileLock = { "Archivo", "Tipo", "Owners", "Espera" };
        JTable tableFileLock = new JTable(new DefaultTableModel(new Object[][] {}, colFileLock));
        procPanel.add(new JScrollPane(tableFileLock));

        // 2.2.4 Log/Tx (Bottom)
        JPanel logPanel = new JPanel(new BorderLayout());
        // split log in two parts: tx table and text area
        String[] colTx = { "TX", "Operacion", "Ruta", "Estado", "Descripcion" };
        JTable tableTx = new JTable(new DefaultTableModel(new Object[][] {
                { "TX-3", "DELETE", "/system/image_01.png", "COMMITTED", "DELETE /system/image_01.png" }
        }, colTx));

        JTextArea txtLog = new JTextArea(
                "proceso P-17 finalizado: UPDATE completado\nDespachado P-16 con politica SSTF...");
        txtLog.setEditable(false);
        txtLog.setBackground(new Color(245, 245, 245));

        JSplitPane bottomLogSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tableTx),
                new JScrollPane(txtLog));
        bottomLogSplit.setResizeWeight(0.6);
        logPanel.add(bottomLogSplit, BorderLayout.CENTER);

        // Anidar splits Derechos
        JSplitPane rightSplit3 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, procPanel, logPanel);
        rightSplit3.setResizeWeight(0.7);

        JSplitPane rightSplit2 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePanel, rightSplit3);
        rightSplit2.setResizeWeight(0.5);

        JSplitPane rightSplit1 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, diskPanel, rightSplit2);
        rightSplit1.setResizeWeight(0.4);

        // Split final
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, rightSplit1);
        mainSplit.setResizeWeight(0.2); // 20% izquierda, 80% derecha

        add(mainSplit, BorderLayout.CENTER);

        // ============================================
        // 3. BARRA DE ESTADO (SOUTH)
        // ============================================
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));

        JLabel lblStatusLeft = new JLabel(
                "Pausa | cabeza 62 | politica SSTF | delay 500 ms | ultimo despacho P-15 -> /system/script.py");
        JLabel lblStatusCenter = new JLabel("Sesion: admin / ADMIN", SwingConstants.CENTER);
        JLabel lblStatusRight = new JLabel("Disco: libres 99 de 200    Seek total 127 | ultimo 8");

        statusBar.add(lblStatusLeft, BorderLayout.WEST);
        statusBar.add(lblStatusCenter, BorderLayout.CENTER);
        statusBar.add(lblStatusRight, BorderLayout.EAST);

        add(statusBar, BorderLayout.SOUTH);
    }
}
