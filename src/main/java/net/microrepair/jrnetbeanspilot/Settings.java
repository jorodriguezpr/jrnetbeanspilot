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

import java.util.prefs.Preferences;
import org.openide.util.NbPreferences;

/** Settings (Tools > Options > Miscellaneous > Copilot App), kept in the NetBeans user folder. */
public final class Settings {

    public static final String DEFAULT_EXE = "C:\\Program Files (x86)\\Microsoft\\Copilot\\Application\\copilotapp.exe";
    public static final String DEFAULT_INSTRUCTION = "Answer with the complete code in a single fenced code block.";

    private Settings() {
    }

    static Preferences prefs() {
        return NbPreferences.forModule(Settings.class);
    }

    public static String copilotExe() { return prefs().get("copilotExe", DEFAULT_EXE); }
    public static int timeoutSeconds() { return Math.max(20, prefs().getInt("timeoutSeconds", 180)); }
    public static boolean newChatEachQuestion() { return prefs().getBoolean("newChatEachQuestion", false); }
    public static boolean includeSelection() { return prefs().getBoolean("includeSelection", true); }
    public static String codeInstruction() { return prefs().get("codeInstruction", DEFAULT_INSTRUCTION); }
    /** auto = replace the selection / insert at the cursor; clipboard = only copy. */
    public static String insertMode() { return prefs().get("insertMode", "auto"); }
    /** pick | all | first */
    public static String multipleBlocks() { return prefs().get("multipleBlocks", "pick"); }
    /** ask | always | never */
    public static String autoRestartCopilot() { return prefs().get("autoRestartCopilot", "ask"); }
}
