using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows.Forms;

// Two short, unmodified Control taps. Never consume or inject keyboard input.
internal sealed class ControlTapGesture {
    private readonly HashSet<int> held = new HashSet<int>();
    private long pressed, released = -1000;
    private bool clean;
    internal bool Feed(int key, bool down, long now, bool injected) {
        if (injected) { released = -1000; clean = false; return false; }
        bool control = key == 0xA2 || key == 0xA3 || key == 0x11;
        if (down) {
            if (!held.Add(key)) return false; // Ignore autorepeat.
            if (control && held.Count == 1) { pressed = now; clean = true; }
            else { clean = false; released = -1000; }
            return false;
        }
        if (!held.Remove(key) || !control) return false;
        if (!clean || held.Count != 0 || now - pressed > 250) { released = -1000; return false; }
        clean = false;
        if (now - released <= 400) { released = -1000; return true; }
        released = now; return false;
    }
}

internal static class ControlTapWatcher {
    private delegate IntPtr Hook(int code, IntPtr message, IntPtr data);
    [StructLayout(LayoutKind.Sequential)] private struct Keyboard { public uint Key, Scan, Flags, Time; public UIntPtr Extra; }
    [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr SetWindowsHookEx(int id, Hook callback, IntPtr module, uint thread);
    [DllImport("user32.dll")] private static extern bool UnhookWindowsHookEx(IntPtr hook);
    [DllImport("user32.dll")] private static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr message, IntPtr data);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr GetModuleHandle(string name);
    private static Hook callback;
    internal static void Run() {
        var gesture = new ControlTapGesture(); var timer = Stopwatch.StartNew();
        callback = delegate(int code, IntPtr message, IntPtr data) {
            if (code >= 0) {
                int m = message.ToInt32(); var key = (Keyboard)Marshal.PtrToStructure(data, typeof(Keyboard));
                if (m == 0x100 || m == 0x104 || m == 0x101 || m == 0x105) {
                    if (gesture.Feed((int)key.Key, m == 0x100 || m == 0x104, timer.ElapsedMilliseconds, (key.Flags & 0x10) != 0)) Console.WriteLine("toggle");
                }
            }
            return CallNextHookEx(IntPtr.Zero, code, message, data);
        };
        IntPtr handle = SetWindowsHookEx(13, callback, GetModuleHandle(null), 0);
        if (handle == IntPtr.Zero) throw new Exception("Unable to watch Control taps: " + Marshal.GetLastWin32Error());
        // Parent death/closed pipe also stops the otherwise windowless message loop.
        int closed = 0;
        var input = new System.Threading.Thread(delegate() { Console.ReadLine(); System.Threading.Interlocked.Exchange(ref closed, 1); }); input.IsBackground = true; input.Start();
        using (var lifetime = new Timer()) {
            lifetime.Interval = 250;
            lifetime.Tick += delegate { if (System.Threading.Volatile.Read(ref closed) != 0) Application.ExitThread(); };
            lifetime.Start(); Console.WriteLine("ready");
            try { Application.Run(); } finally { UnhookWindowsHookEx(handle); }
        }
    }
}
