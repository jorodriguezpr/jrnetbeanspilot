<!--
  JR NetBeans Pilot (jrnetbeanspilot) - Copyright 2026 Jose Rodriguez Arroyo
  Developer: Jose Rodriguez Arroyo <jrpcone@gmail.com> - https://microrepair.net
  Licensed under the Apache License, Version 2.0. SPDX-License-Identifier: Apache-2.0
-->

# Changelog

## 0.1.0 - 2026-11-24

First release: a NetBeans 21+ plugin that asks the Microsoft Copilot Windows app for code.

- **Ask Copilot and Insert Code** (`Ctrl+Alt+Shift+C`, editor right-click menu, Tools → Copilot App): the question, plus the selected code with its file name and language, goes to Copilot. The code comes back at the cursor or in place of the selection, re-indented, as one undo step.
- **Ask Copilot and Show the Answer** (`Ctrl+Alt+Shift+E`): shown in Window → Output → Copilot App.
- Also: insert the last answer again, new chat, restart Copilot with accessibility, check the connection.
- Settings under Tools → Options → Miscellaneous → Copilot App.
- Uses the bridge from JR Copilot Code 0.1.3: works with the work and consumer Copilot screens, keeps Copilot visible while it answers, and switches HTML previews to code.
- Apache License 2.0.
