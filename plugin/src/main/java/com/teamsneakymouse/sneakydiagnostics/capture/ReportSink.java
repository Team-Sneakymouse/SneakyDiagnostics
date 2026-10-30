package com.teamsneakymouse.sneakydiagnostics.capture;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Paper headers and Bukkit snapshots are assembled on the server thread. Report suppliers run on the
 * writer thread and contain only detached data: no live entities, worlds, or players, and no Bukkit reads.
 * After handoff, collectors must not mutate data reachable by those suppliers.
 */
public interface ReportSink {
    /** One JSON report. A null suffix is the collector's main file; others are named by {@link #fileName}. */
    record ReportFile(String suffix, Supplier<Map<String, Object>> report) {}

    boolean busy(String collector);
    Map<String, Object> header(String collector, CaptureContext context, String reason, int segment);
    void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> detachedReport);

    /**
     * Several reports of one collector and segment, written in order on the writer thread. The collector
     * stays busy until all are written; a failing file is logged and does not stop the others.
     */
    default void write(String collector, CaptureContext context, int segment, List<ReportFile> files) {
        throw new UnsupportedOperationException("Multi-file reports are not supported by this sink");
    }

    /** {@code <collector>-<segment>.json}, or {@code <collector>-<suffix>-<segment>.json} for a suffix. */
    static String fileName(String collector, String suffix, int segment) {
        return collector + (suffix == null ? "" : "-" + suffix) + "-" + segment + ".json";
    }

    /** The artifact supplier runs on the writer thread; spark supports live asynchronous exports. */
    default void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> detachedReport,
                       String artifactName, Supplier<byte[]> artifact) {
        throw new UnsupportedOperationException("Binary artifacts are not supported by this sink");
    }
}
