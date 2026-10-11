package com.teamsneakymouse.sneakydiagnostics.capture;

import java.util.Map;
import java.util.function.Supplier;

/** Headers are assembled on the server thread; report suppliers contain only detached data. */
public interface ReportSink {
    boolean busy(String collector);
    Map<String, Object> header(String collector, CaptureContext context, String reason, int segment);
    void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> detachedReport);
}
