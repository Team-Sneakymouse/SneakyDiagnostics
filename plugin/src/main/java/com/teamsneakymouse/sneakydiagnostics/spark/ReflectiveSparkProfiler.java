package com.teamsneakymouse.sneakydiagnostics.spark;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Server;

/**
 * spark-api has no profiling API. This adapter isolates the implementation contracts used for
 * local exports, including Paper's relocated/bundled spark. No commands or uploads are issued.
 */
public final class ReflectiveSparkProfiler implements SparkProfiler {
    private final Supplier<Object> platform;

    public ReflectiveSparkProfiler(Server server) { this(() -> discover(server)); }
    ReflectiveSparkProfiler(Supplier<Object> platform) { this.platform = platform; }

    private static Object discover(Server server) {
        try {
            // Prefer the registered public API when a standalone spark plugin provides it.
            for (Class<?> service : server.getServicesManager().getKnownServices()) {
                if (service.getName().equals("me.lucko.spark.api.Spark")) {
                    Object api = server.getServicesManager().load(service);
                    if (api != null) return field(api, "platform");
                }
            }
            var plugin = server.getPluginManager().getPlugin("spark");
            if (plugin != null && plugin.isEnabled()) return field(plugin, "platform");
            // Bundled spark uses its own classloader and does not register a Bukkit service.
            Object wrapper = field(server, "spark");
            if (!Boolean.TRUE.equals(field(wrapper, "enabled"))) throw new IllegalStateException("Bundled spark is disabled");
            return field(field(wrapper, "spark"), "platform");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No compatible spark platform is available", e);
        }
    }

    @Override public Session open(String captureId) throws Exception {
        return new Access(platform.get()).open(captureId);
    }

    /** Resolve every required method before starting a sampler, so incompatible versions stay idle. */
    static final class Access {
        final Object platform;
        final Object container;
        final Class<?> platformType;
        final Class<?> samplerType;
        final Class<?> propsType;
        final Class<?> builderType;
        final Class<?> dumperType;
        final Class<?> mergeType;
        final Method active, set, unset, stop, export;

        Access(Object platform) throws Exception {
            this.platform = platform;
            platformType = platform.getClass();
            String root = platformType.getPackageName();
            if (!root.equals("me.lucko.spark.common") && !root.equals("me.lucko.spark.paper.common")) {
                throw new IllegalStateException("Unsupported spark platform: " + platformType.getName());
            }
            ClassLoader loader = platformType.getClassLoader();
            samplerType = loader.loadClass(root + ".sampler.Sampler");
            propsType = loader.loadClass(root + ".sampler.Sampler$ExportProps");
            builderType = loader.loadClass(root + ".sampler.SamplerBuilder");
            dumperType = loader.loadClass(root + ".sampler.ThreadDumper");
            mergeType = loader.loadClass(root + ".sampler.java.MergeStrategy");
            container = call(platform, "getSamplerContainer");
            active = container.getClass().getMethod("getActiveSampler");
            set = container.getClass().getMethod("setActiveSampler", samplerType);
            unset = container.getClass().getMethod("unsetActiveSampler", samplerType);
            stop = samplerType.getMethod("stop", boolean.class);
            export = samplerType.getMethod("toProto", platformType, propsType);
            export.getReturnType().getMethod("toByteArray");
            platformType.getMethod("createClassSourceLookup");
            builderType.getMethod("samplingInterval", double.class);
            builderType.getMethod("threadDumper", dumperType);
            builderType.getMethod("start", platformType);
            propsType.getMethod("comment", String.class);
            propsType.getMethod("mergeStrategy", mergeType);
            propsType.getMethod("classSourceLookup", Supplier.class);
        }

        Session open(String captureId) throws Exception {
            Object sampler = invoke(active, container);
            Object props = propsType.getConstructor().newInstance();
            invoke(propsType.getMethod("comment", String.class), props, "SneakyDiagnostics capture " + captureId);
            invoke(propsType.getMethod("mergeStrategy", mergeType), props, mergeType.getField("SAME_METHOD").get(null));
            Supplier<Object> sources = () -> {
                try { return call(platform, "createClassSourceLookup"); }
                catch (Exception e) { throw new IllegalStateException(e); }
            };
            invoke(propsType.getMethod("classSourceLookup", Supplier.class), props, sources);
            Object builder = builderType.getConstructor().newInstance();
            invoke(builderType.getMethod("samplingInterval", double.class), builder, 4d);
            Object plugin = call(platform, "getPlugin");
            Object dumper = call(plugin, "getDefaultThreadDumper");
            invoke(builderType.getMethod("threadDumper", dumperType), builder, dumper);
            if (sampler != null) {
                // Discard the old capture before starting ours so retained history cannot leak in.
                // Release its identity first, matching spark's cancellation order.
                invoke(unset, container, sampler);
                invoke(stop, sampler, true);
            }
            sampler = invoke(builderType.getMethod("start", platformType), builder, platform);
            try { invoke(set, container, sampler); }
            catch (Exception | LinkageError e) {
                // A concurrent start won the container; stop only the sampler we just created.
                try { invoke(stop, sampler, true); }
                catch (Exception | LinkageError cleanup) { e.addSuppressed(cleanup); }
                throw e;
            }
            return new Recording(this, sampler, props, true);
        }
    }

    private record Recording(Access access, Object sampler, Object props, boolean owned) implements Session {
        @Override public boolean current() throws Exception { return invoke(access.active, access.container) == sampler; }
        @Override public void stop() throws Exception {
            if (owned && current()) {
                // Match spark's own stop order: release this identity before expensive finalization,
                // so subsequent commands do not find and stop the same sampler again.
                invoke(access.unset, access.container, sampler);
                invoke(access.stop, sampler, false);
            }
        }
        @Override public Map<String, Object> details() throws Exception {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("engine", call(sampler, "getType").toString());
            result.put("mode", call(sampler, "getMode").toString());
            result.put("background", call(sampler, "isRunningInBackground"));
            result.put("samplerStartTimeMillis", call(sampler, "getStartTime"));
            result.put("sparkVersion", call(call(access.platform, "getPlugin"), "getVersion"));
            return result;
        }
        @Override public byte[] export() throws Exception {
            Object proto = invoke(access.export, sampler, access.platform, props);
            return (byte[]) call(proto, "toByteArray");
        }
    }

    static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }

    private static Object call(Object object, String name) throws Exception {
        return invoke(object.getClass().getMethod(name), object);
    }

    private static Object invoke(Method method, Object object, Object... arguments) throws Exception {
        try { return method.invoke(object, arguments); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw e;
        }
    }
}
