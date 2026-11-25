param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ScriptArguments)
$ErrorActionPreference = 'Stop'
& python (Join-Path $PSScriptRoot 'build_image.py') @ScriptArguments
exit $LASTEXITCODE
