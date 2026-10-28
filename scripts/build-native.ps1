$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$nativeCompiler=Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $nativeCompiler)) { throw 'Windows .NET Framework C# compiler not found' }
$nativeBin=Join-Path $projectRoot 'bin'
New-Item -ItemType Directory -Path $nativeBin -Force | Out-Null
& $nativeCompiler /nologo /optimize+ /platform:x64 /target:exe /r:System.Drawing.dll /r:System.Web.Extensions.dll /r:System.Windows.Forms.dll "/out:$nativeBin\ScreenCapture.exe" (Join-Path $projectRoot 'native\ScreenCapture.cs') (Join-Path $projectRoot 'native\ScrollStitcher.cs') (Join-Path $projectRoot 'native\ControlTapWatcher.cs')
if ($LASTEXITCODE -ne 0) { throw 'GDI screenshot helper compilation failed' }
Write-Output 'Native GDI screenshot helper built'
