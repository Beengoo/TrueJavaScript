package ua.beengoo.trueJavaScript;

public interface ScriptContext {
    // register things so manager can remove classes
    void registerListener(org.bukkit.event.Listener listener);
    int scheduleSync(Runnable task, long delayTicks, long periodTicks); // returns task id
    void cancelTask(int taskId);
    void registerCommand(String name, org.bukkit.command.CommandExecutor executor);
}