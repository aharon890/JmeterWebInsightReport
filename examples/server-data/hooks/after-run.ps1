# After a JMeter run: fetch the server data for the run's window, then build the report with it.
#
#   powershell -File hooks\after-run.ps1 <first-report.json> <results.jtl> <new-output-folder>
#
# <first-report.json> is the .json the run's own report wrote (web-insight-report.json): its
# "window" is the span to export. $env:EXPORT_CMD is your tool's export, called as
#   <EXPORT_CMD> <startMillis> <endMillis> server.csv
# (default: this example's stand-in, "node fake-export.js"). EXPORT_CMD is split on whitespace,
# so a command or argument whose path contains spaces is not supported. JMeter is run as
# java -jar $env:JMETER_HOME\bin\ApacheJMeter.jar, because jmeter.bat does not pass JMeter's exit code
# back. Run it from the folder holding run.properties. Exits with JMeter's code, and server data
# never changes that code (unless run.properties sets server.failonerror).
param(
    [Parameter(Mandatory = $true)][string]$Json,
    [Parameter(Mandatory = $true)][string]$Jtl,
    [Parameter(Mandatory = $true)][string]$Out
)
$ErrorActionPreference = 'Stop'

if (-not $env:JMETER_HOME) {
    Write-Error 'after-run: set JMETER_HOME to the JMeter folder (the one holding bin\ApacheJMeter.jar)'
    exit 1
}
$window = (Get-Content -Raw -LiteralPath $Json | ConvertFrom-Json).window
if ($null -eq $window -or $null -eq $window.startMillis) {
    Write-Error "after-run: $Json has no window - is it the report's .json, from version 4.1 or later?"
    exit 1
}

# A minute either side: rows outside the run are counted and ignored.
$start = [long]$window.startMillis - 60000
$end = [long]$window.endMillis + 60000
$export = if ($env:EXPORT_CMD) { $env:EXPORT_CMD } else { 'node fake-export.js' }
$parts = @($export.Trim() -split '\s+')
$exportArgs = @($parts | Select-Object -Skip 1) + @("$start", "$end", 'server.csv')
& $parts[0] @exportArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& java -jar (Join-Path $env:JMETER_HOME 'bin\ApacheJMeter.jar') -g $Jtl -o $Out -q run.properties
exit $LASTEXITCODE
