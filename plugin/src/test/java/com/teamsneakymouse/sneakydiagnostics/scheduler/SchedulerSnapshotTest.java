package com.teamsneakymouse.sneakydiagnostics.scheduler;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerSnapshotTest {
    @Test void countsOwnersAndModesAndReportsObservedChurnOnlyAfterCompleteBaseline() {
        Plugin owner = owner("MagicSpells");
        var first = SchedulerSnapshot.read(0, List.of(task(owner, 1, true), task(owner, 2, false)), List.of(), null, false, System.nanoTime());
        assertFalse(first.snapshot().churnComparable());
        assertEquals(1, first.snapshot().groups().getFirst().syncPending());
        assertEquals(1, first.snapshot().groups().getFirst().asyncPending());
        var second = SchedulerSnapshot.read(1000, List.of(task(owner, 2, false), task(owner, 3, true)), List.of(), first.taskIds(), true, System.nanoTime());
        assertEquals(1, second.snapshot().observedNewTasks());
        assertEquals(1, second.snapshot().observedMissingTasks());
        var partial = SchedulerSnapshot.read(2000, List.of(task(owner, 3, true)), List.of(), second.taskIds(), false, System.nanoTime());
        assertFalse(partial.snapshot().churnComparable());
        assertEquals(0, partial.snapshot().observedMissingTasks());
    }

    @Test void workerCountsStaySeparateAndDoNotExposeThreadNames() {
        Plugin owner = owner("Voice");
        BukkitWorker worker = (BukkitWorker) Proxy.newProxyInstance(BukkitWorker.class.getClassLoader(), new Class<?>[]{BukkitWorker.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getOwner")) return owner;
                    throw new AssertionError("Unexpected worker read: " + method.getName());
                });
        var observed = SchedulerSnapshot.read(0, List.of(), List.of(worker), Set.of(), true, System.nanoTime());
        assertEquals(1, observed.snapshot().groups().getFirst().activeWorkers());
        assertEquals(0, observed.snapshot().groups().getFirst().asyncPending());
    }

    @Test void taskAndGroupCapsExposeTruncationWithoutInventingChurn() {
        Plugin owner = owner("owner");
        var capped = SchedulerSnapshot.read(0, Collections.nCopies(SchedulerSnapshot.MAX_TASKS + 1, task(owner, 1, true)),
                List.of(), Set.of(), true, System.nanoTime());
        assertTrue(capped.snapshot().truncated());
        assertFalse(capped.snapshot().churnComparable());
        assertEquals(SchedulerSnapshot.MAX_TASKS, capped.snapshot().examinedTasks());
        List<BukkitTask> groups = new ArrayList<>();
        for (int i = 0; i < SchedulerSnapshot.MAX_GROUPS + 1; i++) groups.add(task(owner("plugin-" + i), i, true));
        var overflow = SchedulerSnapshot.read(0, groups, List.of(), null, false, System.nanoTime());
        assertEquals(SchedulerSnapshot.MAX_GROUPS, overflow.snapshot().groups().size());
        assertEquals(1, overflow.snapshot().overflowTasks());
        assertTrue(overflow.snapshot().truncated());
    }

    private static Plugin owner(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
            if (method.getName().equals("getName")) return name;
            throw new AssertionError("Unexpected owner read: " + method.getName());
        });
    }

    private static BukkitTask task(Plugin owner, int id, boolean sync) {
        return (BukkitTask) Proxy.newProxyInstance(BukkitTask.class.getClassLoader(), new Class<?>[]{BukkitTask.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getOwner" -> owner;
            case "getTaskId" -> id;
            case "isSync" -> sync;
            case "isCancelled" -> false;
            default -> throw new AssertionError("Unexpected task read: " + method.getName());
        });
    }
}
