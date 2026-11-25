param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ScriptArguments)
$ErrorActionPreference = 'Stop'
& python (Join-Path $PSScriptRoot 'deploy_k8s.py') @ScriptArguments
exit $LASTEXITCODE
