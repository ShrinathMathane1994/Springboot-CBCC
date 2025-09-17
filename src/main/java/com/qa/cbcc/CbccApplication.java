package com.qa.cbcc;

import com.qa.cbcc.capture.TeeThreadLocalPrintStream;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.annotation.PostConstruct;

@SpringBootApplication
@EnableScheduling
public class CbccApplication {

    public static void main(String[] args) {
        SpringApplication.run(CbccApplication.class, args);
    }

    /**
     * Install thread-local tee streams for System.out and System.err
     * so parallel test cases can capture clean outputs.
     */
    @PostConstruct
    public void installTeeStreams() {
        System.setOut(new TeeThreadLocalPrintStream(System.out, TeeThreadLocalPrintStream.StreamType.STDOUT));
        System.setErr(new TeeThreadLocalPrintStream(System.err, TeeThreadLocalPrintStream.StreamType.STDERR));
    }
}
