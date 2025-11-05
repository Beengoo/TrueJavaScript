package ua.beengoo.trueJavaScript;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.*;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public class ScriptManager {
    private final JavaPlugin plugin;
    private final Path scriptsRoot;

    // active batch (null коли нема скриптів)
    private volatile Batch activeBatch;

    public ScriptManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.scriptsRoot = plugin.getDataFolder().toPath().resolve("scripts");
        try { Files.createDirectories(scriptsRoot); } catch (IOException ignored) {}
    }

    // ------- Public API -------
    /** Load all scripts (first time) or load if none. */
    public synchronized boolean loadAll() {
        if (activeBatch != null) {
            plugin.getLogger().info("Scripts already loaded; use reloadAllAtomic() to reload.");
            return false;
        }
        return reloadAllAtomic();
    }

    /** Atomically recompile & hot-swap entire scripts folder.
     *  If compilation fails, old batch is untouched.
     */
    public synchronized boolean reloadAllAtomic() {
        try {
            List<Path> javaFiles = Files.walk(scriptsRoot)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
            if (javaFiles.isEmpty()) {
                plugin.getLogger().info("No scripts to load in " + scriptsRoot);
                // if nothing and we had a batch: unload it
                if (activeBatch != null) {
                    unloadAll();
                }
                return true;
            }

            // 1) compile to new temp dir
            Path newOut = Files.createTempDirectory(plugin.getName() + "-scripts-");
            boolean ok = compile(javaFiles, newOut);
            if (!ok) {
                cleanupDir(newOut);
                plugin.getLogger().warning("Compilation failed; aborting reload.");
                return false;
            }

            // 2) create new loader and instantiate scripts
            URLClassLoader newLoader = new URLClassLoader(new URL[]{ newOut.toUri().toURL() }, plugin.getClass().getClassLoader());
            Map<String, ScriptInstance> newInstances = new HashMap<>();
            List<String> fqcnList = javaFiles.stream()
                    .map(this::fqcnFromPath)
                    .collect(Collectors.toList());

            // instantiate all scripts (if some class not found or not Script => skip but continue)
            for (String fqcn : fqcnList) {
                try {
                    Class<?> cls = Class.forName(fqcn, true, newLoader);
                    Object o = cls.getDeclaredConstructor().newInstance();
                    if (!(o instanceof Script)) {
                        plugin.getLogger().warning("Class " + fqcn + " does not implement Script - skipping");
                        continue;
                    }
                    Script script = (Script) o;
                    // provide per-batch ScriptContext that records registrations
                    BatchScriptContext ctx = new BatchScriptContext(plugin, fqcn);
                    // call onEnable
                    try {
                        script.onEnable(ctx);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("onEnable threw for " + fqcn + ": " + t);
                        // if onEnable throws, we should cleanup registrations this script made
                        ctx.unregisterAll();
                        continue; // don't include failed script
                    }
                    newInstances.put(fqcn, new ScriptInstance(fqcn, script, ctx));
                } catch (ClassNotFoundException cnf) {
                    plugin.getLogger().warning("Class not found after compilation: " + fqcn);
                } catch (InvocationTargetException | InstantiationException | IllegalAccessException |
                         NoSuchMethodException e) {
                    throw new RuntimeException(e);
                }
            }

            // 3) SWAP: keep reference to old batch and set active -> new
            Batch old = activeBatch;
            activeBatch = new Batch(newOut, newLoader, newInstances);

            plugin.getLogger().info("Hot-swap: loaded " + newInstances.size() + " scripts.");

            // 4) Unload old batch (after new are active). Best-effort.
            if (old != null) {
                try {
                    old.unloadAll();
                } catch (Throwable t) {
                    plugin.getLogger().warning("Error unloading old batch: " + t);
                }
            }

            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("I/O error during reload: " + e);
            return false;
        }
    }

    /** Unload everything and free resources. */
    public synchronized void unloadAll() {
        if (activeBatch == null) return;
        Batch b = activeBatch;
        activeBatch = null;
        b.unloadAll();
    }

    // ------- Helpers and internals -------

    private boolean compile(List<Path> javaFiles, Path outDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            plugin.getLogger().severe("No JavaCompiler available (JDK required).");
            return false;
        }
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(
                javaFiles.stream().map(Path::toFile).collect(Collectors.toList())
        );

        DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
        List<String> options = List.of("-d", outDir.toString(), "-classpath", getClassPath());
        JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diag, options, null, units);
        boolean success = task.call();
        fm.close();

        if (!success) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n");
            for (Diagnostic<?> d : diag.getDiagnostics()) {
                sb.append(d.getKind()).append(" ").append(d.getSource()).append(" line ").append(d.getLineNumber())
                        .append(": ").append(d.getMessage(Locale.getDefault())).append("\n");
            }
            plugin.getLogger().severe(sb.toString());
        }
        return success;
    }

    private String getClassPath() {
        Set<String> parts = new LinkedHashSet<>();

        // 1) get from java.class.path
        String sys = System.getProperty("java.class.path");
        if (sys != null && !sys.isEmpty()) parts.add(sys);

        Consumer<URL> addUrl = u -> {
            try {
                if (u == null) return;
                String s = u.toURI().toString();
                if (s.startsWith("jar:")) {
                    s = s.substring(4);
                    int excl = s.indexOf("!/");
                    if (excl != -1) s = s.substring(0, excl);
                }
                try {
                    parts.add(Paths.get(new URL(s).toURI()).toString());
                } catch (Exception ex) {
                    parts.add(u.getPath());
                }
            } catch (Throwable ignored) {}
        };

        // 2) Get from plugins sources
        try {
            URL pluginLocation = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            addUrl.accept(pluginLocation);
        } catch (Throwable ignored) {}

        // 3) Bukkit loader itself
        try {
            URL bukkitLoc = Bukkit.class.getProtectionDomain().getCodeSource().getLocation();
            addUrl.accept(bukkitLoc);
        } catch (Throwable ignored) {}

        // 4) server implementation (plugin.getServer().getClass())
        try {
            URL serverImpl = plugin.getServer().getClass().getProtectionDomain().getCodeSource().getLocation();
            addUrl.accept(serverImpl);
        } catch (Throwable ignored) {}

        // 5) try Thread context classloader (if URLClassLoader)
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            while (cl != null) {
                if (cl instanceof URLClassLoader) {
                    for (URL u : ((URLClassLoader) cl).getURLs()) addUrl.accept(u);
                }
                cl = cl.getParent();
            }
        } catch (Throwable ignored) {}

        // 6) try plugin classloader if it's URLClassLoader or contains URLs
        try {
            ClassLoader pcl = plugin.getClass().getClassLoader();
            ClassLoader cur = pcl;
            while (cur != null) {
                if (cur instanceof URLClassLoader) {
                    for (URL u : ((URLClassLoader) cur).getURLs()) addUrl.accept(u);
                }
                cur = cur.getParent();
            }
        } catch (Throwable ignored) {}

        // 7) include all other loaded plugin locations (optional but helps)
        try {
            Plugin[] ps = plugin.getServer().getPluginManager().getPlugins();
            for (Plugin p : ps) {
                try {
                    URL loc = p.getClass().getProtectionDomain().getCodeSource().getLocation();
                    addUrl.accept(loc);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        String cp = String.join(File.pathSeparator, parts);
        // debug: print classpath to log to inspect if server jar present
        plugin.getSLF4JLogger().info("Script-compiler classpath: " + cp);
        return cp;
    }

    private String fqcnFromPath(Path javaFile) {
        Path rel = scriptsRoot.relativize(javaFile);
        String s = rel.toString().replace(File.separatorChar, '.');
        if (s.endsWith(".java")) s = s.substring(0, s.length()-5);
        return s;
    }

    private void cleanupDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        } catch (IOException ignored) {}
    }

    // ------- Inner types -------

    private class Batch {
        final Path outDir;
        final URLClassLoader loader;
        final Map<String, ScriptInstance> instances;

        Batch(Path outDir, URLClassLoader loader, Map<String, ScriptInstance> instances) {
            this.outDir = outDir; this.loader = loader; this.instances = instances;
        }

        void unloadAll() {
            for (ScriptInstance inst : instances.values()) {
                try {
                    inst.instance.onDisable();
                } catch (Throwable t) {
                    plugin.getSLF4JLogger().warn("onDisable threw for {}", inst.fqcn, t);
                }
                inst.context.unregisterAll();
            }
            try {
                loader.close();
            } catch (IOException e) {
                plugin.getSLF4JLogger().warn("Failed to close classloader", e);
            }
            cleanupDir(outDir);
        }
    }

    private record ScriptInstance(String fqcn, Script instance, BatchScriptContext context) {
    }

    // Simple per-script context that records registrations for cleanup.
    private class BatchScriptContext implements ScriptContext {
        private final JavaPlugin plugin = ScriptManager.this.plugin;
        private final String owner;
        private final List<Listener> listeners = new ArrayList<>();
        private final List<Integer> taskIds = new ArrayList<>();
        private final List<String> commands = new ArrayList<>();

        BatchScriptContext(JavaPlugin plugin, String owner) { this.owner = owner; }

        @Override
        public void registerListener(Listener listener) {
            Bukkit.getPluginManager().registerEvents(listener, plugin);
            listeners.add(listener);
        }

        @Override
        public int scheduleSync(Runnable task, long delayTicks, long periodTicks) {
            int id = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, task, delayTicks, periodTicks);
            taskIds.add(id);
            return id;
        }

        @Override
        public void cancelTask(int taskId) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskIds.remove(Integer.valueOf(taskId));
        }

        @Override
        public void registerCommand(String name, CommandExecutor executor) {
            // Not ready yet
        }

        // unregister recorded things
        void unregisterAll() {
            for (Listener l : listeners) {
                try { PluginManager pm = Bukkit.getPluginManager();
                    Bukkit.getPluginManager().callEvent(new PluginDisableEvent(plugin));
                } catch (Throwable ignored) {}
                try {
                    HandlerList.unregisterAll(l);
                } catch (Throwable ignored) {}
            }
            for (int tid : taskIds) {
                try { Bukkit.getScheduler().cancelTask(tid); } catch (Throwable ignored) {}
            }
            for (String c : commands) {
                try {
                    PluginCommand pc = plugin.getCommand(c);
                    if (pc != null) pc.setExecutor(null);
                } catch (Throwable ignored) {}
            }
            listeners.clear();
            taskIds.clear();
            commands.clear();
        }
    }
}
