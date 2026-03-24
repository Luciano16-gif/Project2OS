package ve.edu.unimet.so.project2.project2os.gui;

import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot.EventLogEntrySummary;

import javax.swing.*;
import java.awt.*;

public class EventLogPanel extends JPanel {

    private final JTextArea textArea;
    private final JScrollPane scrollPane;
    private long lastSeenSequence = -1;
    private static final String CATEGORY_PLAYBACK = "PLAYBACK";

    public EventLogPanel() {
        setLayout(new BorderLayout());
        setBackground(DarkTheme.BG_PANEL);

        textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setBackground(DarkTheme.BG_DARK);
        textArea.setForeground(DarkTheme.ACCENT_GREEN);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setMargin(new Insets(5, 5, 5, 5));

        scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        
        add(scrollPane, BorderLayout.CENTER);
    }

    public void updateFromSnapshot(EventLogEntrySummary[] entries) {
        if (entries == null) return;

        if (entries.length == 0) {
            if (lastSeenSequence >= 0) {
                textArea.setText("");
            }
            lastSeenSequence = -1;
            return;
        }

        // Backend reinitializations can reset sequence numbering; restart local tracking to avoid a dead panel.
        if (entries[entries.length - 1].getSequenceNumber() < lastSeenSequence) {
            textArea.setText("");
            lastSeenSequence = -1;
        }

        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        boolean isAtBottom = (vertical.getValue() + vertical.getVisibleAmount() >= vertical.getMaximum() - 5);

        boolean added = false;
        StringBuilder sb = new StringBuilder();
        for (EventLogEntrySummary entry : entries) {
            if (entry.getSequenceNumber() <= lastSeenSequence) {
                continue;
            }
            lastSeenSequence = entry.getSequenceNumber();
            if (CATEGORY_PLAYBACK.equalsIgnoreCase(entry.getCategory())) {
                continue;
            }
            sb.append(entry.getCategory())
              .append(" (").append(entry.getTick()).append("): ")
              .append(entry.getMessage()).append("\n");
            added = true;
        }
        
        if (added) {
            textArea.append(sb.toString());
            if (isAtBottom) {
                SwingUtilities.invokeLater(() -> vertical.setValue(vertical.getMaximum()));
            }
        }
    }
    
    public void clearLog() {
        textArea.setText("");
    }
}
