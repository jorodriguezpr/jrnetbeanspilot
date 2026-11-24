// JR NetBeans Pilot (jrnetbeanspilot)
// Copyright 2026 Jose Rodriguez Arroyo
// Developer: Jose Rodriguez Arroyo <jrpcone@gmail.com>
// Website: https://microrepair.net | GitHub: https://github.com/jorodriguezpr
// Project: https://github.com/jorodriguezpr/jrnetbeanspilot
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// SPDX-License-Identifier: Apache-2.0
package net.microrepair.jrnetbeanspilot.options;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.prefs.Preferences;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import net.microrepair.jrnetbeanspilot.Settings;
import org.openide.util.NbPreferences;

/** Tools > Options > Miscellaneous > Copilot App. */
final class PilotOptionsPanel extends JPanel {

    private final JTextField exe = new JTextField(40);
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(180, 20, 1800, 10));
    private final JCheckBox newChat = new JCheckBox("Start a new Copilot chat for every question (otherwise follow-ups keep the context)");
    private final JCheckBox includeSel = new JCheckBox("Send the selected code (with file name and language) along with the question");
    private final JTextField instruction = new JTextField(40);
    private final JComboBox<String> insertMode = new JComboBox<>(new String[]{"auto", "clipboard"});
    private final JComboBox<String> multiple = new JComboBox<>(new String[]{"pick", "all", "first"});
    private final JComboBox<String> restart = new JComboBox<>(new String[]{"ask", "always", "never"});

    PilotOptionsPanel() {
        super(new GridBagLayout());
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(exe.getText());
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                exe.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        int y = 0;
        row(y++, "Copilot app (copilotapp.exe):", exe, browse);
        row(y++, "Answer timeout (seconds):", timeout, null);
        row(y++, "Instruction added to code questions:", instruction, null);
        row(y++, "Insert mode:", insertMode, new JLabel("auto = replace the selection / insert at the cursor; clipboard = only copy"));
        row(y++, "Several code blocks:", multiple, new JLabel("pick = ask, all = join them, first = the first one"));
        row(y++, "Copilot closed / not readable:", restart, new JLabel("ask, always or never restart it with accessibility on"));
        full(y++, includeSel);
        full(y++, newChat);
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = y;
        filler.weighty = 1;
        add(new JLabel(), filler);
    }

    private void row(int y, String label, java.awt.Component field, java.awt.Component extra) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(4, 8, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        add(field, c);
        if (extra != null) {
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            add(extra, c);
        }
    }

    private void full(int y, java.awt.Component comp) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.gridwidth = 3;
        c.insets = new Insets(4, 8, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        add(comp, c);
    }

    void load() {
        exe.setText(Settings.copilotExe());
        timeout.setValue(Settings.timeoutSeconds());
        instruction.setText(Settings.codeInstruction());
        insertMode.setSelectedItem(Settings.insertMode());
        multiple.setSelectedItem(Settings.multipleBlocks());
        restart.setSelectedItem(Settings.autoRestartCopilot());
        includeSel.setSelected(Settings.includeSelection());
        newChat.setSelected(Settings.newChatEachQuestion());
    }

    void store() {
        Preferences p = NbPreferences.forModule(Settings.class);
        p.put("copilotExe", exe.getText().trim().isEmpty() ? Settings.DEFAULT_EXE : exe.getText().trim());
        p.putInt("timeoutSeconds", (Integer) timeout.getValue());
        p.put("codeInstruction", instruction.getText());
        p.put("insertMode", (String) insertMode.getSelectedItem());
        p.put("multipleBlocks", (String) multiple.getSelectedItem());
        p.put("autoRestartCopilot", (String) restart.getSelectedItem());
        p.putBoolean("includeSelection", includeSel.isSelected());
        p.putBoolean("newChatEachQuestion", newChat.isSelected());
    }

    boolean changed() {
        return !exe.getText().trim().equals(Settings.copilotExe())
                || !timeout.getValue().equals(Settings.timeoutSeconds())
                || !instruction.getText().equals(Settings.codeInstruction())
                || !Settings.insertMode().equals(insertMode.getSelectedItem())
                || !Settings.multipleBlocks().equals(multiple.getSelectedItem())
                || !Settings.autoRestartCopilot().equals(restart.getSelectedItem())
                || includeSel.isSelected() != Settings.includeSelection()
                || newChat.isSelected() != Settings.newChatEachQuestion();
    }
}
