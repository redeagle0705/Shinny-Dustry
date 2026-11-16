# Copy this file to tools\local.ps1 and put in the paths of your own computer.
# local.ps1 is read by build.ps1, run-test.ps1 and deploy.ps1, and is not part of the repository.

# the bin folder of a JDK 17 (javac.exe, jar.exe, javaw.exe)
$LocalJdkBin = "C:\path\to\jdk-17\bin"

# the official Mindustry v160.7 desktop jar (compiled against and run for tests; never changed)
$LocalGameJar = "C:\path\to\Mindustry.jar"

# only for deploy.ps1: the folder "mshaders" inside the game's mods folder
$LocalDeployTarget = "C:\path\to\Mindustry\mods\mshaders"
