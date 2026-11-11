param(
    [string]$IdfPath = 'C:/Espressif/frameworks/esp-idf-v5.5.4',
    [string]$IdfToolsPath = 'C:/Espressif',
    [string]$PythonEnvironment = 'C:/Espressif/python_env/idf5.5_py3.11_env'
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
$env:IDF_TOOLS_PATH = $IdfToolsPath
$env:IDF_PYTHON_ENV_PATH = $PythonEnvironment
$env:PATH = "$PythonEnvironment/Scripts;$env:PATH"
Push-Location $projectDirectory
try {
    . (Join-Path $IdfPath 'export.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'ESP-IDF environment initialization failed' }
    idf.py -B build build
    if ($LASTEXITCODE -ne 0) { throw 'Firmware build failed' }
} finally { Pop-Location }
