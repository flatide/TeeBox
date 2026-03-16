@echo off
setlocal

set "BASE_DIR=%~dp0.."
set "JAR_FILE=%BASE_DIR%\lib\propertee-teebox.jar"

if defined PROPERTEE_TEEBOX_CONFIG (
    set "CONF_FILE=%PROPERTEE_TEEBOX_CONFIG%"
) else (
    set "CONF_FILE=%BASE_DIR%\conf\teebox.properties"
)

if defined JAVA_HOME (
    set "JAVA_BIN=%JAVA_HOME%\bin\java"
) else (
    set "JAVA_BIN=java"
)

if not exist "%JAR_FILE%" (
    echo TeeBox server jar not found: %JAR_FILE% 1>&2
    exit /b 1
)

if not exist "%CONF_FILE%" (
    echo TeeBox server config not found: %CONF_FILE% 1>&2
    exit /b 1
)

"%JAVA_BIN%" %JAVA_OPTS% -jar "%JAR_FILE%" --config "%CONF_FILE%" %*
