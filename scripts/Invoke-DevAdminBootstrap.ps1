[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)][string]$ExpectedHost,
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z_][A-Za-z0-9_]{0,62}$')][string]$ExpectedDbUser,
 [Parameter(Mandatory=$true)][string]$CaCertificate,
 [Parameter(Mandatory=$true)][string]$Operator,
 [Parameter(Mandatory=$true)][string]$ApprovalId,
 [Parameter(Mandatory=$true)][string]$JavaHome,
 [string]$BackendDirectory,
 [switch]$PreflightOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if([string]::IsNullOrWhiteSpace($BackendDirectory)){$BackendDirectory=Join-Path $PSScriptRoot '../simertpi-backend'}
if($ExpectedHost -notmatch '^dpg-db1hq55g1s2s73a8e340-a\.[a-z]+-postgres\.render\.com$'){throw 'Solo se admite el hostname externo DEV aprobado'}
if($Operator -notmatch '^[A-Za-z0-9._:@/-]{3,100}$' -or $ApprovalId -notmatch '^[A-Za-z0-9._:/-]{3,100}$'){throw 'Operador o aprobación inválidos'}
$java=Join-Path $JavaHome 'bin/java.exe'
if(!(Test-Path -LiteralPath $java -PathType Leaf)){throw 'Java 21 no disponible'}
$probe=New-Object Diagnostics.ProcessStartInfo; $probe.FileName=$java; $probe.Arguments='-version'; $probe.UseShellExecute=$false; $probe.RedirectStandardError=$true; $probe.CreateNoWindow=$true; $process=[Diagnostics.Process]::Start($probe); $version=$process.StandardError.ReadToEnd(); $process.WaitForExit()
if($version -notmatch 'version "21[.]'){throw 'Se requiere Java 21'}
$ca=(Resolve-Path -LiteralPath $CaCertificate).Path
if($ca.Contains('&') -or $ca.Contains('?') -or $ca.Contains('#')){throw 'Ruta de CA no válida'}
if([Console]::IsInputRedirected -or [Console]::IsOutputRedirected){throw 'Se requiere consola interactiva sin redirección'}
$backend=(Resolve-Path -LiteralPath $BackendDirectory).Path
$classes=Join-Path $backend 'target/classes'
$lib=Join-Path $backend 'target/bootstrap-lib'
if(!(Test-Path (Join-Path $classes 'ec/gob/simertpiadmin/bootstrap/AdminBootstrapCli.class')) -or !(Test-Path $lib)){throw 'Preparar primero clases y dependencias según runbook'}
$classpath=$classes+[IO.Path]::PathSeparator+(Join-Path $lib '*')
# Validate the PEM with the JDK keytool, without trusting certificates obtained from the target.
$keytool=Join-Path $JavaHome 'bin/keytool.exe'
$probe=New-Object Diagnostics.ProcessStartInfo; $probe.FileName=$keytool; $probe.Arguments='-printcert -file "'+$ca+'"'; $probe.UseShellExecute=$false; $probe.RedirectStandardOutput=$true; $probe.RedirectStandardError=$true; $probe.CreateNoWindow=$true; $process=[Diagnostics.Process]::Start($probe); $null=$process.StandardOutput.ReadToEnd(); $null=$process.StandardError.ReadToEnd(); $process.WaitForExit()
if($process.ExitCode -ne 0){throw 'Certificado CA no válido'}
if($PreflightOnly){Write-Host 'Preflight local correcto. No se abrió conexión a DEV.';return}
$names=@('DB_URL','DB_USERNAME','DB_PASSWORD')
$previous=@{}
foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$secure=$null
$pointer=[IntPtr]::Zero
try{
 $env:DB_URL='jdbc:postgresql://'+$ExpectedHost+':5432/simertpi?sslmode=verify-full&sslrootcert='+[Uri]::EscapeDataString($ca.Replace('\','/'))
 $env:DB_USERNAME=$ExpectedDbUser
 # Prefer an already injected, process-scoped managed secret; otherwise prompt without echo.
 if([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)){
  $secure=Read-Host 'Contraseña de conexión PostgreSQL DEV (no contraseña del administrador)' -AsSecureString
  $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  $env:DB_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
 }
 $arguments=@('-cp',('"'+$classpath+'"'),'ec.gob.simertpiadmin.bootstrap.AdminBootstrapCli','--bootstrap-dev-admin',"--expected-host=$ExpectedHost","--expected-db-user=$ExpectedDbUser","--operator=$Operator","--approval-id=$ApprovalId"); $process=Start-Process -FilePath $java -ArgumentList $arguments -NoNewWindow -Wait -PassThru
 if($process.ExitCode -ne 0){throw 'Bootstrap detenido; consultar resultado por vía autorizada antes de reintentar'}
}finally{
 foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
 if($pointer -ne [IntPtr]::Zero){[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
 if($null -ne $secure){$secure.Dispose()}
}
