param(
    [string]$FirebaseConfig = (Join-Path $env:USERPROFILE 'Downloads\google-services.json'),
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$config = Get-Content -LiteralPath $FirebaseConfig -Raw | ConvertFrom-Json
$client = @($config.client | Where-Object {
    $_.client_info.android_client_info.package_name -eq 'ec.gob.simertpi.simertpi_citizen_app'
})
if ($config.project_info.project_id -ne 'simertpi-dev' -or $client.Count -ne 1) {
    throw 'Se requiere la aplicacion Android existente del proyecto simertpi-dev.'
}
$defines = [ordered]@{
    ENVIRONMENT = 'dev'
    API_BASE_URL = 'https://simertpi-backend-dev.onrender.com/api/v1'
    MAP_SOURCE = 'osm-dev'
    FCM_REQUIRED = 'true'
    FCM_FIREBASE_API_KEY = $client[0].api_key[0].current_key
    FCM_FIREBASE_APP_ID = $client[0].client_info.mobilesdk_app_id
    FCM_FIREBASE_SENDER_ID = $config.project_info.project_number
    FCM_FIREBASE_PROJECT_ID = $config.project_info.project_id
}
foreach ($name in @('FCM_FIREBASE_API_KEY','FCM_FIREBASE_APP_ID','FCM_FIREBASE_SENDER_ID','FCM_FIREBASE_PROJECT_ID')) {
    if ([string]::IsNullOrWhiteSpace([string]$defines[$name])) { throw 'Configuracion Firebase Android incompleta.' }
}
Write-Output 'Configuracion FCM DEV validada; valores omitidos.'
if ($ValidateOnly) { return }
# Only public Android client configuration is written; never read an Admin key.
$tempDefines = Join-Path ([IO.Path]::GetTempPath()) ('simertpi-fcm-client-' + [guid]::NewGuid().ToString('N') + '.json')
try {
    $defines | ConvertTo-Json | Set-Content -LiteralPath $tempDefines -Encoding UTF8
    Push-Location (Split-Path $PSScriptRoot -Parent)
    try {
        & flutter build apk --debug "--dart-define-from-file=$tempDefines"
        if ($LASTEXITCODE -ne 0) { throw 'Fallo la compilacion APK DEV push.' }
    } finally { Pop-Location }
} finally {
    if (Test-Path -LiteralPath $tempDefines) { Remove-Item -LiteralPath $tempDefines }
}
