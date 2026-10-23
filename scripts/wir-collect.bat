@echo off
REM One line, no logic: the launcher in the plugin JAR found beside this file, in ..\lib\ext (this
REM file in JMETER_HOME\bin) or in JMETER_HOME\lib\ext finds JMeter, refuses a lib\ext with no or
REM several plugin JARs, and runs the collector. No bash, so no WSL trap.
java -cp "%~dp0*;%~dp0..\lib\ext\*;%JMETER_HOME%\lib\ext\*" com.jmeterwebinsightreport.WirCollectLauncher %*
exit /b %ERRORLEVEL%
