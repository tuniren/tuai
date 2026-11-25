param([Parameter(ValueFromRemainingArguments = $true)][string[]]$NpmArguments)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$nodePathFile = Join-Path $projectRoot '.tools/node-path.txt'
if (Test-Path -LiteralPath $nodePathFile) {
    $tuaiNode = (Get-Content -LiteralPath $nodePathFile -Raw).Trim()
    $env:Path = "$tuaiNode;$env:Path"
    & (Join-Path $tuaiNode 'node.exe') (Join-Path $tuaiNode 'node_modules/npm/bin/npm-cli.js') @NpmArguments
} else {
    & npm.cmd @NpmArguments
}
exit $LASTEXITCODE
