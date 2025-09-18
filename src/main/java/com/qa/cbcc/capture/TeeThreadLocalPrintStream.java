package com.qa.cbcc.capture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe Tee PrintStream that stores per-thread output
 * for STDOUT and STDERR separately, while also forwarding to real console.
 */
public class TeeThreadLocalPrintStream extends PrintStream {

    public enum StreamType { STDOUT, STDERR }

    private static final ConcurrentHashMap<Long, ByteArrayOutputStream> stdoutBuffers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, ByteArrayOutputStream> stderrBuffers = new ConcurrentHashMap<>();

    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;

    private final PrintStream console;
    private final StreamType type;

    public TeeThreadLocalPrintStream(final PrintStream console, final StreamType type) {
        super(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                long tid = Thread.currentThread().getId();
                ByteArrayOutputStream baos;
                if (type == StreamType.STDOUT) {
                    baos = stdoutBuffers.computeIfAbsent(tid, k -> new ByteArrayOutputStream());
                } else {
                    baos = stderrBuffers.computeIfAbsent(tid, k -> new ByteArrayOutputStream());
                }
                baos.write(b);
                console.write(b);
            }
        }, true); // autoflush = true
        this.console = console;
        this.type = type;
    }

    /**
     * Hijack System.out and System.err for the current thread.
     */
    public static void hijackSystemOutAndErrForCurrentThread() {
        System.setOut(new TeeThreadLocalPrintStream(ORIGINAL_OUT, StreamType.STDOUT));
        System.setErr(new TeeThreadLocalPrintStream(ORIGINAL_ERR, StreamType.STDERR));
    }

    /**
     * Restore System.out and System.err back to the originals.
     */
    public static void restoreSystemOutAndErrForCurrentThread() {
        System.setOut(ORIGINAL_OUT);
        System.setErr(ORIGINAL_ERR);
    }

    /**
     * Get and clear the output buffer for the current thread.
     */
    public static String getOutputAndClear(StreamType type) {
        long tid = Thread.currentThread().getId();
        ByteArrayOutputStream baos;
        if (type == StreamType.STDOUT) {
            baos = stdoutBuffers.remove(tid);
        } else {
            baos = stderrBuffers.remove(tid);
        }
        return (baos == null) ? "" : new String(baos.toByteArray(), Charset.forName("UTF-8"));
    }
}
