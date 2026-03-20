package ve.edu.unimet.so.project2.project2os.gui;

import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot.JournalEntrySummary;

import javax.swing.*;
import java.awt.*;

public class JournalPanel extends JPanel {

    private final JList<String> list;
    private final DefaultListModel<String> listModel;

    public JournalPanel() {
        setLayout(new BorderLayout());
        setBackground(DarkTheme.BG_PANEL);

        listModel = new DefaultListModel<>();
        list = new JList<>(listModel);
        list.setBackground(DarkTheme.BG_PANEL);
        list.setForeground(DarkTheme.FG_PRIMARY);
        list.setSelectionBackground(DarkTheme.ACCENT_BLUE);
        list.setSelectionForeground(DarkTheme.FG_PRIMARY);

        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(DarkTheme.BG_PANEL);
        add(scrollPane, BorderLayout.CENTER);
    }

    public void updateFromSnapshot(JournalEntrySummary[] entries) {
        if (entries == null) return;
        
        listModel.clear();
        for (JournalEntrySummary entry : entries) {
            String text = entry.getOperationType() + " '" + entry.getTargetPath() + "': " + entry.getStatus();
            listModel.addElement(text);
        }
    }
}
