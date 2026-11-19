param(
    [string]$Avd = 'Pixel_9_Pro_API_36',
    [string]$DnsServer = '202.98.18.3'
)

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$emulator = Join-Path $sdk 'emulator\emulator.exe'
if (-not (Test-Path -LiteralPath $emulator)) { throw "未找到 Android 模拟器：$emulator" }
& $emulator -avd $Avd -dns-server $DnsServer -no-snapshot-load
