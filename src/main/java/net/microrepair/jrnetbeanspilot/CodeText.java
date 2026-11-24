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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure text helpers (no NetBeans API), unit tested in CodeTextTest. */
public final class CodeText {

    private CodeText() {
    }

    /** One code block of an answer. */
    public static final class Block {
        public final String language;
        public final String code;

        public Block(String language, String code) {
            this.language = language == null ? "" : language;
            this.code = code == null ? "" : code;
        }

        public int lineCount() {
            return code.split("\n", -1).length;
        }

        @Override
        public String toString() {
            String first = "";
            for (String l : code.split("\n")) {
                if (!l.isBlank()) {
                    first = l.trim();
                    break;
                }
            }
            return (language.isEmpty() ? "code" : language) + " - " + lineCount() + " lines - " + first;
        }
    }

    /** Build the text sent to Copilot. */
    public static String buildPrompt(String question, String selection, String fileName, String language, String instruction) {
        List<String> parts = new ArrayList<>();
        parts.add(question.trim());
        if (selection != null && !selection.isBlank()) {
            List<String> where = new ArrayList<>();
            if (fileName != null && !fileName.isEmpty()) {
                where.add("file: " + fileName);
            }
            if (language != null && !language.isEmpty()) {
                where.add("language: " + language);
            }
            String fence = selection.contains("```") ? "~~~~" : "```";
            parts.add("Here is the code" + (where.isEmpty() ? "" : " (" + String.join(", ", where) + ")") + ":\n"
                    + fence + (language == null ? "" : language) + "\n" + stripEnd(selection) + "\n" + fence);
        }
        if (instruction != null && !instruction.isBlank()) {
            parts.add(instruction.trim());
        }
        return String.join("\n\n", parts);
    }

    /** The bridge's blocks value (list, single object or null) as Blocks; empty code is dropped. */
    public static List<Block> normaliseBlocks(Object blocks) {
        List<Block> out = new ArrayList<>();
        List<?> arr = blocks instanceof List ? (List<?>) blocks : (blocks == null ? List.of() : List.of(blocks));
        for (Object o : arr) {
            if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                Object code = m.get("code");
                if (code instanceof String && !((String) code).isBlank()) {
                    Object lang = m.get("language");
                    out.add(new Block(lang == null ? "" : String.valueOf(lang), ((String) code).replace("\r\n", "\n")));
                }
            }
        }
        return out;
    }

    private static final Pattern FENCE = Pattern.compile("(?:^|\\n)(```|~~~)([^\\n]*)\\n([\\s\\S]*?)\\n\\1[ \\t]*(?=\\n|$)");

    /** Fallback: fenced blocks in plain/markdown text. */
    public static List<Block> extractFencedBlocks(String text) {
        List<Block> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = FENCE.matcher(text.replace("\r\n", "\n"));
        while (m.find()) {
            out.add(new Block(m.group(2).trim(), m.group(3)));
        }
        return out;
    }

    /** Indent every line after the first (except empty ones) with {@code indent}. */
    public static String reindent(String code, String indent) {
        if (indent == null || indent.isEmpty()) {
            return code;
        }
        String[] lines = code.split("\n", -1);
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < lines.length; k++) {
            if (k > 0) {
                b.append('\n');
                if (!lines[k].isEmpty()) {
                    b.append(indent);
                }
            }
            b.append(lines[k]);
        }
        return b.toString();
    }

    /**
     * The code as it should go into the editor, matching the indentation there.
     *
     * @param lineText  the text of the line where the selection/cursor starts
     * @param column    the column of the selection/cursor start in that line
     * @param hasSelection whether a selection is being replaced
     */
    public static String fitIndent(String code, String lineText, int column, boolean hasSelection) {
        String lead = leadingWhitespace(lineText);
        if (column > 0) {
            return reindent(code, lead.substring(0, Math.min(column, lead.length())));
        }
        if (hasSelection && !lead.isEmpty()) {
            return reindent(lead + code, lead);
        }
        return code;
    }

    public static String leadingWhitespace(String s) {
        int k = 0;
        while (k < s.length() && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
            k++;
        }
        return s.substring(0, k);
    }

    /** NetBeans MIME type / file extension -> the language name used in the prompt. */
    public static String languageOf(String mimeType, String ext) {
        String m = mimeType == null ? "" : mimeType.toLowerCase();
        if (m.contains("php")) return "php";
        if (m.contains("javascript") || m.equals("text/x-js")) return "javascript";
        if (m.contains("typescript")) return "typescript";
        if (m.contains("html")) return "html";
        if (m.contains("css")) return "css";
        if (m.contains("x-java")) return "java";
        if (m.contains("sql")) return "sql";
        if (m.contains("json")) return "json";
        if (m.contains("xml")) return "xml";
        if (m.contains("python")) return "python";
        if (m.contains("x-sh") || m.contains("shell")) return "bash";
        return ext == null ? "" : ext.toLowerCase();
    }

    private static String stripEnd(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) {
            e--;
        }
        return s.substring(0, e);
    }
}
