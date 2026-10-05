<#
.SYNOPSIS
    Builds, signs, tags and publishes a Morgenbriefing release (GitHub release with the APK).

.DESCRIPTION
    1. Refuses to run on a dirty working tree, outside of main, or when the tag already exists.
    2. Bumps MB_VERSION_NAME to -Version and increments MB_VERSION_CODE in gradle.properties.
    3. Runs ./gradlew testDebugUnitTest assembleRelease (JAVA_HOME defaults to the local JDK 17).
    4. Verifies the release APK: signature (apksigner, signer CN=CedrickGD) and package/version (aapt2).
    5. Commits "release: v<Version>", tags v<Version>, pushes main and the tag.
    6. Creates the GitHub release with gh and uploads the APK as Morgenbriefing-v<Version>.apk.
    7. With -Install: "adb install -r" on every connected physical device (emulators are skipped).

    -DryRun does all checks, the version bump, the build and the APK verification, then restores
    gradle.properties and stops before commit, tag, push and release. A dirty tree is only a
    warning in a dry run. The in-app updater refuses APKs that are not signed with the installed
    app's key, so never publish a debug-signed build.

    Compatible with Windows PowerShell 5.1.

.EXAMPLE
    .\scripts\release.ps1 -Version 1.1.0 -Notes "Themen sortieren, Artikel in der App lesen"

.EXAMPLE
    .\scripts\release.ps1 -Version 1.1.0 -Notes "Test" -DryRun
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [string]$Notes,

    [switch]$Install,

    [switch]$DryRun
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

$DefaultJavaHome = 'C:/Users/cedri/.jdks/jdk-17.0.20.1+1'
$DefaultSdk = 'C:/Users/cedri/AppData/Local/Android/Sdk'
$DefaultGh = 'C:/Users/cedri/AppData/Local/Microsoft/WinGet/Links/gh.exe'
$ExpectedSignerCn = 'CedrickGD'
$PackageName = 'com.cedrickgd.morgenbriefing'
$CoAuthor = 'Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$Root = Split-Path -Parent $PSScriptRoot
$PropsPath = Join-Path $Root 'gradle.properties'
$Tag = "v$Version"

function Write-Step([string]$Text) { Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "    ok: $Text" -ForegroundColor Green }
function Write-Warn([string]$Text) { Write-Host "    warning: $Text" -ForegroundColor Yellow }

# Runs a native command, echoes it and throws when it fails. Output goes to the console.
function Invoke-Native([string]$File, [string[]]$Arguments) {
    Write-Host "    > $File $($Arguments -join ' ')" -ForegroundColor DarkGray
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "'$File $($Arguments -join ' ')' failed with exit code $LASTEXITCODE." }
}

# Runs a native command and returns its standard output lines (stderr passes through).
# Call sites wrap the result in @() - PowerShell unrolls empty and one-element arrays.
function Get-NativeOutput([string]$File, [string[]]$Arguments, [switch]$AllowFailure) {
    $output = & $File @Arguments
    if (-not $AllowFailure -and $LASTEXITCODE -ne 0) {
        throw "'$File $($Arguments -join ' ')' failed with exit code $LASTEXITCODE."
    }
    return @($output | Where-Object { $null -ne $_ } | ForEach-Object { "$_" })
}

# "1.2.3" or "1.2.3-beta.1" -> [version]1.2.3 (pre-release part ignored for the comparison).
function ConvertTo-CoreVersion([string]$Text) {
    $core = ($Text -split '-', 2)[0]
    return [version]$core
}

function Get-PropertyValue([string]$Content, [string]$Key) {
    $match = [regex]::Match($Content, "(?m)^[ \t]*$([regex]::Escape($Key))[ \t]*=[ \t]*([^\r\n]*?)[ \t]*\r?$")
    if (-not $match.Success) { throw "$Key is missing in gradle.properties." }
    return $match.Groups[1].Value
}

# Replaces the value of the first "Key=value" line; keeps spacing and line endings.
function Set-PropertyValue([string]$Content, [string]$Key, [string]$Value) {
    $null = Get-PropertyValue $Content $Key
    $regex = New-Object System.Text.RegularExpressions.Regex("(?m)^([ \t]*$([regex]::Escape($Key))[ \t]*=[ \t]*)[^\r\n]*?([ \t]*\r?)$")
    $evaluator = [System.Text.RegularExpressions.MatchEvaluator]({ param($m) $m.Groups[1].Value + $Value + $m.Groups[2].Value }.GetNewClosure())
    return $regex.Replace($Content, $evaluator, 1)
}

function Find-Sdk {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    $localProps = Join-Path $Root 'local.properties'
    if (Test-Path $localProps) {
        $sdkLine = Select-String -Path $localProps -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
        if ($sdkLine) {
            $dir = $sdkLine.Matches[0].Groups[1].Value.Trim() -replace '\\:', ':' -replace '\\\\', '\'
            if (Test-Path $dir) { return $dir }
        }
    }
    if (Test-Path $DefaultSdk) { return $DefaultSdk }
    throw 'Android SDK not found (set ANDROID_HOME).'
}

# Newest build-tools folder that contains the requested tool (e.g. apksigner.bat, aapt2.exe).
function Find-BuildTool([string]$Sdk, [string]$Name) {
    $buildTools = Join-Path $Sdk 'build-tools'
    if (-not (Test-Path $buildTools)) { throw "No build-tools in $Sdk." }
    $dirs = Get-ChildItem -Path $buildTools -Directory | Sort-Object -Descending -Property @{
        Expression = { try { [version](($_.Name -split '-', 2)[0]) } catch { [version]'0.0' } }
    }
    foreach ($dir in $dirs) {
        $tool = Join-Path $dir.FullName $Name
        if (Test-Path $tool) { return $tool }
    }
    throw "$Name not found in $buildTools."
}

function Find-Gh {
    if (Test-Path $DefaultGh) { return $DefaultGh }
    $command = Get-Command gh -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return $null
}

$originalProps = $null
$oldName = $null
$propsBumped = $false
$released = $false
# How far a real release got: '' -> committed -> tagged -> pushed (for recovery hints).
$stage = ''

Push-Location $Root
try {
    if ($DryRun) { Write-Host 'DRY RUN - nothing will be committed, tagged, pushed or released.' -ForegroundColor Magenta }

    # --- 1. Preconditions -------------------------------------------------------------------
    Write-Step "Checking preconditions for $Tag"
    if ($Version -notmatch '^\d+\.\d+\.\d+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$') {
        throw "Version '$Version' is not X.Y.Z (optionally X.Y.Z-pre.1)."
    }
    if ([string]::IsNullOrWhiteSpace($Notes)) { throw 'Release notes (-Notes) must not be empty.' }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'git is not on PATH.' }

    $branch = (Get-NativeOutput git @('rev-parse', '--abbrev-ref', 'HEAD')) -join ''
    if ($branch -ne 'main') {
        if ($DryRun) { Write-Warn "on branch '$branch', a real release must run on main" } else { throw "Releases are made from main (current branch: $branch)." }
    } else { Write-Ok 'on main' }

    $dirty = @(Get-NativeOutput git @('status', '--porcelain'))
    if ($dirty.Count -gt 0) {
        if ($DryRun) { Write-Warn "working tree has $($dirty.Count) uncommitted change(s)" } else { throw "Working tree is not clean:`n$($dirty -join "`n")" }
    } else { Write-Ok 'working tree clean' }

    if (@(Get-NativeOutput git @('tag', '--list', $Tag)).Count -gt 0) { throw "Tag $Tag already exists locally." }
    $remoteTag = @(Get-NativeOutput git @('ls-remote', '--tags', 'origin', "refs/tags/$Tag") -AllowFailure)
    if ($LASTEXITCODE -ne 0) {
        if ($DryRun) { Write-Warn 'could not reach origin to check remote tags' } else { throw 'Could not reach origin to check for an existing tag.' }
    } elseif ($remoteTag.Count -gt 0) {
        throw "Tag $Tag already exists on origin."
    } else { Write-Ok "tag $Tag is free" }

    $originalProps = [System.IO.File]::ReadAllText($PropsPath)
    $oldName = Get-PropertyValue $originalProps 'MB_VERSION_NAME'
    $oldCodeText = Get-PropertyValue $originalProps 'MB_VERSION_CODE'
    $oldCode = 0
    if (-not [int]::TryParse($oldCodeText, [ref]$oldCode)) { throw "MB_VERSION_CODE '$oldCodeText' is not a number." }
    $newCode = $oldCode + 1
    if ((ConvertTo-CoreVersion $Version) -lt (ConvertTo-CoreVersion $oldName)) {
        throw "Version $Version is lower than the current $oldName."
    }
    if ($Version -eq $oldName) { throw "Version $Version is already the current version." }
    Write-Ok "version $oldName ($oldCode) -> $Version ($newCode)"

    $keystoreProps = Join-Path $Root 'keystore.properties'
    if (-not (Test-Path $keystoreProps)) {
        throw 'keystore.properties is missing - the release must be signed with the release key (see README).'
    }
    $storeLine = Select-String -Path $keystoreProps -Pattern '^\s*FILE\s*=\s*(.+)$' | Select-Object -First 1
    if (-not $storeLine) { throw 'keystore.properties has no FILE entry.' }
    $storeFile = $storeLine.Matches[0].Groups[1].Value.Trim()
    if (-not [System.IO.Path]::IsPathRooted($storeFile)) { $storeFile = Join-Path $Root $storeFile }
    if (-not (Test-Path $storeFile)) { throw "Keystore not found: $storeFile" }
    Write-Ok 'release keystore present'

    $gh = Find-Gh
    if (-not $gh) {
        if ($DryRun) { Write-Warn 'gh (GitHub CLI) not found' } else { throw "gh not found ($DefaultGh)." }
    } else { Write-Ok "gh: $gh" }

    if (-not $env:JAVA_HOME) {
        if (-not (Test-Path $DefaultJavaHome)) { throw "JAVA_HOME is not set and $DefaultJavaHome does not exist." }
        $env:JAVA_HOME = $DefaultJavaHome
    }
    Write-Ok "JAVA_HOME: $env:JAVA_HOME"
    $sdk = Find-Sdk
    $apksigner = Find-BuildTool $sdk 'apksigner.bat'
    $aapt2 = Find-BuildTool $sdk 'aapt2.exe'

    # --- 2. Version bump --------------------------------------------------------------------
    Write-Step 'Bumping gradle.properties'
    $newProps = Set-PropertyValue $originalProps 'MB_VERSION_NAME' $Version
    $newProps = Set-PropertyValue $newProps 'MB_VERSION_CODE' "$newCode"
    [System.IO.File]::WriteAllText($PropsPath, $newProps, $Utf8NoBom)
    $propsBumped = $true
    Write-Ok "MB_VERSION_NAME=$Version, MB_VERSION_CODE=$newCode"

    # --- 3. Tests and release build ---------------------------------------------------------
    Write-Step 'Running unit tests and the release build'
    Invoke-Native (Join-Path $Root 'gradlew.bat') @('testDebugUnitTest', 'assembleRelease', '--console=plain')

    $apkDir = Join-Path $Root 'app/build/outputs/apk/release'
    $apk = Join-Path $apkDir 'app-release.apk'
    if (-not (Test-Path $apk)) {
        $found = Get-ChildItem -Path $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $found) { throw "No release APK in $apkDir." }
        $apk = $found.FullName
    }
    Write-Ok "APK: $apk"

    # --- 4. Verify signature and package ----------------------------------------------------
    Write-Step 'Verifying the APK signature'
    $certs = @(Get-NativeOutput $apksigner @('verify', '--print-certs', $apk) -AllowFailure)
    if ($LASTEXITCODE -ne 0) { throw "apksigner rejected the APK:`n$($certs -join "`n")" }
    $dnLines = @($certs | Where-Object { $_ -match '^Signer #\d+ certificate DN: ' })
    if ($dnLines.Count -eq 0) { throw "apksigner printed no signer:`n$($certs -join "`n")" }
    foreach ($line in $dnLines) {
        $dn = ($line -split 'certificate DN: ', 2)[1]
        if ($dn -notmatch "(^|,\s*)CN=$([regex]::Escape($ExpectedSignerCn))(,|$)") {
            throw "APK is signed by '$dn', expected CN=$ExpectedSignerCn. Is keystore.properties the release key?"
        }
        Write-Ok "signer: $dn"
    }
    $digest = $certs | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest: ' } | Select-Object -First 1
    if ($digest) { Write-Ok ($digest -replace '^Signer #1 certificate ', '') }

    $badging = @(Get-NativeOutput $aapt2 @('dump', 'badging', $apk))
    $packageLine = $badging | Where-Object { $_ -match "^package: " } | Select-Object -First 1
    if (-not $packageLine) { throw 'aapt2 printed no package line.' }
    if ($packageLine -notmatch "name='$([regex]::Escape($PackageName))'") { throw "Unexpected package: $packageLine" }
    if ($packageLine -notmatch "versionCode='$newCode'") { throw "APK has the wrong versionCode: $packageLine" }
    if ($packageLine -notmatch "versionName='$([regex]::Escape($Version))'") { throw "APK has the wrong versionName: $packageLine" }
    Write-Ok "package $PackageName $Version ($newCode)"

    $assetName = "Morgenbriefing-$Tag.apk"
    $assetPath = Join-Path $apkDir $assetName
    Copy-Item -Path $apk -Destination $assetPath -Force

    # --- 5./6. Commit, tag, push, release ---------------------------------------------------
    if ($DryRun) {
        Write-Step 'Dry run complete - would now:'
        Write-Host "    git commit gradle.properties -m 'release: $Tag'"
        Write-Host "    git tag -a $Tag; git push origin main; git push origin $Tag"
        Write-Host "    gh release create $Tag $assetName --title 'Morgenbriefing $Version'"
        Write-Host "    release notes:`n$Notes" -ForegroundColor DarkGray
    } else {
        Write-Step "Committing and tagging $Tag"
        $messageFile = Join-Path ([System.IO.Path]::GetTempPath()) "morgenbriefing-release-$Version.txt"
        [System.IO.File]::WriteAllText($messageFile, "release: $Tag`n`n$CoAuthor`n", $Utf8NoBom)
        try {
            Invoke-Native git @('add', '--', 'gradle.properties')
            Invoke-Native git @('commit', '-F', $messageFile)
        } catch {
            # Unstage the bump; the finally block below restores the file itself.
            & git reset -q -- gradle.properties
            throw
        } finally {
            Remove-Item -Path $messageFile -ErrorAction SilentlyContinue
        }
        $propsBumped = $false
        $stage = 'committed'
        Invoke-Native git @('tag', '-a', $Tag, '-m', "Morgenbriefing $Version")
        $stage = 'tagged'

        Write-Step 'Pushing main and the tag'
        Invoke-Native git @('push', 'origin', 'main')
        Invoke-Native git @('push', 'origin', $Tag)
        $stage = 'pushed'

        Write-Step 'Creating the GitHub release'
        $notesFile = Join-Path ([System.IO.Path]::GetTempPath()) "morgenbriefing-notes-$Version.md"
        [System.IO.File]::WriteAllText($notesFile, $Notes, $Utf8NoBom)
        try {
            $ghArgs = @('release', 'create', $Tag, $assetPath, '--title', "Morgenbriefing $Version", '--notes-file', $notesFile, '--verify-tag')
            if ($Version.Contains('-')) { $ghArgs += '--prerelease' }
            Invoke-Native $gh $ghArgs
        } finally {
            Remove-Item -Path $notesFile -ErrorAction SilentlyContinue
        }
        $released = $true
        Write-Ok "released $Tag"
    }

    # --- 7. Install on connected phones -----------------------------------------------------
    if ($Install) {
        Write-Step 'Installing on connected devices (emulators skipped)'
        if ($DryRun) { Write-Warn "installing the dry-run build $Version, which is not published" }
        $adb = Join-Path $sdk 'platform-tools/adb.exe'
        if (-not (Test-Path $adb)) { throw "adb not found: $adb" }
        $serials = @(Get-NativeOutput $adb @('devices') | Where-Object { $_ -match '^(\S+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
        $phones = @()
        foreach ($serial in $serials) {
            if ($serial -like 'emulator-*') { continue }
            $qemu = (Get-NativeOutput $adb @('-s', $serial, 'shell', 'getprop', 'ro.kernel.qemu') -AllowFailure) -join ''
            if ($qemu.Trim() -eq '1') { continue }
            $phones += $serial
        }
        if ($phones.Count -eq 0) { Write-Warn 'no physical device connected' }
        foreach ($serial in $phones) {
            Invoke-Native $adb @('-s', $serial, 'install', '-r', $assetPath)
            Write-Ok "installed on $serial"
        }
    }

    if ($DryRun) { Write-Host "Dry run for $Tag finished successfully." -ForegroundColor Green }
    else { Write-Host "Release $Tag finished." -ForegroundColor Green }
    exit 0
} catch {
    Write-Host "Release failed: $($_.Exception.Message)" -ForegroundColor Red
    switch ($stage) {
        'committed' { Write-Host "The release commit exists locally (not tagged, not pushed). Undo with: git reset --hard HEAD~1" -ForegroundColor Yellow }
        'tagged' { Write-Host "Commit and tag $Tag exist locally. Finish with: git push origin main; git push origin $Tag; then create the release with gh." -ForegroundColor Yellow }
        'pushed' {
            if ($released) { Write-Host 'The GitHub release is published; only the installation failed.' -ForegroundColor Yellow }
            else { Write-Host "Commit and tag are pushed; create the release manually: gh release create $Tag <apk> --title 'Morgenbriefing $Version'" -ForegroundColor Yellow }
        }
    }
    exit 1
} finally {
    # A dry run or a failure before the commit leaves gradle.properties exactly as it was.
    if ($propsBumped -and $null -ne $originalProps) {
        [System.IO.File]::WriteAllText($PropsPath, $originalProps, $Utf8NoBom)
        Write-Host "    gradle.properties restored ($oldName)" -ForegroundColor DarkGray
    }
    Pop-Location
}
