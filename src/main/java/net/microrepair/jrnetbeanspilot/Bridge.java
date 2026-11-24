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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs bridge/copilot-bridge.ps1 (shipped inside the module, copied to
 * %LOCALAPPDATA%\jrnetbeanspilot) and reads its JSON-lines output.
 * No NetBeans API here, so it can be exercised from a plain test.
 */
public final class Bridge {

    /** A failed bridge run; {@code code} is the bridge's error code (TIMEOUT, NOT_RUNNING, ...). */
    public static final class BridgeException extends Exception {
        public final String code;

        public BridgeException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Lets the caller cancel a running bridge (kills PowerShell; the bridge's finally block does not run). */
    public static final class Handle {
        private volatile Process process;
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
            Process p = process;
            if (p != null) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static Path script;

    private Bridge() {
    }

    /** The bridge script on disk (re-copied when the module ships a different one). */
    static synchronized Path scriptPath() throws IOException {
        if (script != null && Files.isRegularFile(script)) {
            return script;
        }
        byte[] data;
        try (InputStream in = Bridge.class.getResourceAsStream("copilot-bridge.ps1")) {
            if (in == null) {
                throw new IOException("copilot-bridge.ps1 is missing from the module");
            }
            data = in.readAllBytes();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).substring(0, 12);
        } catch (Exception e) {
            hash = Integer.toHexString(Arrays.hashCode(data));
        }
        String base = System.getenv("LOCALAPPDATA");
        Path dir = Paths.get(base != null ? base : System.getProperty("java.io.tmpdir"), "jrnetbeanspilot");
        Files.createDirectories(dir);
        Path p = dir.resolve("copilot-bridge-" + hash + ".ps1");
        if (!Files.isRegularFile(p) || !Arrays.equals(Files.readAllBytes(p), data)) {
            Files.write(p, data);
        }
        script = p;
        return p;
    }

    /**
     * Run one bridge action and return its result object (ok == true).
     *
     * @param onProgress receives each progress message (may be null)
     * @param handle     to cancel the run (may be null)
     */
    public static Map<String, Object> run(String action, List<String> extraArgs, String copilotExe,
            Consumer<String> onProgress, Handle handle) throws BridgeException {
        List<String> cmd = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-STA",
                "-ExecutionPolicy", "Bypass", "-File"));
        try {
            cmd.add(scriptPath().toString());
        } catch (IOException e) {
            throw new BridgeException("NO_BRIDGE", "Could not prepare the bridge script: " + e.getMessage());
        }
        cmd.addAll(List.of("-Action", action, "-CopilotExe", copilotExe));
        cmd.addAll(extraArgs);

        Process p;
        try {
            p = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new BridgeException("NO_POWERSHELL", "Could not start powershell.exe: " + e.getMessage());
        }
        if (handle != null) {
            handle.process = p;
            if (handle.cancelled) {
                handle.cancel();
            }
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread errReader = new Thread(() -> {
            try (InputStream es = p.getErrorStream()) {
                es.transferTo(err);
            } catch (IOException ignored) {
            }
        }, "jrnetbeanspilot-bridge-stderr");
        errReader.setDaemon(true);
        errReader.start();

        Map<String, Object> result = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Map<String, Object> obj = parseLine(line);
                if (obj == null) {
                    continue;
                }
                Object type = obj.get("type");
                if ("progress".equals(type)) {
                    if (onProgress != null) {
                        onProgress.accept(String.valueOf(obj.get("message")));
                    }
                } else if ("result".equals(type)) {
                    result = obj;
                }
            }
        } catch (IOException e) {
            // stream closed (cancel) - handled below
        }
        try {
            p.waitFor();
            errReader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (handle != null && handle.isCancelled()) {
            throw new BridgeException("CANCELLED", "Cancelled.");
        }
        if (result == null) {
            String se = err.toString(StandardCharsets.UTF_8).trim();
            throw new BridgeException("NO_RESULT", "The bridge returned no result." + (se.isEmpty() ? "" : "\n" + se));
        }
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            Object code = result.get("code");
            Object msg = result.get("error");
            throw new BridgeException(code == null ? "ERROR" : String.valueOf(code), msg == null ? "Failed." : String.valueOf(msg));
        }
        return result;
    }

    /** One stdout line as a JSON object, or null for anything else (PowerShell warnings etc.). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseLine(String line) {
        String t = line.trim();
        if (!t.startsWith("{")) {
            return null;
        }
        try {
            Object o = Json.parse(t);
            return o instanceof Map ? (Map<String, Object>) o : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
