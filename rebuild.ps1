# Rebuild the plugin JAR.
#
# All packaging logic (compilation, resources, embedded abap-cli runtime)
# lives in build.ps1 - this script is kept as a convenience forwarder so
# existing muscle memory keeps working.
& "$PSScriptRoot\build.ps1"
exit $LASTEXITCODE
