$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$nativeCompiler=Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$testExe=Join-Path $projectRoot '.toolchain\NativeCaptureTests.exe'
& $nativeCompiler /nologo /optimize+ /platform:x64 /target:exe /r:System.Drawing.dll /r:System.Windows.Forms.dll "/out:$testExe" (Join-Path $projectRoot 'native\ScrollStitcher.cs') (Join-Path $projectRoot 'native\ControlTapWatcher.cs') (Join-Path $projectRoot 'test\NativeCaptureTests.cs')
if ($LASTEXITCODE -ne 0) { throw 'Native test compilation failed' }
& $testExe
if ($LASTEXITCODE -ne 0) { throw 'Native capture tests failed' }
