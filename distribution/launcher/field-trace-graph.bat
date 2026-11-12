@echo off
REM 作用：归档内的 Windows 启动脚本。
REM 使用场景：Windows 解压发布包后运行 ftg。
setlocal
set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%..\lib\field-trace-graph.jar"
set "JAVA=%SCRIPT_DIR%..\runtime\bin\java.exe"

if not exist "%JAR%" (
    echo field-trace-graph: missing %JAR% 1>&2
    exit /b 1
)

if not exist "%JAVA%" set "JAVA=java"

"%JAVA%" -jar "%JAR%" %*
