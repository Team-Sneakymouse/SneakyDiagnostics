package com.teamsneakymouse.sneakydiagnostics.capture;

import java.util.Map;
import java.util.function.Supplier;

/** Headers are assembled on the server thread; report suppliers contain only detached data. */
public interface ReportSink {
    boolean busy(String collector);
    Map<String, Object> header(String collector, CaptureContext context, String reason, int segment);
    void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> detachedReport);

    /** The artifact supplier runs on the writer thread; spark supports live asynchronous exports. */
    default void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> detachedReport,
                       String artifactName, Supplier<byte[]> artifact) {
        throw new UnsupportedOperationException("Binary artifacts are not supported by this sink");
    }
}
