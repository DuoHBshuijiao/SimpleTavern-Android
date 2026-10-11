param([string]$CacheRoot = (Join-Path $PSScriptRoot '../../.android-tools/sandbox'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$CacheRoot = (Resolve-Path -LiteralPath $CacheRoot).Path
$nativeRoot = Join-Path $projectRoot 'runtime/sandbox/src/main/jniLibs/arm64-v8a'
$assetRoot = Join-Path $projectRoot 'runtime/sandbox/src/main/assets/sandbox'
$sourceRoot = Join-Path $projectRoot 'third_party/sandbox'
$licenseRoot = Join-Path $assetRoot 'licenses'
@($nativeRoot, $assetRoot, $sourceRoot, $licenseRoot) | ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }

function Extract-Entry([string]$archive, [string]$entry, [string]$directory) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    & tar -xf $archive -C $directory $entry
    if ($LASTEXITCODE -ne 0) { throw "Cannot extract $entry from $archive" }
    return Join-Path $directory $entry
}

function Package-Data([string]$name) {
    $package = Join-Path $CacheRoot "$name.deb"
    $directory = Join-Path $CacheRoot "pack-$name"
    $data = Extract-Entry $package 'data.tar.xz' $directory
    Copy-Item -LiteralPath $package -Destination $sourceRoot -Force
    return $data
}

function Install-Native([string]$file, [string]$name, [bool]$renameTalloc = $false) {
    $bytes = [IO.File]::ReadAllBytes($file)
    if ($bytes.Length -lt 64 -or $bytes[0] -ne 127 -or $bytes[1] -ne 69 -or $bytes[2] -ne 76 -or
        $bytes[3] -ne 70 -or $bytes[4] -ne 2 -or $bytes[5] -ne 1 -or [BitConverter]::ToUInt16($bytes, 18) -ne 183) {
        throw "Expected little-endian ELF64 AArch64: $file"
    }
    if ($renameTalloc) {
        # Android extracts lib*.so, not lib*.so.2. Shorten DT_NEEDED/SONAME in place; offsets do not change.
        $before = [Text.Encoding]::ASCII.GetBytes("libtalloc.so.2`0")
        $after = [Text.Encoding]::ASCII.GetBytes("libtalloc.so`0")
        $matches = 0
        for ($i = 0; $i -le $bytes.Length - $before.Length; $i++) {
            if ($bytes[$i] -ne $before[0]) { continue }
            $same = $true
            for ($j = 1; $j -lt $before.Length; $j++) { if ($bytes[$i + $j] -ne $before[$j]) { $same = $false; break } }
            if (!$same) { continue }
            [Array]::Clear($bytes, $i, $before.Length)
            [Array]::Copy($after, 0, $bytes, $i, $after.Length)
            $matches++
        }
        if ($matches -ne 1) { throw "Expected one libtalloc DT_NEEDED/SONAME in $file; found $matches" }
    }
    [IO.File]::WriteAllBytes((Join-Path $nativeRoot $name), $bytes)
}

$prootData = Package-Data 'proot'
$tallocData = Package-Data 'libtalloc'
$shmemData = Package-Data 'libandroid-shmem'
$unpack = Join-Path $CacheRoot 'bundle-extracted'
Install-Native (Extract-Entry $prootData './data/data/com.termux/files/usr/bin/proot' $unpack) 'libstproot.so' $true
Install-Native (Extract-Entry $prootData './data/data/com.termux/files/usr/libexec/proot/loader' $unpack) 'libstprootloader.so'
Install-Native (Extract-Entry $tallocData './data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0' $unpack) 'libtalloc.so' $true
Install-Native (Extract-Entry $shmemData './data/data/com.termux/files/usr/lib/libandroid-shmem.so' $unpack) 'libandroid-shmem.so'
Copy-Item -LiteralPath (Join-Path $CacheRoot 'alpine-minirootfs-tuna.tar.gz') -Destination (Join-Path $assetRoot 'alpine-arm64.tar.gz') -Force
foreach ($name in @('proot-source.zip', 'talloc-source.tar.gz', 'shmem-source.tar.gz')) {
    Copy-Item -LiteralPath (Join-Path $CacheRoot $name) -Destination $sourceRoot -Force
}
Copy-Item -LiteralPath (Extract-Entry (Join-Path $CacheRoot 'proot-source.zip') 'proot-5.1.107.96/COPYING' $unpack) -Destination (Join-Path $licenseRoot 'proot-COPYING.txt') -Force
Copy-Item -LiteralPath (Extract-Entry (Join-Path $CacheRoot 'talloc-source.tar.gz') 'talloc-2.5.0/LICENSE' $unpack) -Destination (Join-Path $licenseRoot 'talloc-LICENSE.txt') -Force
Copy-Item -LiteralPath (Extract-Entry (Join-Path $CacheRoot 'shmem-source.tar.gz') 'libandroid-shmem-0.7/LICENSE' $unpack) -Destination (Join-Path $licenseRoot 'shmem-LICENSE.txt') -Force
Get-ChildItem -LiteralPath $nativeRoot | Select-Object Name, Length
Get-Item (Join-Path $assetRoot 'alpine-arm64.tar.gz') | Select-Object Name, Length
