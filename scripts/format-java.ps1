param([switch]$Check)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousJavaHome = $env:JAVA_HOME

Push-Location $projectRoot
try {
    # Use the workspace toolchain when available; otherwise use the installed tools.
    $localJdk = Get-ChildItem -LiteralPath "$projectRoot/.tools" -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($localJdk) {
        $env:JAVA_HOME = $localJdk.FullName
    }

    $maven = "$projectRoot/.tools/apache-maven-3.9.9/bin/mvn.cmd"
    if (!(Test-Path -LiteralPath $maven)) {
        $maven = (Get-Command mvn -ErrorAction Stop).Source
    }

    $goal = if ($Check) { 'spotless:check' } else { 'spotless:apply' }
    & $maven -B $goal
    if ($LASTEXITCODE -ne 0) {
        throw "Java formatter failed with exit code $LASTEXITCODE."
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
