# TrueJavaScript
Minecraft Bukkit plugin that allows to execute **pure java code** as script in server JVM!

## Features
- **Real** java code execution, just like you writing regular java code in any project!
- **Dynamic reload** allows to reload code almost seamlessly without server restart
- **No reflection hacks, no extra dependencies** — just pure JDK features

## How it works?
- Runs `.java` files placed in the `plugins/TrueJavaScript/scripts/` folder
- Compiles them at runtime using the built-in `JavaCompiler` from the JDK
- Loads each script in an isolated classloader (so reloading replaces classes completely)

And as you may have read above, the plugin requires a server running on [Java Development Kit (JDK)](https://en.wikipedia.org/wiki/Java_Development_Kit) to make the compilation process possible.


## Classpath
Plugin will compile java code with classpath of:
- Java class path property (`System.getProperty("java.class.path")`)
- Plugins jars (including TrueJavaScript)
- Internal libraries (the ` libraries/` folder)
- Bukkit plugins classloader
- Scripts root package (`plugins/TrueJavaScript/scripts/`)

Scripts have their own package that begins from `plugins/TrueJavaScript/scripts/`, imagine this as `src/main/java/` folder