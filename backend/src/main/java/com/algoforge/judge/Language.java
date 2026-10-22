package com.algoforge.judge;

import com.algoforge.common.ApiException;
import java.util.ArrayList;
import java.util.List;

/** Per-language sandbox commands. Time limits are scaled by {@link #timeFactor} to offset interpreter/JVM speed. */
public enum Language {
    PYTHON("python", "main.py",
            List.of("python3", "-m", "py_compile", "/box/main.py"),
            List.of("python3", "/box/main.py"), "python3 --version", 3.0, 0),
    CPP("cpp", "main.cpp",
            List.of("g++", "-std=gnu++17", "-O2", "-pipe", "-o", "/box/main", "/box/main.cpp"),
            List.of("/box/main"), "g++ --version | head -1", 1.0, 0),
    JAVA("java", "Main.java",
            List.of("javac", "-J-Xmx384m", "-encoding", "UTF-8", "-d", "/box", "/box/Main.java"),
            List.of("java", "-Xmx{MEM}m", "-Xss64m", "-XX:+UseSerialGC", "-XX:-UsePerfData", "-cp", "/box", "Main"),
            "java -version 2>&1 | head -1", 2.0, 128 * 1024);

    public final String id;
    public final String fileName;
    final List<String> compile;
    private final List<String> run;
    final String versionCommand;
    public final double timeFactor;
    /** Resident memory above the problem limit that the runtime itself may use (the JVM). */
    final long memoryOverheadKb;

    Language(String id, String fileName, List<String> compile, List<String> run, String versionCommand,
             double timeFactor, long memoryOverheadKb) {
        this.id = id;
        this.fileName = fileName;
        this.compile = compile;
        this.run = run;
        this.versionCommand = versionCommand;
        this.timeFactor = timeFactor;
        this.memoryOverheadKb = memoryOverheadKb;
    }

    public static Language of(String id) {
        for (Language l : values()) if (l.id.equals(id)) return l;
        throw ApiException.badRequest("Unsupported language: " + id);
    }

    List<String> runCommand(int memoryLimitMb) {
        List<String> cmd = new ArrayList<>();
        for (String part : run) cmd.add(part.replace("{MEM}", String.valueOf(memoryLimitMb)));
        return cmd;
    }

    /** Container memory: the problem limit plus room for the compiler (javac) or interpreter. */
    int containerMemoryMb(int problemMemoryMb) {
        return this == JAVA ? Math.max(problemMemoryMb + 320, 704) : problemMemoryMb + 64;
    }

    public double timeLimitSeconds(int problemTimeLimitMs) {
        return problemTimeLimitMs * timeFactor / 1000.0;
    }
}
