@echo off
REM Double-clickable wrapper for move-project.ps1.
REM
REM It exists because Windows PowerShell's default execution policy is
REM Restricted on a stock install, so the .ps1 refuses to run and the error
REM ("not digitally signed") says nothing about what to do next.
REM
REM Usage: drag the project folder path in, or run from a terminal:
REM     tools\move-project.cmd -Destination D:\code\798QuSiBa
REM     tools\move-project.cmd -Destination D:\code\798QuSiBa -SkipAttic
REM     tools\move-project.cmd -Destination D:\code\798QuSiBa -DryRun

setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0move-project.ps1" %*
exit /b %ERRORLEVEL%
