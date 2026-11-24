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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class CodeTextTest {

    @Test
    void jsonParsesBridgeResult() {
        Map<String, Object> r = Bridge.parseLine(
                "{\"type\":\"result\",\"ok\":true,\"text\":\"a\\u003cb\\u003e \\\"q\\\" ñ\\n\",\"blocks\":{\"language\":\"PHP\",\"code\":\"echo 1;\"},\"elapsedMs\":9171}");
        assertEquals("result", r.get("type"));
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("a<b> \"q\" ñ\n", r.get("text"));
        assertEquals(9171.0, r.get("elapsedMs"));
        List<CodeText.Block> b = CodeText.normaliseBlocks(r.get("blocks"));   // single object, not a list
        assertEquals(1, b.size());
        assertEquals("PHP", b.get(0).language);
    }

    @Test
    void nonJsonLinesAreIgnored() {
        assertNull(Bridge.parseLine("WARNING: something"));
        assertNull(Bridge.parseLine("{broken"));
        assertTrue(CodeText.normaliseBlocks(null).isEmpty());
        assertTrue(CodeText.normaliseBlocks(Json.parse("[{\"language\":\"x\",\"code\":\"  \"}]")).isEmpty());
    }

    @Test
    void promptWithSelection() {
        String p = CodeText.buildPrompt(" add validation ", "function f() {}\n\n", "a.php", "php", "Answer with code.");
        assertEquals("add validation\n\nHere is the code (file: a.php, language: php):\n```php\nfunction f() {}\n```\n\nAnswer with code.", p);
        assertEquals("q", CodeText.buildPrompt("q", "", "a.php", "php", ""));
        assertTrue(CodeText.buildPrompt("q", "x ``` y", "", "", "").contains("~~~~"));
    }

    @Test
    void fencedBlocks() {
        List<CodeText.Block> b = CodeText.extractFencedBlocks("Text\n```php\n<?php\necho 1;\n```\nmore\n~~~\nplain\n~~~");
        assertEquals(2, b.size());
        assertEquals("php", b.get(0).language);
        assertEquals("<?php\necho 1;", b.get(0).code);
        assertEquals("plain", b.get(1).code);
    }

    @Test
    void indentation() {
        String code = "if ($a) {\n    f();\n\n}";
        // cursor after the indentation: every following line gets the indentation
        assertEquals("if ($a) {\n        f();\n\n    }", CodeText.fitIndent(code, "    ", 4, false));
        // cursor at column 0, no selection: unchanged
        assertEquals(code, CodeText.fitIndent(code, "    x", 0, false));
        // whole indented lines selected from column 0: indentation added to every line
        assertEquals("\tif ($a) {\n\t    f();\n\n\t}", CodeText.fitIndent(code, "\told();", 0, true));
        assertEquals("php", CodeText.languageOf("text/x-php5", "php"));
        assertEquals("vue", CodeText.languageOf(null, "VUE"));
    }

    /** Live: needs the Copilot app. mvn test -Djrpilot.live=true */
    @Test
    @EnabledIfSystemProperty(named = "jrpilot.live", matches = "true")
    void liveStatus() throws Exception {
        Map<String, Object> r = Bridge.run("status", List.of(), Settings.DEFAULT_EXE, null, null);
        System.out.println("status: " + r);
        assertEquals(Boolean.TRUE, r.get("ok"));
    }

    /** Live: sends one question to Copilot. mvn test -Djrpilot.ask=true */
    @Test
    @EnabledIfSystemProperty(named = "jrpilot.ask", matches = "true")
    void liveAsk() throws Exception {
        Path f = Files.createTempFile("jrnetbeanspilot-test-", ".txt");
        Files.writeString(f, CodeText.buildPrompt("Write a PHP function that returns the square of a number.",
                "", "", "", Settings.DEFAULT_INSTRUCTION));
        try {
            Map<String, Object> r = Bridge.run("ask", List.of("-PromptFile", f.toString(), "-TimeoutSec", "150"),
                    Settings.DEFAULT_EXE, m -> System.out.println("  " + m), null);
            List<CodeText.Block> b = CodeText.normaliseBlocks(r.get("blocks"));
            System.out.println("blocks: " + b.size() + " elapsedMs=" + r.get("elapsedMs"));
            b.forEach(x -> System.out.println("[" + x.language + "]\n" + x.code));
            assertTrue(!b.isEmpty());
        } finally {
            Files.deleteIfExists(f);
        }
    }
}
