///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES Boot.java

/**
 * Start — interactive entry point for the Kiwi distro.
 *
 * Thin wrapper over Boot: boots the full system (Kernel + interceptor stack + persistent
 * plugins) and hands the terminal to the interactive Console. Flags are passed through to
 * Boot (e.g. --clean, --reset-memory, --update).
 *
 *   jbang projects/Kiwi/Start.java          (from the MK8 root)
 *   jbang Start.java                         (from projects/Kiwi/)
 *   jbang Start.java --clean
 *
 * For headless/test boots use dev/Dev.java (which also delegates to Boot).
 */
public class Start {
    public static void main(String[] args) throws Exception {
        Boot.main(args);
    }
}
