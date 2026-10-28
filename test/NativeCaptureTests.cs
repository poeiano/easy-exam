using System;
using System.Drawing;
using System.Drawing.Imaging;

internal static class NativeCaptureTests {
    private static void Check(bool result, string message) { if (!result) throw new Exception(message); }
    private static Bitmap Frame(Bitmap document, int offset, bool sidebar) {
        var frame = new Bitmap(480, 360, PixelFormat.Format24bppRgb);
        using (Graphics g = Graphics.FromImage(frame)) {
            g.Clear(Color.FromArgb(30, 50, 80));
            g.DrawImage(document, new Rectangle(0, 40, 480, 280), new Rectangle(0, offset, 480, 280), GraphicsUnit.Pixel);
            if (sidebar) g.FillRectangle(Brushes.LightGray, 0, 40, 140, 280);
        }
        return frame;
    }
    private static void Stitch(bool text, bool sidebar) {
        using (var document = new Bitmap(480, 1400, PixelFormat.Format24bppRgb)) {
            if (text) {
                using (Graphics g = Graphics.FromImage(document)) using (var font = new Font("Arial", 12)) {
                    g.Clear(Color.White);
                    for (int i = 0; i < 60; i++) g.DrawString("Question " + i + ": scrolling capture example " + (i * 7919), font, Brushes.Black, 150, i * 23);
                }
            } else {
                var random = new Random(722);
                for (int y = 0; y < document.Height; y++) for (int x = 0; x < document.Width; x++) document.SetPixel(x, y, Color.FromArgb(random.Next(256), random.Next(256), random.Next(256)));
            }
            using (Bitmap first = Frame(document, 0, sidebar)) using (var stitcher = new ScrollStitcher(first)) {
                foreach (int offset in new int[] { 0, 47, 91, 137, 90, 130, 230, 230, 360, 420 }) {
                    using (Bitmap next = Frame(document, offset, sidebar)) Check(stitcher.Add(next), "Could not align " + offset + " text=" + text + " sidebar=" + sidebar);
                }
                using (Bitmap result = stitcher.Render()) {
                    Check(result.Height == 780, "Incorrect output height " + result.Height);
                    if (!sidebar) {
                        // Assert real pixels across every seam, header and footer, not just dimensions.
                        for (int y = 0; y < result.Height; y++) for (int x = 0; x < result.Width; x += 13) {
                            Color expected = y < 40 || y >= 740 ? Color.FromArgb(30, 50, 80) : document.GetPixel(x, y - 40);
                            Check(result.GetPixel(x, y).ToArgb() == expected.ToArgb(), "Pixel seam at " + x + "," + y);
                        }
                    }
                }
            }
        }
    }
    private static void Gestures() {
        var gesture = new ControlTapGesture();
        Check(!gesture.Feed(0xA2, true, 0, false), "Down must not toggle");
        Check(!gesture.Feed(0xA2, false, 60, false), "First tap must not toggle");
        gesture.Feed(0xA2, true, 150, false);
        Check(gesture.Feed(0xA2, false, 210, false), "Double tap missing");
        gesture.Feed(0xA2, true, 270, false);
        Check(!gesture.Feed(0xA2, false, 300, false), "Third tap must not reuse previous pair");
        gesture.Feed(0xA2, true, 900, false); gesture.Feed(0x43, true, 920, false); gesture.Feed(0x43, false, 940, false);
        Check(!gesture.Feed(0xA2, false, 960, false), "Ctrl+C must not toggle");
        gesture.Feed(0xA2, true, 1000, false);
        Check(!gesture.Feed(0xA2, false, 1050, false), "Combo must reset double tap");
        gesture.Feed(0xA2, true, 1200, false); gesture.Feed(0xA2, true, 1400, false);
        Check(!gesture.Feed(0xA2, false, 1700, false), "Held Control must not toggle");
        gesture.Feed(0xA3, true, 1800, false);
        Check(!gesture.Feed(0xA3, false, 1840, false), "Long hold must reset");
        gesture.Feed(0xA3, true, 1900, false);
        Check(gesture.Feed(0xA3, false, 1940, false), "Right Control double tap missing");
        gesture.Feed(0xA2, true, 2000, true); Check(!gesture.Feed(0xA2, false, 2050, true), "Injected key toggled");
        Console.WriteLine("Control gesture tests passed");
    }
    private static void SparseText() {
        using (var document = new Bitmap(960, 2200, PixelFormat.Format24bppRgb)) using (var font = new Font("Courier New", 12)) {
            for (int phase = 0; phase < 8; phase++) {
                using (Graphics g = Graphics.FromImage(document)) {
                    g.Clear(Color.White);
                    for (int row = 0; row < 32; row++) {
                        using (var brush = new SolidBrush(Color.FromArgb(row * 17 % 170, row * 29 % 170, row * 43 % 170)))
                            g.DrawString("Question " + row + ": verify sparse scrolling text " + row * 7919, font, brush, 50, row * 69 + phase * 3);
                        g.DrawLine(Pens.LightGray, 50, row * 69 + 65, 900, row * 69 + 65);
                    }
                }
                using (var frame = new Bitmap(960, 1200, PixelFormat.Format24bppRgb)) {
                    Action<int> draw = offset => { using (Graphics g = Graphics.FromImage(frame)) { g.Clear(Color.Navy); g.DrawImage(document, new Rectangle(0, 75, 960, 1050), new Rectangle(0, offset, 960, 1050), GraphicsUnit.Pixel); } };
                    draw(0);
                    using (var stitcher = new ScrollStitcher(frame)) {
                        foreach (int offset in new int[] { 100, 200, 300, 400, 500, 600 }) { draw(offset); Check(stitcher.Add(frame), "Sparse text missed shift " + offset + " phase " + phase); }
                        using (Bitmap result = stitcher.Render()) Check(result.Height == 1800, "Sparse text height incorrect");
                    }
                }
            }
        }
        Console.WriteLine("Sparse text sampling tests passed");
    }
    private static int Main() {
        try {
            Gestures(); Stitch(false, false); Stitch(true, false); Stitch(true, true); SparseText();
            using (var first = new Bitmap(480, 360, PixelFormat.Format24bppRgb)) using (var stitcher = new ScrollStitcher(first)) {
                Check(stitcher.Add(first), "Static screen rejected");
                using (var next = new Bitmap(480, 360, PixelFormat.Format24bppRgb)) {
                    using (Graphics g = Graphics.FromImage(next)) g.Clear(Color.Red);
                    Check(!stitcher.Add(next), "Unrelated screen must not be stitched");
                }
            }
            Console.WriteLine("Scroll stitching: text, textured pixels, fixed chrome/sidebar, reversal, duplicate and unrelated frames passed"); return 0;
        } catch (Exception e) { Console.Error.WriteLine(e); return 1; }
    }
}

