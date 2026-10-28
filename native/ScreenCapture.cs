using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Web.Script.Serialization;
using System.Threading;

// Independent implementation of the observed Win32 GDI screen-copy behavior.
// No windows, overlays, clipboard use, disk screenshots or focus manipulation.
internal static class ScreenCapture {
    [StructLayout(LayoutKind.Sequential)] private struct Rect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct MonitorInfo {
        public int Size; public Rect Monitor, Work; public uint Flags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string Device;
    }
    private delegate bool MonitorCallback(IntPtr monitor, IntPtr dc, ref Rect area, IntPtr data);
    [DllImport("user32.dll")] private static extern bool EnumDisplayMonitors(IntPtr dc, IntPtr clip, MonitorCallback callback, IntPtr data);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "GetMonitorInfoW")] private static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo info);
    [DllImport("user32.dll")] private static extern bool SetProcessDpiAwarenessContext(IntPtr context);
    [DllImport("user32.dll")] private static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr window);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr window, IntPtr dc);
    [DllImport("gdi32.dll", SetLastError = true)] private static extern bool BitBlt(IntPtr dest, int x, int y, int w, int h, IntPtr source, int sx, int sy, uint operation);
    private sealed class Display {
        public string id, name; public int x, y, width, height; public bool primary;
    }
    private static List<Display> Displays() {
        var result = new List<Display>();
        if (!EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, delegate(IntPtr m, IntPtr dc, ref Rect rect, IntPtr data) {
            var info = new MonitorInfo(); info.Size = Marshal.SizeOf(typeof(MonitorInfo));
            if (GetMonitorInfo(m, ref info)) {
                var d = new Display(); d.id = info.Device; d.x = info.Monitor.Left; d.y = info.Monitor.Top;
                d.width = info.Monitor.Right - d.x; d.height = info.Monitor.Bottom - d.y; d.primary = (info.Flags & 1) != 0;
                d.name = info.Device.Replace("\\\\.\\", "") + "  " + d.width + " × " + d.height + (d.primary ? " (主显示器)" : ""); result.Add(d);
            }
            return true;
        }, IntPtr.Zero) || result.Count == 0) throw new Exception("No active desktop monitor available");
        result.Sort((a, b) => b.primary.CompareTo(a.primary)); return result;
    }
    private static int Main(string[] args) {
        try {
            Console.OutputEncoding = new UTF8Encoding(false);
            if (args.Length == 1 && args[0] == "watch-control") { ControlTapWatcher.Run(); return 0; }
            try { if (!SetProcessDpiAwarenessContext(new IntPtr(-4))) SetProcessDPIAware(); } catch (EntryPointNotFoundException) { SetProcessDPIAware(); }
            var monitors = Displays();
            if (args.Length == 1 && args[0] == "list") { Console.Write(new JavaScriptSerializer().Serialize(monitors)); return 0; }
            if (args.Length != 4 || (args[0] != "capture" && args[0] != "scroll")) throw new ArgumentException("Usage: list | watch-control | capture/scroll monitorId maxWidth quality");
            Display display = monitors.Find(d => d.id == args[1]); if (display == null) throw new Exception("Selected monitor is no longer available");
            int maxWidth, quality;
            if (!Int32.TryParse(args[2], out maxWidth) || maxWidth < 800 || maxWidth > 3840 || !Int32.TryParse(args[3], out quality) || quality < 50 || quality > 95) throw new ArgumentException("Invalid capture dimensions or quality");
            if (args[0] == "scroll") { Scroll(display, maxWidth, quality); return 0; }
            using (var bitmap = Capture(display, maxWidth)) WriteJpeg(bitmap, quality);
            return 0;
        } catch (Exception e) { Console.Error.WriteLine(e.Message); return 1; }
    }
    private static Bitmap Capture(Display display, int maxWidth) {
        using (var bitmap = new Bitmap(display.width, display.height, PixelFormat.Format24bppRgb)) {
            using (var graphics = Graphics.FromImage(bitmap)) {
                    IntPtr source = GetDC(IntPtr.Zero); if (source == IntPtr.Zero) throw new Exception("Desktop DC unavailable");
                    IntPtr destination = graphics.GetHdc();
                    try { if (!BitBlt(destination,0,0,display.width,display.height,source,display.x,display.y,0x00CC0020)) throw new Exception("GDI screen copy failed: " + Marshal.GetLastWin32Error()); }
                    finally { graphics.ReleaseHdc(destination); ReleaseDC(IntPtr.Zero,source); }
            }
            double scale = Math.Min(1.0, Math.Min((double)maxWidth/display.width, 4096.0/display.height));
            int width = Math.Max(1,(int)Math.Round(display.width*scale)), height = Math.Max(1,(int)Math.Round(display.height*scale));
            var output = new Bitmap(width,height,PixelFormat.Format24bppRgb);
            using (var g = Graphics.FromImage(output)) { g.InterpolationMode=InterpolationMode.HighQualityBicubic; g.DrawImage(bitmap,0,0,width,height); }
            return output;
        }
    }
    private static void WriteJpeg(Bitmap bitmap, int quality) {
        double scale = Math.Min(1.0, 8192.0 / bitmap.Height);
        using (var output = new Bitmap(Math.Max(1, (int)Math.Round(bitmap.Width * scale)), Math.Max(1, (int)Math.Round(bitmap.Height * scale)), PixelFormat.Format24bppRgb)) {
            using (var g = Graphics.FromImage(output)) { g.InterpolationMode = InterpolationMode.HighQualityBicubic; g.DrawImage(bitmap, 0, 0, output.Width, output.Height); }
                    ImageCodecInfo encoder=Array.Find(ImageCodecInfo.GetImageEncoders(), x=>x.MimeType=="image/jpeg");
                    using (var parameters = new EncoderParameters(1)) {
                        parameters.Param[0]=new EncoderParameter(System.Drawing.Imaging.Encoder.Quality,(long)quality);
                        using (var stream=new MemoryStream()) {
                            output.Save(stream,encoder,parameters);
                            if(stream.Length>6*1024*1024)throw new Exception("Image exceeds transfer limit; lower JPEG quality");
                            byte[] bytes=stream.ToArray();using(var stdout=Console.OpenStandardOutput()){stdout.Write(bytes,0,bytes.Length);}
                        }
                    }
        }
    }
    private static void Scroll(Display display, int width, int quality) {
        string command = null; int inputDone = 0;
        var reader = new Thread(delegate() { command = Console.ReadLine(); Interlocked.Exchange(ref inputDone, 1); }); reader.IsBackground = true; reader.Start();
        using (Bitmap first = Capture(display, width)) using (var stitcher = new ScrollStitcher(first)) {
            Console.Error.WriteLine("{\"state\":\"capturing\",\"height\":" + stitcher.Height + "}");
            bool matched = true; int misses = 0;
            while (Volatile.Read(ref inputDone) == 0) {
                Thread.Sleep(160);
                if (Volatile.Read(ref inputDone) != 0) break;
                using (Bitmap frame = Capture(display, width)) matched = stitcher.Add(frame);
                misses = matched ? 0 : misses + 1;
                Console.Error.WriteLine("{\"state\":\"capturing\",\"height\":" + stitcher.Height + ",\"unmatched\":" + (!matched ? "true" : "false") + "}");
                if (misses >= 12) throw new Exception("无法匹配滚动内容，请保持页面不切换并缓慢滚动后重试");
            }
            if (command != "finish") return; // Cancel/parent exit discards all pixels.
            using (Bitmap last = Capture(display, width)) matched = stitcher.Add(last);
            if (!matched) throw new Exception("结束画面与前段没有足够重叠，请减慢滚动速度后重试");
            using (Bitmap result = stitcher.Render()) WriteJpeg(result, quality);
        }
    }
}
