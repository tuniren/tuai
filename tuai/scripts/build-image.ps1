# Build/verify locally, then build and publish the Linux image on the SSH host.
# Example: .\scripts\build-image.ps1
$ErrorActionPreference = 'Continue'
& python "$PSScriptRoot\build_image.py" @args 2>&1 | ForEach-Object { $_.ToString() }
exit $LASTEXITCODE
