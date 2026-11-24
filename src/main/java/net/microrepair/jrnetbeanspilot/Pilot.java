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
package net.microrepair.jrnetbeanspilot;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import javax.swing.text.StyledDocument;
import net.microrepair.jrnetbeanspilot.CodeText.Block;
import org.netbeans.api.editor.EditorRegistry;
import org.netbeans.api.progress.ProgressHandle;
import org.openide.DialogDescriptor;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.StatusDisplayer;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.text.NbDocument;
import org.openide.util.RequestProcessor;
import org.openide.windows.IOProvider;
import org.openide.windows.InputOutput;
import org.openide.windows.TopComponent;

/** Everything the actions do: ask Copilot, pick a code block, insert it into the editor. */
public final class Pilot {

    private static final RequestProcessor RP = new RequestProcessor("jrnetbeanspilot", 1);
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile Answer lastAnswer;
    private static String lastQuestion = "";

    private Pilot() {
    }

    /** One answer from Copilot. */
    static final class Answer {
        final String text;
        final List<Block> blocks;

        Answer(String text, List<Block> blocks) {
            this.text = text;
            this.blocks = blocks;
        }
    }

    /** Where the code goes, captured when the action starts (positions follow later edits). */
    static final class Target {
        JTextComponent component;
        Document doc;
        Position start;
        Position end;
        String selection = "";
        String fileName = "";
        String language = "";
    }

    // ---------------------------------------------------------------- actions

    /** Ask and insert code ({@code withCode}) or ask and show the answer. Called on the EDT. */
    public static void ask(boolean withCode) {
        if (BUSY.get()) {
            status("Copilot App: a question is already running.");
            return;
        }
        Target t = captureTarget();
        boolean hasSel = t != null && !t.selection.isBlank() && Settings.includeSelection();
        String hint = hasSel
                ? "The selected code (" + t.selection.split("\n", -1).length + " lines) is sent too."
                : (withCode ? (t == null ? "No editor is open: the code goes to the clipboard." : "The code goes in at the cursor.")
                        : "The answer opens in the \"Copilot App\" output window.");
        String question = askQuestion(withCode ? "Ask the Copilot app for code" : "Ask the Copilot app", hint);
        if (question == null || question.isBlank()) {
            return;
        }
        String prompt = CodeText.buildPrompt(question,
                hasSel ? t.selection : "",
                t == null ? "" : t.fileName,
                t == null ? "" : t.language,
                withCode ? Settings.codeInstruction() : "");
        RP.post(() -> runAsk(prompt, withCode, t));
    }

    /** Insert the last answer's code again, at the current cursor. Called on the EDT. */
    public static void insertLast() {
        Answer a = lastAnswer;
        if (a == null) {
            status("Copilot App: no answer yet.");
            return;
        }
        Target t = captureTarget();
        Block b = chooseBlock(a);
        if (b != null) {
            insert(t, b);
        }
    }

    public static void newChat() {
        RP.post(() -> withProgress("Starting a new chat", h -> {
            if (ensureReady(h)) {
                Bridge.run("newchat", List.of(), Settings.copilotExe(), null, h.bridge);
                status("Copilot App: new chat started.");
            }
        }));
    }

    public static void launch() {
        RP.post(() -> withProgress("Restarting Copilot", h -> {
            Bridge.run("launch", List.of(), Settings.copilotExe(), h::progress, h.bridge);
            status("Copilot App: ready.");
        }));
    }

    public static void checkStatus() {
        RP.post(() -> withProgress("Checking Copilot", h -> {
            Map<String, Object> st = Bridge.run("status", List.of(), Settings.copilotExe(), null, h.bridge);
            boolean running = Boolean.TRUE.equals(st.get("running"));
            boolean accessible = Boolean.TRUE.equals(st.get("accessible"));
            String msg = !running ? "The Copilot app is not running."
                    : accessible ? "The Copilot app is running and readable - ready."
                    : "The Copilot app is running WITHOUT accessibility, so its chat cannot be read.\nUse Tools > Copilot App > Restart Copilot with Accessibility.";
            DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(msg,
                    running && accessible ? NotifyDescriptor.INFORMATION_MESSAGE : NotifyDescriptor.WARNING_MESSAGE));
        }));
    }

    // ---------------------------------------------------------------- asking

    private static void runAsk(String prompt, boolean withCode, Target t) {
        withProgress("Asking Copilot", h -> {
            if (!ensureReady(h)) {
                return;
            }
            Path promptFile = Files.createTempFile("jrnetbeanspilot-", ".txt");
            try {
                Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);
                log("\n=== " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " question ===\n" + prompt);
                List<String> args = new ArrayList<>(List.of("-PromptFile", promptFile.toString(),
                        "-TimeoutSec", String.valueOf(Settings.timeoutSeconds())));
                if (Settings.newChatEachQuestion()) {
                    args.add("-NewChat");
                }
                Map<String, Object> res = Bridge.run("ask", args, Settings.copilotExe(), h::progress, h.bridge);
                String text = res.get("text") == null ? "" : String.valueOf(res.get("text")).replace("\r\n", "\n");
                List<Block> blocks = CodeText.normaliseBlocks(res.get("blocks"));
                if (blocks.isEmpty()) {
                    blocks = CodeText.extractFencedBlocks(text);
                }
                Answer a = new Answer(text, blocks);
                lastAnswer = a;
                Object ms = res.get("elapsedMs");
                log(String.format("=== answer (%.1f s, %d code block(s)) ===%n%s",
                        ms instanceof Double ? (Double) ms / 1000 : 0.0, blocks.size(), text));
                h.finish();
                SwingUtilities.invokeLater(() -> {
                    if (!withCode) {
                        showAnswer();
                        return;
                    }
                    Block b = chooseBlock(a);
                    if (b != null) {
                        insert(t, b);
                    }
                });
            } finally {
                Files.deleteIfExists(promptFile);
            }
        });
    }

    /** Make sure Copilot runs with accessibility; may ask to (re)start it. False = user cancelled. */
    private static boolean ensureReady(Progress h) throws Bridge.BridgeException {
        Map<String, Object> st = Bridge.run("status", List.of(), Settings.copilotExe(), null, h.bridge);
        boolean running = Boolean.TRUE.equals(st.get("running"));
        if (running && Boolean.TRUE.equals(st.get("accessible"))) {
            return true;
        }
        String mode = Settings.autoRestartCopilot();
        if ("never".equals(mode)) {
            throw new Bridge.BridgeException("NOT_READY", running
                    ? "The Copilot app is running without accessibility. Use Tools > Copilot App > Restart Copilot with Accessibility."
                    : "The Copilot app is not running.");
        }
        if ("ask".equals(mode)) {
            String msg = running
                    ? "The Copilot app is running, but its chat cannot be read until it is restarted with accessibility on.\nRestart it now? (Your chats are kept.)"
                    : "The Copilot app is not running. Start it with accessibility on?";
            NotifyDescriptor nd = new NotifyDescriptor.Confirmation(msg, "Copilot App", NotifyDescriptor.OK_CANCEL_OPTION);
            if (DialogDisplayer.getDefault().notify(nd) != NotifyDescriptor.OK_OPTION) {
                return false;
            }
        }
        Bridge.run("launch", List.of(), Settings.copilotExe(), h::progress, h.bridge);
        return true;
    }

    // ---------------------------------------------------------------- progress / errors

    /** Progress bar in the status line, with Cancel. */
    static final class Progress {
        final Bridge.Handle bridge = new Bridge.Handle();
        final ProgressHandle handle;
        private boolean finished;

        Progress(String title) {
            handle = ProgressHandle.createHandle("Copilot App: " + title, () -> {
                bridge.cancel();
                return true;
            });
        }

        void progress(String msg) {
            log("  " + msg);
            if (!finished) {
                handle.progress(msg);
            }
        }

        synchronized void finish() {
            if (!finished) {
                finished = true;
                handle.finish();
            }
        }
    }

    interface Work {
        void run(Progress h) throws Exception;
    }

    private static void withProgress(String title, Work w) {
        if (!BUSY.compareAndSet(false, true)) {
            status("Copilot App: a question is already running.");
            return;
        }
        Progress h = new Progress(title);
        try {
            h.handle.start();
            h.handle.switchToIndeterminate();
            w.run(h);
        } catch (Bridge.BridgeException e) {
            if (!"CANCELLED".equals(e.code)) {
                log("ERROR " + e.code + ": " + e.getMessage());
                DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                        "Copilot App: " + e.getMessage() + "\n\n(" + e.code + " - details in Window > Output > Copilot App)",
                        NotifyDescriptor.ERROR_MESSAGE));
            } else {
                log("Cancelled.");
            }
        } catch (Exception e) {
            log("ERROR: " + e);
            DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message("Copilot App: " + e.getMessage(),
                    NotifyDescriptor.ERROR_MESSAGE));
        } finally {
            h.finish();
            BUSY.set(false);
        }
    }

    // ---------------------------------------------------------------- editor

    /** The editor that had the focus last, with its selection/cursor; null when none is open. */
    static Target captureTarget() {
        JTextComponent c = EditorRegistry.lastFocusedComponent();
        if (c == null) {
            return null;
        }
        Target t = new Target();
        t.component = c;
        t.doc = c.getDocument();
        int s = Math.min(c.getSelectionStart(), c.getSelectionEnd());
        int e = Math.max(c.getSelectionStart(), c.getSelectionEnd());
        try {
            t.start = NbDocument.createPosition(t.doc, s, Position.Bias.Backward);
            t.end = NbDocument.createPosition(t.doc, e, Position.Bias.Forward);
            t.selection = e > s ? t.doc.getText(s, e - s) : "";
        } catch (BadLocationException ex) {
            return null;
        }
        Object sd = t.doc.getProperty(Document.StreamDescriptionProperty);
        String ext = "";
        String mime = (String) t.doc.getProperty("mimeType");
        if (sd instanceof DataObject) {
            FileObject fo = ((DataObject) sd).getPrimaryFile();
            t.fileName = fo.getNameExt();
            ext = fo.getExt();
            if (mime == null) {
                mime = fo.getMIMEType();
            }
        }
        t.language = CodeText.languageOf(mime, ext);
        return t;
    }

    /** Replace the captured selection / insert at the captured cursor, as one undo step. EDT only. */
    static void insert(Target t, Block block) {
        if (t == null || "clipboard".equals(Settings.insertMode())) {
            copy(block.code, t == null ? "Copilot App: no editor open - the code is on the clipboard."
                    : "Copilot App: the code is on the clipboard.");
            return;
        }
        Document doc = t.doc;
        int start = t.start.getOffset();
        int end = Math.max(start, t.end.getOffset());
        String code;
        try {
            Element root = doc.getDefaultRootElement();
            Element line = root.getElement(root.getElementIndex(start));
            String lineText = doc.getText(line.getStartOffset(), Math.max(0, line.getEndOffset() - line.getStartOffset()))
                    .replace("\n", "");
            code = CodeText.fitIndent(block.code, lineText, start - line.getStartOffset(), end > start);
        } catch (BadLocationException ex) {
            code = block.code;
        }
        final String text = code;
        final boolean[] ok = {false};
        Runnable edit = () -> {
            try {
                if (end > start) {
                    doc.remove(start, end - start);
                }
                doc.insertString(start, text, null);
                ok[0] = true;
            } catch (BadLocationException ex) {
                log("Insert failed: " + ex.getMessage());
            }
        };
        try {
            if (doc instanceof StyledDocument) {
                NbDocument.runAtomicAsUser((StyledDocument) doc, edit);
            } else {
                edit.run();
            }
        } catch (BadLocationException ex) {
            log("Insert failed (read-only or guarded text): " + ex.getMessage());
        }
        if (!ok[0]) {
            copy(block.code, "Copilot App: could not edit the file - the code is on the clipboard.");
            return;
        }
        JTextComponent c = t.component;
        TopComponent tc = (TopComponent) SwingUtilities.getAncestorOfClass(TopComponent.class, c);
        if (tc != null) {
            tc.requestActive();
        }
        if (c.getDocument() == doc) {
            c.select(start, start + text.length());
            c.requestFocusInWindow();
        }
        status("Copilot App: code inserted (" + block.lineCount() + " lines). Ctrl+Z undoes it.");
    }

    // ---------------------------------------------------------------- dialogs

    /** Multi-line question box: Enter sends, Shift+Enter adds a line. Null when cancelled. */
    private static String askQuestion(String title, String hint) {
        JTextArea area = new JTextArea(lastQuestion, 4, 60);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.selectAll();
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        p.add(new JLabel("<html>" + escape(hint) + "<br><small>Enter = send, Shift+Enter = new line. "
                + "Example: write a PHP function that validates an email address.</small></html>"), BorderLayout.NORTH);
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        DialogDescriptor dd = new DialogDescriptor(p, title, true, DialogDescriptor.OK_CANCEL_OPTION,
                DialogDescriptor.OK_OPTION, null);
        Dialog dlg = DialogDisplayer.getDefault().createDialog(dd);
        area.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "jrpilot-send");
        area.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), DefaultEditorKit.insertBreakAction);
        area.getActionMap().put("jrpilot-send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dd.setValue(DialogDescriptor.OK_OPTION);
                dlg.setVisible(false);
            }
        });
        dlg.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                area.requestFocusInWindow();
            }
        });
        dlg.setVisible(true);
        dlg.dispose();
        if (dd.getValue() != DialogDescriptor.OK_OPTION) {
            return null;
        }
        lastQuestion = area.getText().trim();
        return lastQuestion;
    }

    /** The block to insert (asks when there are several). Null = nothing. EDT only. */
    static Block chooseBlock(Answer a) {
        List<Block> blocks = a.blocks;
        if (blocks.isEmpty()) {
            Object insertAll = "Insert whole answer";
            Object show = "Show answer";
            NotifyDescriptor nd = new NotifyDescriptor("Copilot App: the answer has no code block.", "Copilot App",
                    NotifyDescriptor.DEFAULT_OPTION, NotifyDescriptor.WARNING_MESSAGE,
                    new Object[]{insertAll, show, NotifyDescriptor.CANCEL_OPTION}, show);
            Object r = DialogDisplayer.getDefault().notify(nd);
            if (r == insertAll) {
                return new Block("", a.text);
            }
            if (r == show) {
                showAnswer();
            }
            return null;
        }
        if (blocks.size() == 1) {
            return blocks.get(0);
        }
        Block all = new Block(blocks.get(0).language, joinAll(blocks));
        String mode = Settings.multipleBlocks();
        if ("first".equals(mode)) {
            return blocks.get(0);
        }
        if ("all".equals(mode)) {
            return all;
        }
        DefaultListModel<Object> model = new DefaultListModel<>();
        for (int k = 0; k < blocks.size(); k++) {
            model.addElement((k + 1) + ". " + blocks.get(k));
        }
        model.addElement("All " + blocks.size() + " blocks, joined");
        JList<Object> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, list.getFont().getSize()));
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(620, Math.min(300, 40 + 22 * model.size())));
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        p.add(new JLabel("Copilot returned several code blocks - which one to insert?"), BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        DialogDescriptor dd = new DialogDescriptor(p, "Copilot App", true, DialogDescriptor.OK_CANCEL_OPTION,
                DialogDescriptor.OK_OPTION, null);
        Dialog dlg = DialogDisplayer.getDefault().createDialog(dd);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    dd.setValue(DialogDescriptor.OK_OPTION);
                    dlg.setVisible(false);
                }
            }
        });
        dlg.setVisible(true);
        dlg.dispose();
        if (dd.getValue() != DialogDescriptor.OK_OPTION || list.getSelectedIndex() < 0) {
            return null;
        }
        int i = list.getSelectedIndex();
        return i < blocks.size() ? blocks.get(i) : all;
    }

    private static String joinAll(List<Block> blocks) {
        StringBuilder b = new StringBuilder();
        for (Block x : blocks) {
            if (b.length() > 0) {
                b.append("\n\n");
            }
            b.append(x.code);
        }
        return b.toString();
    }

    /** Show the last answer in the "Copilot App" output window. */
    static void showAnswer() {
        Answer a = lastAnswer;
        InputOutput io = io();
        io.select();
        if (a != null) {
            io.getOut().println("\n---------------- answer ----------------\n" + a.text + "\n----------------------------------------");
        }
    }

    // ---------------------------------------------------------------- small helpers

    private static InputOutput io() {
        return IOProvider.getDefault().getIO("Copilot App", false);
    }

    static void log(String msg) {
        io().getOut().println(msg);
    }

    static void status(String msg) {
        StatusDisplayer.getDefault().setStatusText(msg);
    }

    private static void copy(String code, String msg) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
        status(msg);
        DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(msg, NotifyDescriptor.INFORMATION_MESSAGE));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
