package com.mauricio.adaptiveperformance;

public class ShellCommandRunnerCheck {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }
    public static void main(String[] args) {
        String shell = args.length > 0 ? args[0] : "/system/bin/sh";
        String output = ShellCommandRunner.run(shell, "printf 'first\\nsecond\\n'; printf 'err' >&2", 2000);
        check(output.equals("first" + (char)10 + "second" + (char)10 + "err"), "stdout and stderr preserved");
        output = ShellCommandRunner.run(shell, "python -c 'print(" + (char)34 + "x" + (char)34 + " * 300000, end=" + (char)34 + (char)34 + ")'", 2000);
        check(output.length() == 65536, "large output bounded and drained");
        long start = System.nanoTime();
        output = ShellCommandRunner.run(shell, "exec sleep 2", 100);
        long elapsed = (System.nanoTime() - start) / 1000000;
        check(output.contains("[timeout]") && elapsed < 1000, "silent command deadline " + elapsed + " ms");
        start = System.nanoTime();
        output = ShellCommandRunner.run(shell, "printf partial; exec sleep 2", 100);
        elapsed = (System.nanoTime() - start) / 1000000;
        check(output.contains("partial") && output.contains("[timeout]") && elapsed < 1000,
                "partial line deadline " + elapsed + " ms");
        output = ShellCommandRunner.run(shell, "read input; printf eof", 500);
        check(output.equals("eof"), "stdin closed");
        output = ShellCommandRunner.run("/missing-shell", "true", 100);
        check(output.startsWith("[error]"), "launch failure");
    }
}
