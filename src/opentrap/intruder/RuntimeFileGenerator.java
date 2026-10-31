package opentrap.intruder;

import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.List;

/** Streams payloads from a file, one per line. */
class RuntimeFileGenerator extends AbstractGenerator {

    private final JTextField pathField = Ui.field("");
    private final JLabel countLabel = Ui.label("no file selected");
    private final JPanel panel;
    private int lines = -1;

    RuntimeFileGenerator() {
        pathField.setEditable(false);
        JButton choose = Ui.button("select file ...");
        choose.addActionListener(e -> choose());
        panel = Ui.vbox(Ui.form("File:", Ui.row(pathField, choose)), countLabel);
    }

    private void choose() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(panel) != JFileChooser.APPROVE_OPTION) return;
        pathField.setText(fc.getSelectedFile().getAbsolutePath());
        countLines();
        fire();
    }

    private void countLines() {
        lines = 0;
        try (LineNumberReader r = new LineNumberReader(new FileReader(pathField.getText()))) {
            while (r.readLine() != null) lines++;
            countLabel.setText(lines + " lines");
        } catch (Exception e) {
            lines = -1;
            countLabel.setText("could not read file");
        }
    }

    @Override public String label() { return " runtime file"; }
    @Override public JComponent config() { return panel; }

    @Override public List<String> generate() {
        List<String> out = new ArrayList<>();
        if (pathField.getText().isEmpty()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(pathField.getText()))) {
            String line;
            while ((line = r.readLine()) != null && out.size() < CAP) out.add(line);
        } catch (Exception ignored) {}
        return out;
    }

    @Override public int count() { return lines; }
}
