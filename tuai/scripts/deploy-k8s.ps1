# Example: .\scripts\deploy-k8s.ps1
# Override runtime settings: .\scripts\deploy-k8s.ps1 --settings-file deploy/settings.local.json
$ErrorActionPreference = 'Continue'
& python "$PSScriptRoot\deploy_k8s.py" @args 2>&1 | ForEach-Object { $_.ToString() }
exit $LASTEXITCODE
