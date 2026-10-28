param([string]$AndroidRoot='D:\it\Android')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.toolchain'
$sdkRoot = Join-Path $AndroidRoot 'Sdk'
$androidLogs = Join-Path $AndroidRoot 'Logs'
$androidDownloads = Join-Path $AndroidRoot 'Downloads'
New-Item -ItemType Directory -Path $androidLogs,$androidDownloads -Force | Out-Null
New-Item -ItemType Directory -Path $toolsRoot -Force | Out-Null
$env:JAVA_HOME = 'D:\it\java\jdk-21.0.12.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$sdkArchive = Join-Path $androidDownloads 'commandlinetools-win-15859902_latest.zip'
$sdkManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    & curl.exe --fail --location --retry 5 --retry-all-errors --continue-at - --output $sdkArchive 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
    if ($LASTEXITCODE -ne 0) { throw 'SDK download failed' }
    if ((Get-FileHash -LiteralPath $sdkArchive -Algorithm SHA256).Hash -ne '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a') { throw 'Android SDK checksum mismatch' }
    $bootstrap = Join-Path $androidDownloads 'command-tools-extracted'
    Expand-Archive -LiteralPath $sdkArchive -DestinationPath $bootstrap -Force
    New-Item -ItemType Directory -Path (Join-Path $sdkRoot 'cmdline-tools') -Force | Out-Null
    $extracted = (Resolve-Path -LiteralPath (Join-Path $bootstrap 'cmdline-tools')).Path
    $latest = [IO.Path]::GetFullPath((Join-Path $sdkRoot 'cmdline-tools\latest'))
    $resolvedAndroidRoot = [IO.Path]::GetFullPath($AndroidRoot).TrimEnd('\')
    if (-not $extracted.StartsWith($resolvedAndroidRoot+'\',[StringComparison]::OrdinalIgnoreCase) -or -not $latest.StartsWith($resolvedAndroidRoot+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected SDK extraction path' }
    Move-Item -LiteralPath $extracted -Destination $latest
}
1..80 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" --licenses *> (Join-Path $androidLogs 'sdk-licenses.log')
& $sdkManager "--sdk_root=$sdkRoot" 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0' *> (Join-Path $androidLogs 'sdk-install.log')
if ($LASTEXITCODE -ne 0) { throw ('SDK install failed; inspect '+(Join-Path $androidLogs 'sdk-install.log')) }
$gradleDir = Join-Path $toolsRoot 'gradle-8.13'
if (-not (Test-Path -LiteralPath (Join-Path $gradleDir 'bin\gradle.bat'))) {
    $gradleZip = Join-Path $toolsRoot 'gradle.zip'
    & curl.exe --fail --location --retry 5 --retry-all-errors --continue-at - --output $gradleZip 'https://services.gradle.org/distributions/gradle-8.13-bin.zip'
    if ($LASTEXITCODE -ne 0) { throw 'Gradle download failed' }
    $gradleSha = [string](& curl.exe --fail --silent --location 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256')
    $gradleSha = $gradleSha.Trim()
    if ((Get-FileHash -LiteralPath $gradleZip -Algorithm SHA256).Hash -ne $gradleSha) { throw 'Gradle checksum mismatch' }
    Expand-Archive -LiteralPath $gradleZip -DestinationPath $toolsRoot
}
$sdkProperty = $sdkRoot.Replace('\','/').Replace(':','\:')
New-Item -ItemType Directory -Path (Join-Path $projectRoot 'android') -Force | Out-Null
"sdk.dir=$sdkProperty" | Set-Content -LiteralPath (Join-Path $projectRoot 'android\local.properties') -Encoding ascii
Write-Output "ANDROID_SDK_READY=$sdkRoot"
Write-Output "GRADLE_READY=$gradleDir"
