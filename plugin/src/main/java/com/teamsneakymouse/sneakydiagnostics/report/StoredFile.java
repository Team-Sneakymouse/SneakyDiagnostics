package com.teamsneakymouse.sneakydiagnostics.report;

import com.google.gson.GsonBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** A file as it is stored: the compression decision, byte counts and checksum, with no I/O. */
public record StoredFile(String name, byte[] data, long uncompressedBytes, String sha256) {
    /** Serialized JSON larger than this is stored gzip-compressed as {@code <name>.gz}. */
    public static final int COMPRESS_ABOVE_BYTES = 1024 * 1024;

    public long bytes() { return data.length; }

    public static byte[] serialize(Map<String, Object> report) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8);
    }

    /** {@code jsonName} ends in {@code .json}; the stored name gains {@code .gz} only when compressed. */
    public static StoredFile json(String jsonName, byte[] json) {
        if (json.length <= COMPRESS_ABOVE_BYTES) return new StoredFile(jsonName, json, json.length, sha256(json));
        byte[] gzip = gzip(json);
        return new StoredFile(jsonName + ".gz", gzip, json.length, sha256(gzip));
    }

    /** Binary artifacts such as spark profiles are never recompressed. */
    public static StoredFile raw(String name, byte[] data) { return new StoredFile(name, data, data.length, sha256(data)); }

    static byte[] gzip(byte[] data) {
        var out = new ByteArrayOutputStream(data.length / 4 + 64);
        try (var gzip = new GZIPOutputStream(out)) { gzip.write(data); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        return out.toByteArray();
    }

    static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
