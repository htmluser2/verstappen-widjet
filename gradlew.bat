@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=-Dfile.encoding=UTF-8 "-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

@rem Prefer a Java 17+ runtime for Android Gradle Plugin 8.x; older Java 8 installs
@rem can otherwise break dependency resolution even when the project itself is valid.
set "JAVA_HOME="
for %%D in (
  "C:\Program Files\Eclipse Adoptium"
  "C:\Program Files\Microsoft"
  "C:\Program Files\Java"
  "%USERPROFILE%\AppData\Local\Programs\Eclipse Adoptium"
  "%USERPROFILE%\AppData\Local\Programs\Microsoft"
) do (
  if exist "%%~D" (
    for /d %%J in ("%%~D\*") do (
      if exist "%%~J\bin\java.exe" (
        set "JAVA_HOME=%%~J"
        goto findJavaFromJavaHome
      )
    )
  )
)

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no compatible Java 17+ installation could be found in typical locations. 1>&2
echo. 1>&2
echo Please install JDK 17 or newer and set the JAVA_HOME variable to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto validateJavaVersion

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:validateJavaVersion
set "JAVA_VERSION="
for /f "tokens=3" %%V in ('"%JAVA_EXE%" -version 2^>^&1') do (
  set "JAVA_VERSION=%%~V"
  set "JAVA_VERSION=%JAVA_VERSION:"=%"
  for /f "tokens=1,2 delims=." %%A in ("%JAVA_VERSION%") do (
    if "%%A"=="1" (
      set "JAVA_MAJOR=%%B"
    ) else (
      set "JAVA_MAJOR=%%A"
    )
  )
)

if defined JAVA_MAJOR if "%JAVA_MAJOR%" GEQ "17" goto execute

set "JAVA_HOME="
set "JAVA_EXE=java.exe"
goto resolveFallbackJava

:resolveFallbackJava
set "JAVA_HOME="
for %%D in (
  "C:\Program Files\Eclipse Adoptium"
  "C:\Program Files\Microsoft"
  "C:\Program Files\Java"
  "%USERPROFILE%\AppData\Local\Programs\Eclipse Adoptium"
  "%USERPROFILE%\AppData\Local\Programs\Microsoft"
) do (
  if exist "%%~D" (
    for /d %%J in ("%%~D\*") do (
      if exist "%%~J\bin\java.exe" (
        set "JAVA_HOME=%%~J"
        goto findJavaFromJavaHome
      )
    )
  )
)

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME points to an older Java runtime and no compatible JDK 17+ could be found. 1>&2
echo. 1>&2
echo Please install JDK 17 or newer and set JAVA_HOME to the correct installation. 1>&2

goto fail

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar


@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
