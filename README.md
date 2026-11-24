<!--
  JR NetBeans Pilot (jrnetbeanspilot) - Copyright 2026 Jose Rodriguez Arroyo
  Developer: Jose Rodriguez Arroyo <jrpcone@gmail.com> - https://microrepair.net
  Licensed under the Apache License, Version 2.0. SPDX-License-Identifier: Apache-2.0
-->

# JR NetBeans Pilot

A NetBeans plugin that sends your question to the **Microsoft Copilot app for Windows** and puts the code from the answer into the file you are editing: at the cursor, or in place of the selected code.

It's the NetBeans companion of [JR Copilot Code](https://github.com/jorodriguezpr/jrcopilotcode) (VS Code) and uses the same bridge. It works with both Copilot screens: the work (Microsoft 365) one and the consumer one for personal accounts.

## Requirements

- Windows 10 or 11.
- NetBeans **21 or newer**.
- The Microsoft Copilot app (`copilotapp.exe`), installed and signed in.

## Install

1. Download `jrnetbeanspilot-<version>.nbm`.
2. In NetBeans: **Tools → Plugins → Downloaded → Add Plugins...**, choose the `.nbm` file, then **Install**.
3. The plugin is not signed, so NetBeans asks you to confirm. Choose **Continue**.

## Use

| What | How |
|---|---|
| Ask and insert code | **Ctrl+Alt+Shift+C**, the editor's right-click menu, or **Tools → Copilot App** |
| Ask and show the answer | **Ctrl+Alt+Shift+E** (the answer opens in **Window → Output → Copilot App**) |
| Insert the last answer's code again | Tools → Copilot App |
| Start a new Copilot chat | Tools → Copilot App |
| Restart Copilot with accessibility | Tools → Copilot App |
| Check the connection | Tools → Copilot App |

1. Put the cursor where the code should go, or select code you want changed. The selected code, the file name and the language are sent along with your question.
2. Type the question. **Enter** sends it; **Shift+Enter** adds a new line.
3. While Copilot answers, its window stays visible on top, because Copilot pauses its answer when it's completely covered. The keyboard stays in NetBeans. A progress bar with a cancel button shows at the bottom right.
4. The code goes in with the indentation of the line it lands on, as one undo step: **Ctrl+Z** removes it. If the answer has several code blocks, you choose one, or all of them.

The first time, the plugin offers to restart the Copilot app with accessibility on (`--force-renderer-accessibility`), because without it Copilot's chat can't be read. Your chats are kept.

## Settings

**Tools → Options → Miscellaneous → Copilot App**

| Setting | Default |
|---|---|
| Path to `copilotapp.exe` | `C:\Program Files (x86)\Microsoft\Copilot\Application\copilotapp.exe` |
| Answer timeout | 180 s |
| Instruction added to code questions | "Answer with the complete code in a single fenced code block." |
| Insert mode | `auto` (at the cursor / replace the selection) or `clipboard` |
| Several code blocks | `pick`, `all` or `first` |
| Copilot closed or not readable | `ask`, `always` or `never` restart it |
| Send the selected code | on |
| New Copilot chat for every question | off |

## How it works

The Copilot app has no API. The plugin runs a PowerShell script (`copilot-bridge.ps1`, shipped inside the plugin) that uses Windows UI Automation:

- It pastes your question into Copilot's chat box and presses Enter. It types only after confirming that the keyboard focus really is in that chat box.
- It waits until the answer is finished and its code blocks are loaded. HTML answers shown as a live preview are switched to the code view first.
- It reads the code blocks with their language, hands the keyboard back to NetBeans, and restores your clipboard.

## Troubleshooting

| Message | What to do |
|---|---|
| `LAUNCH_TIMEOUT` | Open Copilot yourself and check that it's signed in and shows a chat box. |
| `NOT_ACCESSIBLE` | Use **Tools → Copilot App → Restart Copilot with Accessibility**. |
| `FOCUS_FAILED` | Something took the keyboard while the question was being pasted. Nothing was sent; ask again. |
| `COPILOT_ERROR` | Copilot shows "Unable to load the content". Wait a few minutes; Copilot may be limiting requests. |
| `TIMEOUT` | Raise the timeout in the settings, or check what Copilot shows. |

Details of every run are in **Window → Output → Copilot App**.

## Build

Needs JDK 17+ and Maven 3.9+:

```
powershell -ExecutionPolicy Bypass -File build.ps1
```

The plugin is written to `dist\jrnetbeanspilot-<version>.nbm`. `mvn test -Djrpilot.live=true` checks the connection to the Copilot app; `-Djrpilot.ask=true` also sends it one test question.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Developer: **Jose Rodriguez Arroyo**, <https://microrepair.net>, [github.com/jorodriguezpr](https://github.com/jorodriguezpr)

Microsoft and Copilot are trademarks of Microsoft Corporation. This project is not affiliated with or endorsed by Microsoft, and NetBeans is a trademark of the Apache Software Foundation.
