using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

// Match vertical motion in changing columns, excluding stationary screen chrome.
// Store only newly revealed bands; reversals move the viewport without duplicating it.
internal sealed class ScrollStitcher : IDisposable {
    private const int Columns = 48;
    private Bitmap previous, footer;
    private byte[] samples;
    private readonly List<Bitmap> bands = new List<Bitmap>();
    private int top, bottom, position, extent, height;
    private bool aligned;
    internal int Height { get { return height; } }
    internal ScrollStitcher(Bitmap first) {
        previous = (Bitmap)first.Clone(); samples = Sample(first); bottom = first.Height;
        bands.Add((Bitmap)first.Clone()); height = first.Height;
    }
    private static byte[] Sample(Bitmap image) {
        var area = new Rectangle(0, 0, image.Width, image.Height);
        BitmapData data = image.LockBits(area, ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);
        try {
            var row = new byte[Math.Abs(data.Stride)]; var result = new byte[image.Height * Columns * 3];
            for (int y = 0; y < image.Height; y++) {
                Marshal.Copy(IntPtr.Add(data.Scan0, y * data.Stride), row, 0, row.Length);
                for (int c = 0; c < Columns; c++) {
                    int x = (c + 1) * image.Width / (Columns + 1);
                    for (int k = 0; k < 3; k++) result[(y * Columns + c) * 3 + k] = row[x * 3 + k];
                }
            }
            return result;
        } finally { image.UnlockBits(data); }
    }
    private static int Difference(byte[] a, int ai, byte[] b, int bi) {
        return Math.Abs(a[ai] - b[bi]) + Math.Abs(a[ai + 1] - b[bi + 1]) + Math.Abs(a[ai + 2] - b[bi + 2]);
    }
    private double Score(byte[] next, List<int> columns, int from, int to, int shift, int rowStep) {
        long error = 0; int count = 0;
        int start = Math.Max(from, from - shift), end = Math.Min(to, to - shift);
        for (int y = start; y < end; y += rowStep) foreach (int c in columns) {
            error += Difference(samples, ((y + shift) * Columns + c) * 3, next, (y * Columns + c) * 3); count += 3;
        }
        return count == 0 ? 255 : (double)error / count;
    }
    // false means motion could not be aligned. The caller must never silently send an incomplete image.
    internal bool Add(Bitmap next) {
        if (next.Width != previous.Width || next.Height != previous.Height) throw new Exception("显示器尺寸已改变，请重新开始长截屏");
        byte[] current = Sample(next); int h = next.Height;
        var changes = new int[Columns]; int changedTop = h, changedBottom = 0, changedRows = 0;
        for (int y = 0; y < h; y++) {
            int rowChanges = 0;
            for (int c = 0; c < Columns; c++) if (Difference(samples, (y * Columns + c) * 3, current, (y * Columns + c) * 3) > 36) { changes[c]++; rowChanges++; }
            if (rowChanges >= 3) { changedTop = Math.Min(changedTop, y); changedBottom = y + 1; changedRows++; }
        }
        if (changedRows < 4) return true; // Static frame or cursor/blinking caret.
        var columns = new List<int>(); int maximum = 0;
        foreach (int n in changes) maximum = Math.Max(maximum, n);
        for (int c = 0; c < Columns; c++) if (changes[c] >= Math.Max(4, maximum / 3)) columns.Add(c);
        if (columns.Count < 3) return false;
        int from = aligned ? top : changedTop, to = aligned ? bottom : changedBottom;
        int span = to - from, overlap = Math.Max(32, span / 3), limit = span - overlap;
        if (limit < 2) return false;
        var candidates = new List<KeyValuePair<int, double>>();
        for (int shift = -limit; shift <= limit; shift++) {
            if (shift == 0 || position + shift < 0) continue;
            double score = Score(current, columns, from, to, shift, Math.Max(1, span / 32));
            candidates.Add(new KeyValuePair<int, double>(shift, score));
        }
        candidates.Sort((a, b) => a.Value.CompareTo(b.Value));
        int best = 0; double bestScore = Double.MaxValue;
        for (int i = 0; i < Math.Min(16, candidates.Count); i++) {
            int shift = candidates[i].Key;
            double score = Score(current, columns, from, to, shift, 2);
            if (score < bestScore) { bestScore = score; best = shift; }
        }
        double still = Score(current, columns, from, to, 0, 2);
        if (best == 0 || bestScore > 12 || bestScore >= still * 0.65) return false;
        if (!aligned) {
            top = from; bottom = to; aligned = true; extent = bottom;
            bands[0].Dispose(); bands[0] = previous.Clone(new Rectangle(0, 0, previous.Width, bottom), PixelFormat.Format24bppRgb);
            height = bottom;
            if (bottom < h) footer = previous.Clone(new Rectangle(0, bottom, previous.Width, h - bottom), PixelFormat.Format24bppRgb);
        }
        int newPosition = position + best, newBottom = newPosition + bottom;
        if (newBottom > extent) {
            int rows = newBottom - extent;
            if (height + rows + h - bottom > Math.Min(32768, 32000000 / next.Width)) throw new Exception("长截屏已达到长度上限，请缩短范围后重试");
            bands.Add(next.Clone(new Rectangle(0, bottom - rows, next.Width, rows), PixelFormat.Format24bppRgb));
            height += rows; extent = newBottom;
            if (footer != null) { footer.Dispose(); footer = next.Clone(new Rectangle(0, bottom, next.Width, h - bottom), PixelFormat.Format24bppRgb); }
        }
        position = newPosition; previous.Dispose(); previous = (Bitmap)next.Clone(); samples = current;
        return true;
    }
    internal Bitmap Render() {
        int total = height + (footer == null ? 0 : footer.Height);
        var result = new Bitmap(previous.Width, total, PixelFormat.Format24bppRgb);
        using (Graphics g = Graphics.FromImage(result)) {
            int y = 0; foreach (Bitmap band in bands) { g.DrawImageUnscaled(band, 0, y); y += band.Height; }
            if (footer != null) g.DrawImageUnscaled(footer, 0, y);
        }
        return result;
    }
    public void Dispose() { previous.Dispose(); if (footer != null) footer.Dispose(); foreach (Bitmap band in bands) band.Dispose(); }
}

