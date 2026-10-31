package opentrap.extensions;

import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * The "Load extension" dialog: extension type, file, and where the extension's
 * standard output and error go. Returns null when cancelled.
 */
public final class LoadExtensionDialog {

    private LoadExtensionDialog() {}

    public record Result(String type, File file, String outputMode, File outputFile) {}

    public static Result show(Component parent) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog d = new JDialog(owner, "Load extension", Dialog.ModalityType.APPLICATION_MODAL);
        final Result[] out = {null};

        JRadioButton java = Ui.radio("Java", true);
        JRadioButton python = Ui.radio("Python", false);
        JRadioButton ruby = Ui.radio("Ruby", false);
        ButtonGroup types = new ButtonGroup();
        types.add(java); types.add(python); types.add(ruby);

        JTextField file = Ui.field("", 34);
        JButton pick = Ui.button("Select file ...");
        pick.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(file.getText().isBlank() ? System.getProperty("user.home") : file.getText());
            if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) file.setText(fc.getSelectedFile().getAbsolutePath());
        });

        JRadioButton toConsole = Ui.radio("Output to system console", true);
        JRadioButton toFile = Ui.radio("Save to file:", false);
        JRadioButton toUi = Ui.radio("Show in UI", false);
        ButtonGroup outs = new ButtonGroup();
        outs.add(toConsole); outs.add(toFile); outs.add(toUi);
        JTextField outFile = Ui.field("", 26);
        JButton pickOut = Ui.button("Select file ...");
        outFile.setEnabled(false); pickOut.setEnabled(false);
        toFile.addItemListener(e -> { outFile.setEnabled(toFile.isSelected()); pickOut.setEnabled(toFile.isSelected()); });
        pickOut.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showSaveDialog(d) == JFileChooser.APPROVE_OPTION) outFile.setText(fc.getSelectedFile().getAbsolutePath());
        });

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(Ui.pad(16, 20, 10, 20));
        body.add(left(Ui.heading("Extension details")));
        body.add(left(Ui.row(Ui.label("Extension type:"), java, python, ruby)));
        body.add(left(Ui.row(Ui.label("Extension file (.jar, .py, .rb):"), file, pick)));
        body.add(Box.createVerticalStrut(12));
        body.add(left(Ui.heading("Standard output and error")));
        body.add(left(Ui.note("Choose where the extension's standard output and standard error are written.")));
        body.add(left(toConsole));
        body.add(left(Ui.row(toFile, outFile, pickOut)));
        body.add(left(toUi));

        JButton cancel = Ui.button("Cancel");
        JButton next = Ui.primaryButton("Next", "load the extension");
        cancel.addActionListener(e -> d.dispose());
        next.addActionListener(e -> {
            if (file.getText().isBlank()) {
                JOptionPane.showMessageDialog(d, "Choose the extension file.", "Load extension", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String type = python.isSelected() ? "Python" : ruby.isSelected() ? "Ruby" : "Java";
            String mode = toFile.isSelected() ? "file" : toUi.isSelected() ? "ui" : "console";
            out[0] = new Result(type, new File(file.getText().strip()), mode,
                    outFile.getText().isBlank() ? null : new File(outFile.getText().strip()));
            d.dispose();
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        bar.add(cancel);
        bar.add(next);

        JPanel root = new JPanel(new BorderLayout());
        root.add(body, BorderLayout.CENTER);
        root.add(bar, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setMinimumSize(d.getSize());
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
        return out[0];
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (c instanceof JTextArea) Ui.sizeNote((JTextArea) c, 520);
        return c;
    }
}
