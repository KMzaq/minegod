
# Mythic TRPG

Minecraft Java 1.21.1 + NeoForge + ModDevGradle prototype mod foundation.

This project targets Java 21. The included `run-gradle.ps1` checks these local JDK locations in order:

```text
C:\Program Files\Java\jdk-21.0.11
C:\Program Files\Android\Android Studio\jbr
```

`run-gradle.ps1` selects the first available JDK only while a project command is running, leaving the system-wide Java configuration unchanged.

## Commands

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-gradle.ps1 build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-gradle.ps1 runClient --no-daemon
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-gradle.ps1 runServer --no-daemon
```

`-ExecutionPolicy Bypass` applies only to that PowerShell process and does not change the system execution policy.

## MDK Notes

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything
{this does not affect your code} and then start the process again.

Mapping Names:
============
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources:
==========
Community Documentation: https://docs.neoforged.net/
NeoForged Discord: https://discord.neoforged.net/
