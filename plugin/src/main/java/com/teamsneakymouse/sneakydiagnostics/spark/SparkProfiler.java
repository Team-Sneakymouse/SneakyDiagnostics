package com.teamsneakymouse.sneakydiagnostics.spark;

import java.util.Map;

/** Keeps spark's optional, version-sensitive implementation outside capture coordination. */
public interface SparkProfiler {
    Session open(String captureId) throws Exception;

    interface Session {
        boolean owned();
        boolean current() throws Exception;
        Map<String, Object> details() throws Exception;
        void stop() throws Exception;
        byte[] export() throws Exception;
    }
}
