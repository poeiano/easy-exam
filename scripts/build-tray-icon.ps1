$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path -Parent $PSScriptRoot
[xml]$source = Get-Content -LiteralPath (Join-Path $projectRoot 'assets\tray.svg') -Raw
$size = [int]$source.svg.width
$supersampling = 8
$canvas = New-Object Drawing.Bitmap ($size * $supersampling), ($size * $supersampling), ([Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [Drawing.Graphics]::FromImage($canvas)
$output = $null
$outputGraphics = $null
try {
    $graphics.Clear([Drawing.Color]::Transparent)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.ScaleTransform($supersampling, $supersampling)
    $rect = $source.svg.rect
    $radius = [float]$rect.rx
    $width = [float]$rect.width
    $height = [float]$rect.height
    $diameter = 2 * $radius
    $outline = New-Object Drawing.Drawing2D.GraphicsPath
    $background = New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml($rect.fill))
    try {
        $outline.AddArc(0, 0, $diameter, $diameter, 180, 90)
        $outline.AddArc(($width - $diameter), 0, $diameter, $diameter, 270, 90)
        $outline.AddArc(($width - $diameter), ($height - $diameter), $diameter, $diameter, 0, 90)
        $outline.AddArc(0, ($height - $diameter), $diameter, $diameter, 90, 90)
        $outline.CloseFigure()
        $graphics.FillPath($background, $outline)
    } finally { $outline.Dispose(); $background.Dispose() }
    foreach ($circle in $source.svg.circle) {
        $brush = New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml($circle.fill))
        $r = [float]$circle.r
        try { $graphics.FillEllipse($brush, ([float]$circle.cx - $r), ([float]$circle.cy - $r), (2 * $r), (2 * $r)) }
        finally { $brush.Dispose() }
    }
    $output = New-Object Drawing.Bitmap $size, $size, ([Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $outputGraphics = [Drawing.Graphics]::FromImage($output)
    $outputGraphics.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
    $outputGraphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $outputGraphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $outputGraphics.DrawImage($canvas, 0, 0, $size, $size)
    foreach ($corner in @(@(0,0), @(($size-1),0), @(0,($size-1)), @(($size-1),($size-1)))) {
        if ($output.GetPixel($corner[0], $corner[1]).A -ne 0) { throw 'Tray corners must be transparent' }
    }
    $output.Save((Join-Path $projectRoot 'assets\tray.png'), [Drawing.Imaging.ImageFormat]::Png)
} finally {
    if ($outputGraphics) { $outputGraphics.Dispose() }
    if ($output) { $output.Dispose() }
    $graphics.Dispose()
    $canvas.Dispose()
}
Write-Output 'Rounded tray icon built: assets/tray.png (32 x 32, transparent corners)'
