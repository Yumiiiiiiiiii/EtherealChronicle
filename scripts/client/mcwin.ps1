<#
Window helpers for the offline QA client. Dot-source: . ./scripts/client/mcwin.ps1
SAFETY: every input function first verifies that the foreground window belongs to the given Minecraft process id and aborts otherwise;
screenshots capture only that window's rectangle. Nothing is ever sent to any other application.
#>
Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices; using System.Text;
public class McWin {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] inputs, int size);
  [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint type);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern void SwitchToThisWindow(IntPtr h, bool alt);
  // Focus without sending any input to other apps: temporarily share the foreground thread's input queue.
  public static void ForceFocus(IntPtr h) {
    IntPtr fg = GetForegroundWindow(); uint pid; uint fgThread = GetWindowThreadProcessId(fg, out pid); uint me = GetCurrentThreadId();
    if (fgThread != me) AttachThreadInput(me, fgThread, true);
    SetForegroundWindow(h); SwitchToThisWindow(h, false);
    if (fgThread != me) AttachThreadInput(me, fgThread, false);
  }
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  public static void Topmost(IntPtr h, bool on) { SetWindowPos(h, on ? new IntPtr(-1) : new IntPtr(-2), 0, 0, 0, 0, 0x13); }
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
  [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public INPUTUNION u; }
  [StructLayout(LayoutKind.Explicit)] public struct INPUTUNION { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
  [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx, dy; public uint data, flags, time; public IntPtr extra; }
  [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort vk, scan; public uint flags, time; public IntPtr extra; }
  public static IntPtr FindByPid(uint pid) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); if (p == pid && IsWindowVisible(h)) { var sb = new StringBuilder(256); GetWindowText(h, sb, 256); if (sb.Length > 0) { found = h; return false; } } return true; }, IntPtr.Zero);
    return found;
  }
  public static void Key(ushort vk, bool up) { var i = new INPUT[1]; i[0].type = 1; i[0].u.ki.vk = vk; i[0].u.ki.scan = (ushort)MapVirtualKey(vk, 0); i[0].u.ki.flags = (uint)(up ? 2 : 0); SendInput(1, i, Marshal.SizeOf(typeof(INPUT))); }
  public static void Mouse(uint flags, int dx, int dy) { var i = new INPUT[1]; i[0].type = 0; i[0].u.mi.flags = flags; i[0].u.mi.dx = dx; i[0].u.mi.dy = dy; SendInput(1, i, Marshal.SizeOf(typeof(INPUT))); }
}
'@
if (-not ('McWinLayout' -as [type])) {
    Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public static class McWinLayout { [DllImport("user32.dll")] public static extern short VkKeyScan(char ch); }'
}
Add-Type -AssemblyName System.Drawing

function Get-McHandle([int]$ProcessId) { $h = [McWin]::FindByPid([uint32]$ProcessId); if ($h -eq [IntPtr]::Zero) { throw "no visible window for pid $ProcessId" }; $h }
function Test-McFocused([int]$ProcessId) { $fg = [McWin]::GetForegroundWindow(); [uint32]$p = 0; [void][McWin]::GetWindowThreadProcessId($fg, [ref]$p); return ($p -eq $ProcessId) }
function Focus-Mc([int]$ProcessId) { [McWin]::Mouse(0x0001, 0, 0); Start-Sleep -Milliseconds 100; $h = Get-McHandle $ProcessId; [void][McWin]::ShowWindow($h, 9); [McWin]::ForceFocus($h); Start-Sleep -Milliseconds 400; if (-not (Test-McFocused $ProcessId)) { throw 'could not focus the Minecraft window; aborting (nothing was sent)' } }
function Assert-McFocused([int]$ProcessId) { if (-not (Test-McFocused $ProcessId)) { throw 'Minecraft is not the foreground window; input aborted' } }

function Save-McShot([int]$ProcessId, [string]$Path) {
    $h = Get-McHandle $ProcessId; $r = New-Object McWin+RECT; [void][McWin]::GetClientRect($h, [ref]$r)
    $pt = New-Object McWin+POINT; [void][McWin]::ClientToScreen($h, [ref]$pt)
    $w = $r.R - $r.L; $hgt = $r.B - $r.T; if ($w -lt 50 -or $hgt -lt 50) { throw "window too small ($w x $hgt)" }
    $bmp = New-Object System.Drawing.Bitmap $w, $hgt; $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($pt.X, $pt.Y, 0, 0, (New-Object System.Drawing.Size $w, $hgt)); $g.Dispose()
    New-Item -ItemType Directory -Force (Split-Path $Path -Parent) | Out-Null
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose(); $Path
}
# virtual-key helpers (only while focused)
$script:VK = @{ T = 0x54; ENTER = 0x0D; ESC = 0x1B; SLASH = 0xBF; E = 0x45; Q = 0x51; F = 0x46; SHIFT = 0x10; CTRL = 0x11; SPACE = 0x20; W = 0x57; A = 0x41; S = 0x53; D = 0x44; D1 = 0x31; D2 = 0x32; D3 = 0x33; D4 = 0x34; D9 = 0x39; F3 = 0x72; TAB = 0x09; BACK = 0x08 }
function Press-McKey([int]$ProcessId, [string]$Name, [int]$HoldMs = 60) { Assert-McFocused $ProcessId; $vk = [uint16]$script:VK[$Name]; [McWin]::Key($vk, $false); Start-Sleep -Milliseconds $HoldMs; [McWin]::Key($vk, $true); Start-Sleep -Milliseconds 80 }
function Type-McChat([int]$ProcessId, [string]$Text) {
    # opens chat with T (or '/' for commands), types via per-char virtual keys, sends with Enter
    Assert-McFocused $ProcessId; Press-McKey $ProcessId 'T'; Start-Sleep -Milliseconds 250
    foreach ($ch in $Text.ToCharArray()) {
        Assert-McFocused $ProcessId
        # VkKeyScan maps the char under the ACTIVE keyboard layout (JIS vs US put ':' and '_' on different keys)
        $scan = [McWinLayout]::VkKeyScan($ch); if ($scan -eq -1) { throw "unsupported char '$ch'" }
        $vk = [uint16]($scan -band 0xFF); $needShift = (($scan -shr 8) -band 1) -eq 1
        if ($needShift) { [McWin]::Key(0x10, $false) }
        [McWin]::Key($vk, $false); Start-Sleep -Milliseconds 15; [McWin]::Key($vk, $true)
        if ($needShift) { [McWin]::Key(0x10, $true) }
        Start-Sleep -Milliseconds 25
    }
    Press-McKey $ProcessId 'ENTER'
}
function Click-Mc([int]$ProcessId, [string]$Button = 'right', [int]$HoldMs = 60) {
    Assert-McFocused $ProcessId
    $down = if ($Button -eq 'right') { 0x0008 } else { 0x0002 }; $up = if ($Button -eq 'right') { 0x0010 } else { 0x0004 }
    [McWin]::Mouse($down, 0, 0); Start-Sleep -Milliseconds $HoldMs; [McWin]::Mouse($up, 0, 0); Start-Sleep -Milliseconds 80
}

# move the cursor to client coordinates (x,y) of the Minecraft window and click; only while Minecraft is foreground
function Click-McAt([int]$ProcessId, [int]$X, [int]$Y, [string]$Button = 'left') {
    Assert-McFocused $ProcessId
    $h = Get-McHandle $ProcessId; $pt = New-Object McWin+POINT; [void][McWin]::ClientToScreen($h, [ref]$pt)
    [void][McWin]::SetCursorPos($pt.X + $X, $pt.Y + $Y); Start-Sleep -Milliseconds 120
    Click-Mc $ProcessId $Button
}

# keep the Minecraft window above other windows (z-order only; does not take focus) so screenshots show it even when another app owns the foreground
function Pin-Mc([int]$ProcessId, [bool]$On = $true) { [McWin]::Topmost((Get-McHandle $ProcessId), $On) }
