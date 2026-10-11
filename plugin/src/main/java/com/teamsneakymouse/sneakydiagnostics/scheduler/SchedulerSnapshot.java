package com.teamsneakymouse.sneakydiagnostics.scheduler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;

/** Detached, bounded grouping of the public scheduler view. Task IDs stay out of reports. */
final class SchedulerSnapshot {
    static final int MAX_TASKS = 50_000, MAX_WORKERS = 5_000, MAX_GROUPS = 512;
    record Key(String plugin, String taskImplementationClass) {}
    record TaskKey(String plugin, int id) {}
    record Group(Key key, long syncPending, long asyncPending, long cancelledPending, long activeWorkers) {}
    record Snapshot(long observedAfterMillis, int pendingTasks, int examinedTasks, int activeWorkers,
                    int examinedWorkers, boolean truncated, int overflowTasks, int overflowWorkers,
                    boolean churnComparable, long observedNewTasks, long observedMissingTasks,
                    List<Group> groups, long collectionNanos) {}
    record Observation(Snapshot snapshot, Set<TaskKey> taskIds) {}

    static Observation read(long offset, List<BukkitTask> tasks, List<BukkitWorker> workers,
                            Set<TaskKey> previous, boolean previousComplete, long beginNanos) {
        Map<Key, long[]> groups = new LinkedHashMap<>();
        Set<TaskKey> ids = new HashSet<>();
        int overflowTasks = 0, overflowWorkers = 0;
        int examined = Math.min(tasks.size(), MAX_TASKS), examinedWorkers = Math.min(workers.size(), MAX_WORKERS);
        for (int i = 0; i < examined; i++) {
            BukkitTask task = tasks.get(i);
            String owner = task.getOwner().getName();
            ids.add(new TaskKey(owner, task.getTaskId()));
            Key key = new Key(owner, task.getClass().getName());
            if (!groups.containsKey(key) && groups.size() >= MAX_GROUPS) { overflowTasks++; continue; }
            long[] counts = groups.computeIfAbsent(key, ignored -> new long[4]);
            counts[task.isSync() ? 0 : 1]++;
            if (task.isCancelled()) counts[2]++;
        }
        for (int i = 0; i < examinedWorkers; i++) {
            BukkitWorker worker = workers.get(i);
            // The worker interface exposes no runnable class; this is deliberately labelled unknown.
            Key key = new Key(worker.getOwner().getName(), "<active-worker: runnable class unavailable>");
            if (!groups.containsKey(key) && groups.size() >= MAX_GROUPS) { overflowWorkers++; continue; }
            groups.computeIfAbsent(key, ignored -> new long[4])[3]++;
        }
        boolean truncated = tasks.size() > examined || workers.size() > examinedWorkers
                || overflowTasks > 0 || overflowWorkers > 0;
        boolean comparable = previous != null && previousComplete && tasks.size() <= examined;
        long added = 0, missing = 0;
        if (comparable) {
            for (TaskKey id : ids) if (!previous.contains(id)) added++;
            for (TaskKey id : previous) if (!ids.contains(id)) missing++;
        }
        List<Group> rows = new ArrayList<>();
        groups.forEach((key, count) -> rows.add(new Group(key, count[0], count[1], count[2], count[3])));
        var detachedRows = List.copyOf(rows);
        var detachedIds = Set.copyOf(ids);
        return new Observation(new Snapshot(offset, tasks.size(), examined, workers.size(), examinedWorkers,
                truncated, overflowTasks, overflowWorkers, comparable, added, missing, detachedRows,
                System.nanoTime() - beginNanos), detachedIds);
    }
}
