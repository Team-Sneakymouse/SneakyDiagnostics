package com.teamsneakymouse.sneakydiagnostics.connection;

import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformIsolationTest {
    @Test void eachEntryPointLinksWithoutTheOtherPlatformsApi() throws Exception {
        check("org.bukkit.", "velocity.SneakyDiagnosticsVelocity", "velocity.VelocityConnections");
        check("com.velocitypowered.", "SneakyDiagnosticsPlugin", "connection.ConnectionCollector");
    }

    private void check(String forbidden, String... types) throws Exception {
        var location = ConnectionCapture.class.getProtectionDomain().getCodeSource().getLocation();
        var api = com.teamsneakymouse.sneakydiagnostics.api.Diagnostics.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new java.net.URL[]{location, api}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(forbidden)) throw new ClassNotFoundException("Other platform is absent: " + name);
                if (name.startsWith("com.teamsneakymouse.sneakydiagnostics.")) {
                    synchronized (getClassLoadingLock(name)) {
                        var loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            for (String type : types) {
                var loaded = Class.forName("com.teamsneakymouse.sneakydiagnostics." + type, true, loader);
                assertDoesNotThrow(loaded::getDeclaredConstructors);
                assertDoesNotThrow(loaded::getDeclaredMethods);
                assertDoesNotThrow(loaded::getDeclaredFields);
            }
        }
    }
}
