# Builds SpotifyControl_1.0_1.21.x.jar, the single jar that runs on every Minecraft 1.21 release.
# The platform layer is compiled once per API generation (six Minecraft versions) into build/generations,
# then merged. Run from the project root: .\build-universal.ps1
$ErrorActionPreference = "Stop"
$generations = @("1.21.1", "1.21.4", "1.21.5", "1.21.8", "1.21.10", "1.21.11")

foreach ($mc in $generations) {
    Write-Host "=== compiling generation for $mc ===" -ForegroundColor Cyan
    & .\gradlew.bat build stageGenerationJar "-Pmc=$mc" --console=plain
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

Write-Host "=== merging universal jar ===" -ForegroundColor Cyan
& .\gradlew.bat universalJar --console=plain
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "versions/SpotifyControl_1.0_1.21.x.jar is ready" -ForegroundColor Green
