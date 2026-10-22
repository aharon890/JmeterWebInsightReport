@echo off
REM One line, no logic: the plugin JAR's launcher finds JMeter, refuses a lib\ext with no or
REM several plugin JARs, and runs the collector. Needs JMETER_HOME set. No bash, so no WSL trap.
java -cp "%JMETER_HOME%\lib\ext\*" com.jmeterwebinsightreport.WirCollectLauncher %*
exit /b %ERRORLEVEL%
