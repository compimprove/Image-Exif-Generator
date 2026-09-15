package org.compi.image_exif_reset

import org.compi.image_exif_reset.processing.PythonSynthIdEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class PythonSynthIdEngineTest {
    private fun withWorker(timeout: Long = 20, block: (PythonSynthIdEngine, Path) -> Unit) {
        val directory = createTempDirectory("synthid-worker-protocol-")
        val source = directory.resolve("Worker.java")
        Files.writeString(source, """
            import java.io.*;
            import java.nio.file.*;
            import java.util.Base64;
            public class Worker {
                public static void main(String[] args) throws Exception {
                    var reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
                    String line;
                    int count = 0;
                    while ((line = reader.readLine()) != null) {
                        var parts = line.split("\t");
                        var in = Path.of(new String(Base64.getDecoder().decode(parts[1]), "UTF-8"));
                        var out = Path.of(new String(Base64.getDecoder().decode(parts[2]), "UTF-8"));
                        String detail = ProcessHandle.current().pid() + ":" + (++count);
                        System.out.println("progress\t" + Base64.getEncoder().encodeToString(detail.getBytes("UTF-8")));
                        System.out.flush();
                        if (in.getFileName().toString().startsWith("timeout")) Thread.sleep(30000);
                        System.err.print("x".repeat(256000));
                        System.err.flush();
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("done");
                        System.out.flush();
                    }
                }
            }
        """.trimIndent())
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val engine = PythonSynthIdEngine({ listOf(java.toString(), source.toString()) }, timeout)
        try { block(engine, directory) } finally {
            engine.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun reusesWorkerAndDrainsDiagnosticsAndHandlesUnicodePaths() = withWorker { engine, directory ->
        val input = directory.resolve("ảnh with spaces.txt")
        val output = directory.resolve("output.txt")
        Files.writeString(input, "pixels")
        val events = mutableListOf<String>()
        repeat(2) { engine.reduce(input, output, events::add) }
        assertEquals("pixels", Files.readString(output))
        assertEquals(events[0].substringBefore(':'), events[1].substringBefore(':'))
        assertTrue(events[1].endsWith(":2"))
        engine.release()
        val pid = events[0].substringBefore(':').toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        engine.reduce(input, output, events::add)
        assertTrue(events.last().endsWith(":1"), "Next batch must get a fresh worker")
    }

    @Test fun timeoutTerminatesWorker() = withWorker(timeout = 3) { engine, directory ->
        val input = directory.resolve("timeout.txt")
        Files.writeString(input, "pixels")
        val events = mutableListOf<String>()
        assertFails { engine.reduce(input, directory.resolve("output.txt"), events::add) }
        assertTrue(events.isNotEmpty(), "Worker should have started")
        val pid = events.first().substringBefore(':').toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }
}
