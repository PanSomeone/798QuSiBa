@echo off
setlocal
rem ===========================================================================
rem  make-keystore.cmd -- create a signing keystore, then build a signed release.
rem
rem  Why this is a .cmd and not a PowerShell script: PowerShell 5.1 splits a
rem  native-command argument such as -PhuishuiReleaseStoreFile=keystore/x.jks at
rem  the dot, so Gradle receives "keystore/x" and ".jks" and tries to run a task
rem  named .jks. Everything below passes values to keytool and Gradle without the
rem  shell getting a chance to reinterpret them.
rem
rem  The password is echoed on the console rather than typed into a silent
rem  prompt, because keytool wants it twice (store + key, which are set to the
rem  same value here) and because the result has to end up in local.properties
rem  anyway -- silently hashing a value that must be written down in plain text
rem  would only look safe.
rem
rem  KEEP THE .jks FILE AND THIS PASSWORD. A different keystore cannot update an
rem  installed app: Android refuses the install, and the only fix is to uninstall
rem  (losing the app's data) before installing again.
rem ===========================================================================

cd /d "%~dp0.."

set "ALIAS=huishui"
set "KS=keystore\huishui-release.jks"

where java >nul 2>nul
if errorlevel 1 (
    echo [error] java is not on PATH.
    echo         Android Studio ships one; try:
    echo         set "PATH=%%LOCALAPPDATA%%\Program Files\Android\Android Studio\jbr\bin;%%PATH%%"
    exit /b 1
)

if not exist "keystore" mkdir "keystore"

set "PASS="
if exist "keystore\password.txt" (
    set /p PASS=<"keystore\password.txt"
    echo [1/4] reusing the password already in keystore\password.txt
) else (
    set /p PASS=Choose a password for the keystore: 
    if "%PASS%"=="" (
        echo [error] empty password refused.
        exit /b 1
    )
    > "keystore\password.txt" echo %PASS%
)

if not exist "%KS%" (
    echo [2/4] creating %KS%
    keytool -genkeypair -v -keystore "%KS%" -alias %ALIAS% -keyalg RSA -keysize 4096 -validity 10000 -storepass "%PASS%" -keypass "%PASS%" -dname "CN=KillHuiShengHuo, OU=self-use, O=self-use, L=unknown, ST=unknown, C=CN"
    if errorlevel 1 (
        echo [error] keytool failed.
        exit /b 1
    )
) else (
    echo [2/4] %KS% already exists, keeping it
)

echo [3/4] writing the four keys into local.properties
findstr /v /b /c:"huishuiRelease" "local.properties" > "local.properties.tmp"
>> "local.properties.tmp" echo huishuiReleaseStoreFile=keystore/huishui-release.jks
>> "local.properties.tmp" echo huishuiReleaseStorePassword=%PASS%
>> "local.properties.tmp" echo huishuiReleaseKeyAlias=%ALIAS%
>> "local.properties.tmp" echo huishuiReleaseKeyPassword=%PASS%
move /y "local.properties.tmp" "local.properties" >nul

echo [4/4] building the signed release
call gradlew.bat --console=plain :app:assembleRelease
if errorlevel 1 (
    echo [error] build failed.
    exit /b 1
)

echo.
echo done. signed apk:
echo   app\build\outputs\apk\release\app-release.apk
echo.
echo both the .jks and local.properties are in .gitignore.
endlocal
