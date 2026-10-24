package com.teamsneakymouse.sneakydiagnostics.report;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoredFileTest {
    private static byte[] json(int size) {
        byte[] data = new byte[size];
        java.util.Arrays.fill(data, (byte) ' ');
        return data;
    }

    @Test void exactlyOneMebibyteStaysPlainAndOneByteMoreIsCompressed() {
        var plain = StoredFile.json("a.json", json(StoredFile.COMPRESS_ABOVE_BYTES));
        assertEquals("a.json", plain.name());
        assertEquals(StoredFile.COMPRESS_ABOVE_BYTES, plain.bytes());
        var compressed = StoredFile.json("a.json", json(StoredFile.COMPRESS_ABOVE_BYTES + 1));
        assertEquals("a.json.gz", compressed.name());
        assertEquals(StoredFile.COMPRESS_ABOVE_BYTES + 1, compressed.uncompressedBytes());
        assertTrue(compressed.bytes() < compressed.uncompressedBytes());
    }

    @Test void compressedBytesInflateToTheOriginalAndTheChecksumCoversTheStoredBytes() throws IOException {
        byte[] original = json(StoredFile.COMPRESS_ABOVE_BYTES + 100);
        var stored = StoredFile.json("a.json", original);
        assertArrayEquals(original, new GZIPInputStream(new ByteArrayInputStream(stored.data())).readAllBytes());
        assertEquals(StoredFile.sha256(stored.data()), stored.sha256());
        assertEquals(64, stored.sha256().length());
    }

    @Test void sha256MatchesKnownVectorAndRawFilesAreNeverRecompressed() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", StoredFile.sha256("abc".getBytes()));
        var raw = StoredFile.raw("profile.sparkprofile", new byte[2 * StoredFile.COMPRESS_ABOVE_BYTES]);
        assertEquals("profile.sparkprofile", raw.name());
        assertEquals(raw.bytes(), raw.uncompressedBytes());
    }

    @Test void serializationIsUtf8AndSizedByBytesNotCharacters() {
        var report = new LinkedHashMap<String, Object>();
        report.put("text", "é");
        byte[] bytes = StoredFile.serialize(report);
        assertTrue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("é"));
        assertTrue(bytes.length > new String(bytes, java.nio.charset.StandardCharsets.UTF_8).length());
    }
}
