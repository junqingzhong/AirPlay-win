# build-sfx.ps1 - 打包 AirPlay-Server.exe 单文件
# 用法: powershell -ExecutionPolicy Bypass -File build-sfx.ps1
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$buildDir = "c:\Users\admin\Desktop\UxPlay-Portable\build"
$sfxExe = Join-Path $buildDir "SfxStub.exe"
$payloadZip = Join-Path $buildDir "payload.zip"
$outputExe = "c:\Users\admin\Desktop\UxPlay-Portable\AirPlay-Server.exe"

Write-Host "=== AirPlay-Server SFX Builder ===" -ForegroundColor Cyan

# 1. 创建 payload.zip
Write-Host "[1/3] Creating payload.zip..." -ForegroundColor Yellow
if (Test-Path $payloadZip) { Remove-Item $payloadZip -Force }

$zip = [System.IO.Compression.ZipFile]::Open($payloadZip, [System.IO.Compression.ZipArchiveMode]::Create)
$fileCount = 0

function Add-FileToZip($zip, $sourcePath, $entryName) {
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $sourcePath, $entryName, [System.IO.Compression.CompressionLevel]::Optimal)
}

function Add-DirToZip($zip, $sourceDir, $entryPrefix) {
    $baseLen = $sourceDir.Length
    Get-ChildItem $sourceDir -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($baseLen + 1) -replace "\\","/"
        $entryName = "$entryPrefix/$relative"
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal)
        $script:fileCount++
        if ($script:fileCount % 50 -eq 0) {
            Write-Host "  ... $script:fileCount files added" -ForegroundColor DarkGray
        }
    }
}

# 添加 jar
$jarPath = Join-Path $buildDir "java-airplay-server-1.0.8.jar"
if (Test-Path $jarPath) {
    Add-FileToZip $zip $jarPath "java-airplay-server-1.0.8.jar"
    $fileCount++
    Write-Host "  + java-airplay-server-1.0.8.jar" -ForegroundColor Green
} else {
    Write-Host "  ERROR: jar not found!" -ForegroundColor Red
    $zip.Dispose()
    exit 1
}

# 添加 bin/ffmpeg.exe
$ffmpegPath = Join-Path $buildDir "bin\ffmpeg.exe"
if (Test-Path $ffmpegPath) {
    Add-FileToZip $zip $ffmpegPath "bin/ffmpeg.exe"
    $fileCount++
    Write-Host "  + bin/ffmpeg.exe" -ForegroundColor Green
} else {
    Write-Host "  WARNING: ffmpeg.exe not found!" -ForegroundColor Red
}

# 添加 config/application.properties
$configPath = Join-Path $buildDir "config\application.properties"
if (Test-Path $configPath) {
    Add-FileToZip $zip $configPath "config/application.properties"
    $fileCount++
    Write-Host "  + config/application.properties" -ForegroundColor Green
}

# 添加 jre/ 目录
$jreDir = Join-Path $buildDir "jre"
if (Test-Path $jreDir) {
    Write-Host "  Adding jre/ ..." -ForegroundColor DarkGray
    Add-DirToZip $zip $jreDir "jre"
    Write-Host "  + jre/ ($fileCount files total)" -ForegroundColor Green
}

# 添加 gstreamer/ 目录
$gstDir = Join-Path $buildDir "gstreamer"
if (Test-Path $gstDir) {
    Write-Host "  Adding gstreamer/ ..." -ForegroundColor DarkGray
    Add-DirToZip $zip $gstDir "gstreamer"
    Write-Host "  + gstreamer/ ($fileCount files total)" -ForegroundColor Green
}

$zip.Dispose()
$zipSize = (Get-Item $payloadZip).Length
Write-Host "  payload.zip: $([math]::Round($zipSize/1MB, 2)) MB, $fileCount files" -ForegroundColor Cyan

# 2. 拼接 SfxStub.exe + marker + zip长度 + payload.zip
Write-Host "[2/3] Building AirPlay-Server.exe..." -ForegroundColor Yellow

$stubBytes = [System.IO.File]::ReadAllBytes($sfxExe)
$markerBytes = [System.Text.Encoding]::ASCII.GetBytes("SFXZIP")
$zipBytes = [System.IO.File]::ReadAllBytes($payloadZip)
$lengthBytes = [System.BitConverter]::GetBytes([int32]$zipBytes.Length)

$outputStream = [System.IO.File]::Create($outputExe)
$outputStream.Write($stubBytes, 0, $stubBytes.Length)
$outputStream.Write($markerBytes, 0, $markerBytes.Length)
$outputStream.Write($lengthBytes, 0, $lengthBytes.Length)
$outputStream.Write($zipBytes, 0, $zipBytes.Length)
$outputStream.Close()

# 3. 验证
$finalSize = (Get-Item $outputExe).Length
Write-Host "[3/3] Done!" -ForegroundColor Green
Write-Host "  AirPlay-Server.exe: $([math]::Round($finalSize/1MB, 2)) MB ($finalSize bytes)" -ForegroundColor Cyan
Write-Host "  Structure: SfxStub($($stubBytes.Length)B) + marker(6B) + len(4B) + zip($zipBytes.Length bytes)" -ForegroundColor DarkGray

# 清理临时文件
Remove-Item $payloadZip -Force
Write-Host "  Cleaned up payload.zip" -ForegroundColor DarkGray
