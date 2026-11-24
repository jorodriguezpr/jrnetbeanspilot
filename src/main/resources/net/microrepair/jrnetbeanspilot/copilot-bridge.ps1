# JR NetBeans Pilot (jrnetbeanspilot) - shared bridge from JR Copilot Code (jrcopilotcode)
# Copyright 2026 Jose Rodriguez Arroyo
# Developer: Jose Rodriguez Arroyo <jrpcone@gmail.com>
# Website: https://microrepair.net | GitHub: https://github.com/jorodriguezpr
# Project: https://github.com/jorodriguezpr/jrnetbeanspilot
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0

<#
  copilot-bridge.ps1 - drive the Microsoft Copilot Windows app through UI Automation.

  The Copilot app (copilotapp.exe) is Chromium based and has no API. Its web content
  is only visible to UI Automation when the app runs with --force-renderer-accessibility,
  so "launch" (re)starts it that way.

  Actions (one per run, output = JSON lines on stdout):
    status   -> {type:"result", ok, running, accessible}
    launch   -> restart the Copilot app with accessibility on, wait until the chat box is ready
    newchat  -> click "New chat"
    ask      -> paste the prompt from -PromptFile, press Enter, wait for the answer, and return
                {text, blocks:[{language, code}]}

  Progress lines: {type:"progress", message}
  Errors:         {type:"result", ok:false, code, error}
#>
param(
  [ValidateSet('status', 'launch', 'newchat', 'ask')][string]$Action = 'status',
  [string]$PromptFile,
  [int]$TimeoutSec = 180,
  [switch]$NewChat,
  [switch]$NoRestoreFocus,
  [string]$CopilotExe = 'C:\Program Files (x86)\Microsoft\Copilot\Application\copilotapp.exe'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Windows.Forms

Add-Type -Namespace JrCopilot -Name Win32 -MemberDefinition @'
[DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(System.IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool ShowWindow(System.IntPtr hWnd, int nCmdShow);
[DllImport("user32.dll")] public static extern bool IsIconic(System.IntPtr hWnd);
[DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, System.UIntPtr dwExtraInfo);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(System.IntPtr hWnd, System.IntPtr pid);
[DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
[DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
[DllImport("user32.dll")] public static extern bool BringWindowToTop(System.IntPtr hWnd);
[DllImport("user32.dll")] public static extern void SwitchToThisWindow(System.IntPtr hWnd, bool altTab);
[DllImport("user32.dll")] public static extern bool SetWindowPos(System.IntPtr hWnd, System.IntPtr after, int x, int y, int cx, int cy, uint flags);
'@

$AE = [System.Windows.Automation.AutomationElement]
$Scope = [System.Windows.Automation.TreeScope]
$CT = [System.Windows.Automation.ControlType]
$EDITOR_ID = 'm365-chat-editor-target-element'
$MSGLIST_ID = 'llm-web-ui-messageList-scrollable-container'
$INPUT_BOX_ID = 'm365-chat-input-shared-container'

function Out-Json($obj) { [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress -Depth 6)); [Console]::Out.Flush() }
function Say($msg) { Out-Json @{ type = 'progress'; message = $msg } }
function Dbg($msg) { if ($env:JRCOPILOT_DEBUG) { Out-Json @{ type = 'debug'; message = "$msg | fg=$([JrCopilot.Win32]::GetForegroundWindow())" } } }
function Fail($code, $msg) { Out-Json @{ type = 'result'; ok = $false; code = $code; error = $msg }; exit 1 }

function Cond($prop, $value) { New-Object System.Windows.Automation.PropertyCondition($prop, $value) }

function Get-CopilotWindow {
  $p = Get-Process copilotapp -ErrorAction SilentlyContinue |
       Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } | Select-Object -First 1
  if (-not $p) { return $null }
  return $AE::FromHandle($p.MainWindowHandle)
}

# Two Copilot screens exist: the work (Microsoft 365) one, whose chat box has an id, and the
# consumer one, whose chat box ("Message Copilot") has none but sits in the shared input container.
# JRCOPILOT_COMPAT=1 skips the id lookups (to test the consumer path on a work account).
function Find-Editor($root) {
  if (-not $root) { return $null }
  if (-not $env:JRCOPILOT_COMPAT) {
    $e = $root.FindFirst($Scope::Descendants, (Cond $AE::AutomationIdProperty $EDITOR_ID))
    if ($e) { return $e }
  }
  # Both screens: the chat box is an Edit with class "fai-EditorInput__input ..." (name "Message Copilot").
  $e = @($root.FindAll($Scope::Descendants, (Cond $AE::ControlTypeProperty $CT::Edit)) |
         Where-Object { $_.Current.ClassName -like 'fai-EditorInput__input*' }) | Select-Object -First 1
  if ($e) { return $e }
  $box = $root.FindFirst($Scope::Descendants, (Cond $AE::AutomationIdProperty $INPUT_BOX_ID))
  if ($box) { return $box.FindFirst($Scope::Descendants, (Cond $AE::ControlTypeProperty $CT::Edit)) }
  return $null
}

# True when the focused element is the chat box (or something inside it).
function Test-InEditor($focused, $editor) {
  $el = $focused
  for ($i = 0; $el -and $i -lt 4; $i++) {
    if ([System.Windows.Automation.Automation]::Compare($el, $editor)) { return $true }
    $el = [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($el)
  }
  return $false
}

function Find-NewChat($root) {
  $nc = Find-ByName $root 'New chat' $null
  if (-not $nc) { $nc = Find-ByName $root 'New' $CT::Hyperlink }   # consumer screen
  return $nc
}

function Find-ByName($root, $name, $type) {
  $c = Cond $AE::NameProperty $name
  if ($type) { $c = New-Object System.Windows.Automation.AndCondition($c, (Cond $AE::ControlTypeProperty $type)) }
  return $root.FindFirst($Scope::Descendants, $c)
}

function Invoke-Element($el) {
  $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
}

# Copilot's answers, oldest first, as @{ key; msg }.
# Work screen: Text id="copilot-message-_r_1ht_-title" whose parent is the message group (key = that id).
# Otherwise: the message groups (class fai-CopilotMessage), keyed by position.
function Get-CopilotMessages($root) {
  $list = $root.FindFirst($Scope::Descendants, (Cond $AE::AutomationIdProperty $MSGLIST_ID))
  if (-not $list) { return @() }
  if (-not $env:JRCOPILOT_COMPAT) {
    $texts = $list.FindAll($Scope::Descendants, (Cond $AE::ControlTypeProperty $CT::Text))
    $titles = @($texts | Where-Object { $_.Current.AutomationId -like 'copilot-message-*-title' })
    if ($titles.Count -gt 0) {
      return @($titles | ForEach-Object { @{ key = $_.Current.AutomationId; msg = [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($_) } })
    }
  }
  $groups = @($list.FindAll($Scope::Descendants, (Cond $AE::ControlTypeProperty $CT::Group)) |
              Where-Object { $_.Current.ClassName -like 'fai-CopilotMessage*' })
  $i = 0
  return @($groups | ForEach-Object { @{ key = "pos$i"; msg = $_ }; $i++ })
}

# "Copy Response" on the work screen; any other "Copy ..." answer button (not a code block's) otherwise.
function Find-CopyResponse($msg) {
  $b = Find-ByName $msg 'Copy Response' $CT::Button
  if ($b) { return $b }
  return @($msg.FindAll($Scope::Descendants, (Cond $AE::ControlTypeProperty $CT::Button)) |
           Where-Object { $_.Current.Name -match '^Copy' -and $_.Current.Name -notmatch 'code' -and $_.Current.AutomationId -ne 'copy-button' }) |
         Select-Object -First 1
}

function Get-Clip {
  try { if ([System.Windows.Forms.Clipboard]::ContainsText()) { return [System.Windows.Forms.Clipboard]::GetText() } } catch {}
  return $null
}
function Set-Clip($text) {
  for ($i = 0; $i -lt 5; $i++) {
    try { if ($null -eq $text -or $text -eq '') { [System.Windows.Forms.Clipboard]::Clear() } else { [System.Windows.Forms.Clipboard]::SetText($text) }; return } catch { Start-Sleep -Milliseconds 100 }
  }
}

function Bring-ToFront($hwnd) {
  $W = [JrCopilot.Win32]
  if ($W::IsIconic($hwnd)) { $W::ShowWindow($hwnd, 9) | Out-Null }  # SW_RESTORE
  if ($W::GetForegroundWindow() -eq $hwnd) { return }
  # Windows refuses focus changes unless the caller "owns" input; a synthetic ALT press unlocks it.
  $W::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
  $W::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
  $W::SetForegroundWindow($hwnd) | Out-Null
  Start-Sleep -Milliseconds 100
  if ($W::GetForegroundWindow() -eq $hwnd) { return }
  # Join the current foreground window's input queue, then switch.
  $fgThread = $W::GetWindowThreadProcessId($W::GetForegroundWindow(), [IntPtr]::Zero)
  $me = $W::GetCurrentThreadId()
  $attached = $fgThread -ne $me -and $W::AttachThreadInput($me, $fgThread, $true)
  try { $W::BringWindowToTop($hwnd) | Out-Null; $W::SetForegroundWindow($hwnd) | Out-Null }
  finally { if ($attached) { $W::AttachThreadInput($me, $fgThread, $false) | Out-Null } }
  Start-Sleep -Milliseconds 100
  if ($W::GetForegroundWindow() -ne $hwnd) { $W::SwitchToThisWindow($hwnd, $true) }
}

# All Text names under an element, in document order, via the RAW view
# (Chromium marks code lines offscreen and the control view drops them).
$RAW = [System.Windows.Automation.TreeWalker]::RawViewWalker
function Get-RawText($el, $acc) {
  $ch = $RAW.GetFirstChild($el)
  while ($ch) {
    if ($ch.Current.ControlType -eq $CT::Text) { $acc.Add($ch.Current.Name) } else { Get-RawText $ch $acc }
    $ch = $RAW.GetNextSibling($ch)
  }
}

# The Copilot app stops generating while its window is fully covered (Chromium occlusion; the
# --disable-backgrounding-* switches do not help). While it answers, keep it on top WITHOUT
# activating it, so the keyboard stays in VS Code; afterwards put it back behind VS Code.
$SWP_KEEP = 0x0001 -bor 0x0002 -bor 0x0010   # NOSIZE | NOMOVE | NOACTIVATE
function Set-OnTop($hwnd) { [JrCopilot.Win32]::SetWindowPos($hwnd, [IntPtr](-1), 0, 0, 0, 0, $SWP_KEEP) | Out-Null }   # HWND_TOPMOST
function Clear-OnTop($hwnd, $behind) {
  [JrCopilot.Win32]::SetWindowPos($hwnd, [IntPtr](-2), 0, 0, 0, 0, $SWP_KEEP) | Out-Null                               # HWND_NOTOPMOST
  if ($behind -and $behind -ne [IntPtr]::Zero -and $behind -ne $hwnd) {
    [JrCopilot.Win32]::SetWindowPos($hwnd, $behind, 0, 0, 0, 0, $SWP_KEEP) | Out-Null                                  # just behind VS Code
  }
}

# Code text from the UIA tree: the "Code editor" Edit alternates [line-number group][line-content group].
function Get-CodeFromTree($block) {
  $ed = $block.FindFirst($Scope::Descendants, (Cond $AE::NameProperty 'Code editor'))
  if (-not $ed) { return $null }
  $rows = @(); $ch = $RAW.GetFirstChild($ed)
  while ($ch) { $rows += $ch; $ch = $RAW.GetNextSibling($ch) }
  $lines = New-Object System.Collections.Generic.List[string]
  for ($i = 1; $i -lt $rows.Count; $i += 2) {
    $parts = New-Object System.Collections.Generic.List[string]
    Get-RawText $rows[$i] $parts
    $lines.Add(((($parts) -join '') -replace '\s+$', ''))
  }
  return ($lines -join "`n")
}

function Get-Blocks($msg) {
  $blocks = @()
  $groups = $msg.FindAll($Scope::Descendants, (Cond $AE::ControlTypeProperty $CT::Group)) |
            Where-Object { $_.Current.ClassName -like 'scriptor-component-code-block*' }
  foreach ($g in $groups) {
    $lang = ''
    $t = $g.FindFirst($Scope::Children, (Cond $AE::ControlTypeProperty $CT::Text))
    if ($t) { $lang = $t.Current.Name }
    $code = Get-CodeFromTree $g
    $copy = $null
    if ($null -eq $code) { $copy = $g.FindFirst($Scope::Descendants, (Cond $AE::AutomationIdProperty 'copy-button')) }
    if ($copy) {
      $sentinel = '__jrcopilotcode_' + [guid]::NewGuid().ToString('N')
      Set-Clip $sentinel
      try {
        Invoke-Element $copy
        for ($i = 0; $i -lt 15; $i++) { Start-Sleep -Milliseconds 100; $c = Get-Clip; if ($c -and $c -ne $sentinel) { $code = $c; break } }
      } catch {}
    }
    if ($null -ne $code) { $blocks += @{ language = $lang; code = ($code -replace "`r`n", "`n") } }
  }
  return , $blocks
}

function Get-ResponseText($msg) {
  $btn = Find-CopyResponse $msg
  if ($btn) {
    $sentinel = '__jrcopilotcode_' + [guid]::NewGuid().ToString('N')
    Set-Clip $sentinel
    try {
      Invoke-Element $btn
      for ($i = 0; $i -lt 15; $i++) { Start-Sleep -Milliseconds 100; $c = Get-Clip; if ($c -and $c -ne $sentinel) { return ($c -replace "`r`n", "`n") } }
    } catch {}
  }
  $parts = $msg.FindAll($Scope::Descendants, (Cond $AE::ControlTypeProperty $CT::Text)) | ForEach-Object { $_.Current.Name }
  return ($parts -join "`n")
}

function Start-Accessible {
  Say 'Restarting the Copilot app with accessibility enabled...'
  Get-Process copilotapp, copilotapphost -ErrorAction SilentlyContinue | Stop-Process -Force -Confirm:$false
  Start-Sleep -Seconds 2
  if (-not (Test-Path $CopilotExe)) { Fail 'NOT_INSTALLED' "Copilot app not found at $CopilotExe" }
  Start-Process -FilePath $CopilotExe -ArgumentList '--force-renderer-accessibility'
  $deadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 1000
    try { $w = Get-CopilotWindow; if ($w -and (Find-Editor $w)) { return $w } } catch {}
  }
  Fail 'LAUNCH_TIMEOUT' 'The Copilot app started but its chat box did not appear within 60 s (is it signed in?).'
}

# Copilot's own error page: "Unable to load the content / Try refreshing / [Refresh]".
function Test-PageError($root) {
  return [bool]($root.FindFirst($Scope::Descendants, (Cond $AE::NameProperty 'Unable to load the content')))
}

function Require-Ready {
  $w = Get-CopilotWindow
  if (-not $w) { Fail 'NOT_RUNNING' 'The Copilot app is not running.' }
  $ed = Find-Editor $w
  if (-not $ed -and (Test-PageError $w)) {
    # Nothing was asked yet, so refreshing is safe.
    Say 'Copilot shows "Unable to load the content" - refreshing it...'
    $btn = Find-ByName $w 'Refresh' $CT::Button
    if ($btn) {
      Invoke-Element $btn
      for ($i = 0; $i -lt 20 -and -not $ed; $i++) { Start-Sleep -Milliseconds 750; $ed = Find-Editor $w }
    }
    if (-not $ed) { Fail 'COPILOT_ERROR' 'Copilot shows "Unable to load the content" and Refresh did not fix it. Check the Copilot app (sign-in, internet, usage limits).' }
  }
  if (-not $ed) { Fail 'NOT_ACCESSIBLE' 'The Copilot app is running without accessibility, so its chat cannot be read. Restart it with accessibility enabled.' }
  return $w
}

try {
  switch ($Action) {
    'status' {
      $w = Get-CopilotWindow
      $acc = $false; if ($w) { $acc = [bool](Find-Editor $w) }
      Out-Json @{ type = 'result'; ok = $true; running = [bool]$w; accessible = $acc }
    }
    'launch' {
      $w = Start-Accessible
      Out-Json @{ type = 'result'; ok = $true; running = $true; accessible = $true }
    }
    'newchat' {
      $w = Require-Ready
      $nc = Find-NewChat $w
      if (-not $nc) { Fail 'NO_NEW_CHAT' '"New chat" link not found.' }
      Invoke-Element $nc
      Out-Json @{ type = 'result'; ok = $true }
    }
    'ask' {
      if (-not $PromptFile -or -not (Test-Path $PromptFile)) { Fail 'BAD_ARGS' 'Missing -PromptFile.' }
      $prompt = [System.IO.File]::ReadAllText($PromptFile, [System.Text.Encoding]::UTF8)
      if (-not $prompt.Trim()) { Fail 'BAD_ARGS' 'Empty prompt.' }

      $w = Require-Ready
      if ($NewChat) {
        $nc = Find-NewChat $w
        if ($nc) { Say 'Starting a new chat...'; Invoke-Element $nc; Start-Sleep -Milliseconds 1500 }
      }
      $before = @{}
      foreach ($m in (Get-CopilotMessages $w)) { $before[$m.key] = $true }

      $hwnd = New-Object IntPtr ($w.Current.NativeWindowHandle)
      $prevFg = [JrCopilot.Win32]::GetForegroundWindow()
      $savedClip = Get-Clip
      $started = Get-Date
      $onTop = $false
      try {
        Say 'Sending the question to the Copilot app...'
        Set-Clip $prompt
        Bring-ToFront $hwnd
        $ed = Find-Editor $w
        $ed.SetFocus()
        Start-Sleep -Milliseconds 300
        # Safety: only type if the keyboard focus really is the Copilot chat box.
        $f = $AE::FocusedElement
        if (-not $f -or -not (Test-InEditor $f $ed)) {
          Fail 'FOCUS_FAILED' 'Could not put the keyboard focus in the Copilot chat box; nothing was typed.'
        }
        [System.Windows.Forms.SendKeys]::SendWait('^a')
        [System.Windows.Forms.SendKeys]::SendWait('^v')
        Start-Sleep -Milliseconds 400
        $f = $AE::FocusedElement
        if (-not $f -or -not (Test-InEditor $f $ed)) {
          Fail 'FOCUS_FAILED' 'Focus left the Copilot chat box before sending; the question was not sent.'
        }
        [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
        Start-Sleep -Milliseconds 300
        Dbg "sent; copilot=$hwnd prev=$prevFg"
        # Keep Copilot visible (it pauses when covered) but hand the keyboard back to VS Code.
        Set-OnTop $hwnd
        $onTop = $true
        Dbg 'on top set'
        if (-not $NoRestoreFocus -and $prevFg -ne [IntPtr]::Zero) {
          Bring-ToFront $prevFg
          Start-Sleep -Milliseconds 150
          if ([JrCopilot.Win32]::GetForegroundWindow() -ne $prevFg) { Bring-ToFront $prevFg }
          Dbg 'focus restored?'
        }

        Say 'Waiting for the answer...'
        $deadline = $started.AddSeconds($TimeoutSec)
        $msg = $null; $lastSig = ''; $stable = 0; $announced = $false; $polls = 0; $notReadySince = $null; $quietSig = ''; $quietSince = Get-Date
        while ((Get-Date) -lt $deadline) {
          Start-Sleep -Milliseconds 800
          if ((++$polls % 5) -eq 1) { Dbg "poll $polls" }
          if (Test-PageError $w) { Fail 'COPILOT_ERROR' 'Copilot shows "Unable to load the content". Click Refresh in the Copilot app and ask again (if it keeps happening, wait a few minutes - Copilot may be limiting requests).' }
          # Find the newest answer again on every poll: React can replace the element while streaming.
          $new = @(Get-CopilotMessages $w | Where-Object { -not $before.ContainsKey($_.key) })
          if ($new.Count -eq 0) { continue }
          $msg = $new[-1].msg
          if (-not $msg) { continue }
          if (-not $announced) { Say 'Copilot is answering...'; $announced = $true }
          $done = [bool](Find-CopyResponse $msg)
          if (-not $done) {
            # Last resort for a screen without a recognisable copy button: text unchanged for 30 s.
            $qt = $msg.FindAll($Scope::Descendants, (Cond $AE::ControlTypeProperty $CT::Text))
            $qsig = "$($qt.Count):" + (($qt | ForEach-Object { $_.Current.Name.Length }) | Measure-Object -Sum).Sum
            if ($qsig -ne $quietSig -or $qt.Count -eq 0) { $quietSig = $qsig; $quietSince = Get-Date }
            if (((Get-Date) - $quietSince).TotalSeconds -lt 30) { $stable = 0; continue }
            Dbg 'no copy button; text quiet 30 s -> treating as finished'
          }
          # Code previews load lazily after "Copy Response" shows up (skeleton bars first):
          # wait until every code-block host holds a code editor and no skeleton is left.
          if ($msg.FindFirst($Scope::Descendants, (Cond $AE::ControlTypeProperty $CT::ProgressBar))) { $stable = 0; continue }
          # HTML/SVG blocks open as a live PREVIEW (rendered page, no code editor): switch them to "Code".
          $toggles = @($msg.FindAll($Scope::Descendants, (Cond $AE::AutomationIdProperty 'preview-toggle-button')) |
                       Where-Object { $_.Current.Name -eq 'Code' })
          if ($toggles.Count -gt 0) {
            foreach ($tg in $toggles) { try { Invoke-Element $tg; Dbg 'preview -> code view' } catch {} }
            $stable = 0; Start-Sleep -Milliseconds 500; continue
          }
          $groups = $msg.FindAll($Scope::Descendants, (Cond $AE::ControlTypeProperty $CT::Group))
          $hosts = @($groups | Where-Object { $_.Current.AutomationId -like 'code-block-host-*' }).Count
          $ready = @($groups | Where-Object { $_.Current.ClassName -like 'scriptor-component-code-block*' } |
                     Where-Object { $_.FindFirst($Scope::Descendants, (Cond $AE::NameProperty 'Code editor')) }).Count
          $texts = $msg.FindAll($Scope::Descendants, (Cond $AE::ControlTypeProperty $CT::Text))
          $sig = "$hosts/$ready/$($texts.Count):" +(($texts | ForEach-Object { $_.Current.Name.Length }) | Measure-Object -Sum).Sum
          if ($sig -eq $lastSig) { $stable++ } else { $stable = 0; $lastSig = $sig }
          if ($ready -lt $hosts) {
            # Safety net: never wait forever for a code view that does not come (unknown block type).
            # Once the answer has been finished and unchanged for 15 s, read what is there.
            if ($stable -eq 0 -or -not $notReadySince) { $notReadySince = Get-Date }
            Dbg "code blocks ready $ready/$hosts"
            if (((Get-Date) - $notReadySince).TotalSeconds -ge 15) { $stable = 2; break }
            continue
          }
          if ($stable -ge 2) { break }
        }
        if (-not $msg) { Fail 'NO_ANSWER' "No answer appeared within $TimeoutSec s." }
        if ($stable -lt 2) { Fail 'TIMEOUT' "The answer did not finish within $TimeoutSec s." }

        Say 'Reading the answer...'
        $blocks = Get-Blocks $msg
        $text = Get-ResponseText $msg
        Out-Json @{ type = 'result'; ok = $true; text = $text; blocks = $blocks; elapsedMs = [int]((Get-Date) - $started).TotalMilliseconds }
      } finally {
        if ($onTop) {
          Clear-OnTop $hwnd $(if ($NoRestoreFocus) { [IntPtr]::Zero } else { $prevFg })
          # Invoking Copilot's copy buttons can activate its window: hand the keyboard back again.
          if (-not $NoRestoreFocus -and $prevFg -ne [IntPtr]::Zero -and [JrCopilot.Win32]::GetForegroundWindow() -ne $prevFg) { Bring-ToFront $prevFg }
        }
        Set-Clip $savedClip
      }
    }
  }
} catch {
  Fail 'ERROR' ($_.Exception.Message)
}
