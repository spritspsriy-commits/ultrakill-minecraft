@echo off
rem Starts ULTRAKILL through Steam with the "UK Passthrough" r2modman profile.
rem Same as picking that profile in r2modman and pressing "Start modded": r2modman first copies the profile's doorstop
rem (winhttp.dll and doorstop_config.ini) into ULTRAKILL's folder, so this does too when another profile's is there.
setlocal
set "PROFILE=%APPDATA%\r2modmanPlus-local\ULTRAKILL\profiles\UK Passthrough"
if not defined ULTRAKILL set "ULTRAKILL=D:\SteamLibrary\steamapps\common\ULTRAKILL"
for /f "tokens=2,*" %%a in ('reg query "HKCU\Software\Valve\Steam" /v SteamExe 2^>nul') do set "STEAM=%%b"
if not defined STEAM set "STEAM=C:\Program Files (x86)\Steam\steam.exe"
if not exist "%PROFILE%\BepInEx\core\BepInEx.Preloader.dll" (
  echo The "UK Passthrough" r2modman profile is missing: %PROFILE%
  pause
  exit /b 1
)
rem BepInEx 5.4.21 (this profile) needs Doorstop 3; a profile with BepInEx 5.4.23 leaves Doorstop 4 there, which ignores these arguments
fc /b "%PROFILE%\winhttp.dll" "%ULTRAKILL%\winhttp.dll" >nul 2>nul
if errorlevel 1 (
  copy /y "%PROFILE%\winhttp.dll" "%ULTRAKILL%\winhttp.dll" >nul
  copy /y "%PROFILE%\doorstop_config.ini" "%ULTRAKILL%\doorstop_config.ini" >nul
  echo Put the "UK Passthrough" profile's doorstop in ULTRAKILL's folder, as r2modman does when it starts that profile.
)
start "" "%STEAM%" -applaunch 1229490 --doorstop-enable true --doorstop-target "%PROFILE%\BepInEx\core\BepInEx.Preloader.dll"
