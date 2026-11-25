<#!
Run Maven with JAVA_HOME / PATH, falling back to the installed IntelliJ IDEA toolchain.
Examples: .\scripts\maven.ps1 verify
          .\scripts\maven.ps1 clean package
!#>
$ErrorActionPreference = 'Continue'
$ideaRoot = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.3'
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath "$ideaRoot\jbr\bin\javac.exe")) {
    $env:JAVA_HOME = "$ideaRoot\jbr"
}
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($mavenCommand) {
    & $mavenCommand.Source @args
} elseif (Test-Path -LiteralPath "$ideaRoot\plugins\maven-plugin\lib\maven3\bin\mvn.cmd") {
    & "$ideaRoot\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" @args
} else {
    throw 'Maven 3.9+ is required. Install Maven and JDK 25, then configure JAVA_HOME and PATH.'
}
exit $LASTEXITCODE
