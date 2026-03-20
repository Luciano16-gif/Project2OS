package ve.edu.unimet.so.project2.project2os.gui;

import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot.ProcessSnapshot;

import javax.swing.*;
import java.awt.*;

public class ProcessQueuePanel extends JPanel {

    private final JPanel contentPanel;

    public ProcessQueuePanel() {
        setLayout(new BorderLayout());
        setBackground(DarkTheme.BG_PANEL);

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(DarkTheme.BG_PANEL);

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(DarkTheme.BG_PANEL);
        add(scrollPane, BorderLayout.CENTER);
    }

    public void updateFromSnapshot(ProcessSnapshot running, ProcessSnapshot[] ready, ProcessSnapshot[] blocked, ProcessSnapshot[] terminated) {
        contentPanel.removeAll();

        addCategoryHeader("=== LISTOS ===");
        if (ready != null) {
            for (ProcessSnapshot p : ready) {
                addProcessItem(p, DarkTheme.ACCENT_GREEN);
            }
        }

        addCategoryHeader("=== EN CPU ===");
        if (running != null) {
            addProcessItem(running, DarkTheme.ACCENT_BLUE);
        }

        addCategoryHeader("=== BLOQUEADOS ===");
        if (blocked != null) {
            for (ProcessSnapshot p : blocked) {
                addProcessItem(p, DarkTheme.ACCENT_YELLOW);
            }
        }

        addCategoryHeader("=== TERMINADOS ===");
        if (terminated != null) {
            for (ProcessSnapshot p : terminated) {
                addProcessItem(p, DarkTheme.ACCENT_RED);
            }
        }

        contentPanel.revalidate();
        contentPanel.repaint();
    }

    private void addCategoryHeader(String title) {
        JLabel lbl = new JLabel(title, SwingConstants.CENTER);
        lbl.setForeground(DarkTheme.FG_SECONDARY);
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        lbl.setBorder(BorderFactory.createEmptyBorder(10, 0, 5, 0));
        contentPanel.add(lbl);
    }

    private void addProcessItem(ProcessSnapshot p, Color dotColor) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        panel.setBackground(DarkTheme.BG_PANEL);
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        JLabel dot = new JLabel("●");
        dot.setForeground(dotColor);
        dot.setFont(dot.getFont().deriveFont(14f));
        
        JLabel name = new JLabel(p.getProcessId() + " (" + p.getOperationType() + ")");
        name.setForeground(DarkTheme.FG_PRIMARY);
        
        panel.add(dot);
        panel.add(name);
        
        panel.setToolTipText(p.getTargetPath() + " -> " + p.getState());
        contentPanel.add(panel);
    }
}
