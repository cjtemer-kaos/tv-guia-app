# Copia los assets de la web de TV Guia al proyecto Android.
# No se versionan: channels.json trae URLs con credenciales.
param(
    [string]$Origen = "D:\ias\proyectos\tv-guia",
    [string]$Destino = (Join-Path $PSScriptRoot "app\src\main\assets\web")
)

$ErrorActionPreference = "Stop"
$necesarios = @("index.html", "app.js", "style.css", "hls.min.js", "channels.json", "status.json")

if (-not (Test-Path $Origen)) {
    throw "No encuentro el proyecto web en $Origen"
}

New-Item -ItemType Directory -Path $Destino -Force | Out-Null

$faltan = @()
foreach ($f in $necesarios) {
    $o = Join-Path $Origen $f
    if (Test-Path $o) {
        Copy-Item $o (Join-Path $Destino $f) -Force
        "  copiado {0,-16} {1,7:N2} MB" -f $f, ((Get-Item $o).Length / 1MB)
    } else {
        $faltan += $f
    }
}

if ($faltan.Count -gt 0) {
    Write-Warning ("faltan: " + ($faltan -join ", "))
}

if (-not (Test-Path (Join-Path $Destino "tvmode.js"))) {
    throw "Falta tvmode.js en el proyecto (deberia estar versionado en el repo)"
}

"listo: assets en $Destino"
