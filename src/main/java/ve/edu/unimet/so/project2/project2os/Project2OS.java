/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package ve.edu.unimet.so.project2.project2os;

import ve.edu.unimet.so.project2.project2os.gui.MainFrame;
import javax.swing.SwingUtilities;

/**
 *
 * @author chano
 */
public class Project2OS {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
