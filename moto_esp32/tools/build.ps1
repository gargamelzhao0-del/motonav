param(
    [string]$IdfPath = 'C:/Espressif/frameworks/esp-idf-v5.5.4',
    [string]$IdfToolsPath = 'C:/Espressif',
    [string]$PythonEnvironment = 'C:/Espressif/python_env/idf5.5_py3.11_env',
    [switch]$DeviceTests,
    [switch]$NavigationMetrics,
    [switch]$DisplayBench,
    [switch]$MapBench,
    [switch]$LegacyQspi,
    [switch]$HoldMap,
    [ValidateSet('RGB332','RGB565')][string]$ColorMode = 'RGB332',
    [ValidateRange(10,3600)][int]$BenchSeconds = 43,
    [ValidateSet(16,24,32,40,48,56,64)][int]$DisplayRows = 64,
    [string]$BuildDirectory = 'build'
)
$ErrorActionPreference = 'Stop'
if ($NavigationMetrics -and ($DeviceTests -or $DisplayBench -or $MapBench)) { throw 'NavigationMetrics uses real input; do not combine with synthetic display tests' }
if ($DisplayBench -and $MapBench) { throw 'Choose one bench mode' }
if ($HoldMap -and -not ($DisplayBench -or $MapBench)) { throw 'HoldMap requires an automatic benchmark' }
if ($LegacyQspi) {
    if (-not $PSBoundParameters.ContainsKey('ColorMode')) { $ColorMode = 'RGB565' }
    if ($ColorMode -ne 'RGB565') { throw 'LegacyQspi requires RGB565' }
}
$projectDirectory = Split-Path $PSScriptRoot -Parent
$env:IDF_TOOLS_PATH = $IdfToolsPath
$env:IDF_PYTHON_ENV_PATH = $PythonEnvironment
$env:PATH = "$PythonEnvironment/Scripts;$env:PATH"
Push-Location $projectDirectory
try {
    . (Join-Path $IdfPath 'export.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'ESP-IDF environment initialization failed' }
    $metricsSetting = if ($NavigationMetrics) { 'ON' } else { 'OFF' }
    $benchSetting = if ($DisplayBench) { 'ON' } else { 'OFF' }
    $mapBenchSetting = if ($MapBench) { 'ON' } else { 'OFF' }
    $testSetting = if ($DeviceTests -or $DisplayBench -or $MapBench) { 'ON' } else { 'OFF' }
    $directSetting = if ($LegacyQspi) { 'OFF' } else { 'ON' }
    $holdSetting = if ($HoldMap) { 'ON' } else { 'OFF' }
    idf.py -B $BuildDirectory "-DMOTONAV_NAV_METRICS=$metricsSetting" "-DMOTONAV_DEVICE_TESTS=$testSetting" "-DMOTONAV_AUTORUN_BENCH=$benchSetting" "-DMOTONAV_AUTORUN_MAP_BENCH=$mapBenchSetting" "-DMOTONAV_BENCH_SECONDS=$BenchSeconds" "-DMOTONAV_DISPLAY_ROWS=$DisplayRows" "-DMOTONAV_DIRECT_QSPI=$directSetting" "-DMOTONAV_COLOR_MODE=$ColorMode" "-DMOTONAV_HOLD_MAP_AFTER_BENCH=$holdSetting" build
    if ($LASTEXITCODE -ne 0) { throw 'Firmware build failed' }
} finally { Pop-Location }
