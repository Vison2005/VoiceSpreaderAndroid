[CmdletBinding()]
param(
    [string]$Version = '1.2.1'
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$workspaceRoot = Split-Path $repositoryRoot -Parent
$studioJdk = 'D:\AndroidStudio\jbr'
$androidSdk = 'D:\Android\SDK'
$gradleUserHome = 'D:\Android\Gradle'
$javaExecutable = Join-Path $studioJdk 'bin\java.exe'
$keytool = Join-Path $studioJdk 'bin\keytool.exe'
$wrapperJar = Join-Path $repositoryRoot 'gradle\wrapper\gradle-wrapper.jar'
$buildTools = Get-ChildItem (Join-Path $androidSdk 'build-tools') -Directory |
    Sort-Object { [Version]$_.Name } -Descending |
    Select-Object -First 1
$zipAlign = Join-Path $buildTools.FullName 'zipalign.exe'
$apkSigner = Join-Path $buildTools.FullName 'apksigner.bat'
$distRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'dist\release'))
$signingRoot = [IO.Path]::GetFullPath((Join-Path $workspaceRoot '.signing\VoiceSpreader\android'))
$keyStore = Join-Path $signingRoot 'VoiceSpreader-Android.p12'
$passwordPath = Join-Path $signingRoot 'VoiceSpreader-Android.password'
$keyAlias = 'voicespreader'
$unsignedApk = Join-Path $repositoryRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
$alignedApk = Join-Path $distRoot 'VoiceSpreader-Android-aligned.apk'
$signedApk = Join-Path $distRoot "VoiceSpreader-Android-v$Version.apk"

foreach ($tool in @($javaExecutable, $keytool, $wrapperJar, $zipAlign, $apkSigner)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "缺少 Android 构建工具：$tool"
    }
}
New-Item -ItemType Directory -Path $distRoot, $signingRoot -Force | Out-Null

if (-not (Test-Path -LiteralPath $keyStore)) {
    $passwordBytes = [byte[]]::new(32)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($passwordBytes)
    }
    finally {
        $generator.Dispose()
    }
    $password = [Convert]::ToBase64String($passwordBytes)
    [IO.File]::WriteAllText($passwordPath, $password)
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $keytool -genkeypair -v `
            -keystore $keyStore `
            -storetype PKCS12 `
            -storepass $password `
            -keypass $password `
            -alias $keyAlias `
            -keyalg RSA `
            -keysize 4096 `
            -validity 3650 `
            -dname 'CN=Vison2005, O=VoiceSpreader, C=CN'
        $keytoolExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($keytoolExitCode -ne 0) {
        throw "Android 签名密钥生成失败，退出码 $keytoolExitCode"
    }
}
else {
    $password = [IO.File]::ReadAllText($passwordPath).Trim()
}
& icacls.exe $signingRoot '/inheritance:r' '/grant:r' "${env:USERNAME}:(OI)(CI)F" | Out-Null

$previousJavaHome = $env:JAVA_HOME
$previousSigningPassword = $env:VOICESPREADER_SIGNING_PASSWORD
$env:JAVA_HOME = $studioJdk
$env:VOICESPREADER_SIGNING_PASSWORD = $password
try {
    & $javaExecutable '-Xmx64m' '-Xms64m' '-Dorg.gradle.appname=gradlew' `
        "-Dgradle.user.home=$gradleUserHome" `
        -classpath $wrapperJar org.gradle.wrapper.GradleWrapperMain `
        --no-daemon --no-watch-fs testDebugUnitTest lintRelease assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Android Release 构建失败，退出码 $LASTEXITCODE"
    }

    & $zipAlign -f 4 $unsignedApk $alignedApk
    if ($LASTEXITCODE -ne 0) {
        throw "zipalign 失败，退出码 $LASTEXITCODE"
    }
    & $apkSigner sign `
        --ks $keyStore `
        --ks-key-alias $keyAlias `
        --ks-pass 'env:VOICESPREADER_SIGNING_PASSWORD' `
        --key-pass 'env:VOICESPREADER_SIGNING_PASSWORD' `
        --out $signedApk `
        $alignedApk
    if ($LASTEXITCODE -ne 0) {
        throw "APK 签名失败，退出码 $LASTEXITCODE"
    }
    & $apkSigner verify --verbose --print-certs $signedApk
    if ($LASTEXITCODE -ne 0) {
        throw "APK 签名验证失败，退出码 $LASTEXITCODE"
    }
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:VOICESPREADER_SIGNING_PASSWORD = $previousSigningPassword
    if (Test-Path -LiteralPath $alignedApk) {
        Remove-Item -LiteralPath $alignedApk -Force
    }
}

$hash = (Get-FileHash -LiteralPath $signedApk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$signedApk.sha256", "$hash  $([IO.Path]::GetFileName($signedApk))`n")

Write-Host "Android release package: $signedApk"
