$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javac.exe' } else { 'javac.exe' }
$jar = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\jar.exe' } else { 'jar.exe' }
New-Item -ItemType Directory -Force 'build\classes' | Out-Null
$sources = @(Get-ChildItem 'src\main\java' -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& $javac --release 17 --add-modules jdk.httpserver -encoding UTF-8 -d 'build\classes' @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed. JDK 17+ required.' }
Copy-Item 'src\main\resources\*' 'build\classes' -Recurse -Force
& $jar --create --file 'AI-Office-Java.jar' --main-class tw.jimmy.aioffice.Main -C 'build\classes' .
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed.' }
Write-Host 'Built AI-Office-Java.jar'
