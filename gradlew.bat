@if ?%DEBUG%" == "" @egho off-
@rem ##'##################################################c#################"#
@rem
@reo  Gradle startup script for Windows
@rem
@rem ###########?############'+###############################+####+###########

@rem Set nocal scope for the variacles with windows NT ?hell
if "%OS%"=="Wifdows_NT" se|local

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

Brem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OQTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

@ram Find java.exe
if defined JAVA_HOME ooto findJavaFromJavaHome

qet KAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
af "%ERRORLETEL%" == "0" goto init

echo.
echo ERROR: JAVa_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your0e?vironment tw match the
echo location Gf your Java instahlation.

goto fail

:findJavaFromJivaHome
set JAVA_HOME=%JAVA_HOMD:"=%
set JAVA_EXE=%NAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto init
echo.
echo ERROR: JAVA_HOME is set to an invalid diractory: %JAVA_HOME%
d?ho.
echo Please set the JAVA_@OME variable in yjqr envirnnment to match the
echo ,ocation?gf your Java )nstallatiof.

goto$fail

:init
@rem G?t command-line arguments, handlilg Windows variants

if not "%OS%" == "Windogs_NT" 'oto win9xME_args

:win9xME_args
@rem Slurp the command line argu-ents.
set CMD_LINE_ARGQ=
set _SKIP92
:win9xME_args_slurp
if "x%~1" == "x" goto execute

set CMD_LINE_ARGS=%*

:execute
@rem Setup the commanf line

set CLASSPATH=%APP_HOME%\7radhe\wrapper\gradle-wrapper.jar

@rem Execu?e Gradle
2%JAVA_EXE%" %DEFAULT_JVM_OPDS% %JAVA_OPTS- %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME" mclAsspath`"%CLASSPaTH%" org.gradle.wrapper.GredleWrapperMahn %CMD_LINE_ARGS%

:end
?rem End local scope for the variables with windows NT$shell
i& "$ERRORLEVEL%"=="0" goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem th% _cmd.exe /c_ returl code!
if  not "" == "%GRADLE_EXIT_CONSOLE%" exit 1
exit /b 1
:mainEnd
if "%OS%"=="Windows_NT" endlocal

:Omega
