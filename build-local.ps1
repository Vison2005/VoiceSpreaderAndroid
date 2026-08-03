$ErrorActionPreference = 'Stop'

$studioJdk = 'D:\AndroidStudio\jbr'
$gradleUserHome = 'D:\Android\Gradle'
$javaExecutable = Join-Path $studioJdk 'bin\java.exe'
$wrapperJar = Join-Path $PSScriptRoot 'gradle\wrapper\gradle-wrapper.jar'

if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "Android Studio JDK was not found: $javaExecutable"
}
if (-not (Test-Path -LiteralPath $wrapperJar -PathType Leaf)) {
    throw "Gradle Wrapper was not found: $wrapperJar"
}

Push-Location $PSScriptRoot
try {
    & $javaExecutable '-Xmx64m' '-Xms64m' '-Dorg.gradle.appname=gradlew' `
        "-Dgradle.user.home=$gradleUserHome" `
        -classpath $wrapperJar org.gradle.wrapper.GradleWrapperMain `
        --no-daemon --no-watch-fs testDebugUnitTest lintDebug assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Android build failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Host 'Build complete: app\build\outputs\apk\debug\app-debug.apk'
