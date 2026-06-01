// Shared file — included via //SOURCES in each project's Start.java. No JBang header.

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.*;

/**
 * BootHelper — Project root resolution with automatic GitHub fallback.
 *
 * Used by every Start.java runner. Resolution order:
 *   1. Walk up from user.dir looking for kernel/Kernel.java  (local clone)
 *   2. Check ~/.jbang/mk8/ for a previously downloaded copy  (local cache)
 *   3. Download the repo zip from GitHub, extract to cache    (first remote run)
 *
 * The --update flag forces a fresh download even when the cache exists:
 *   jbang Start.java --update
 *   jbang https://raw.githubusercontent.com/gazolla/MK8/main/projects/ChatAI/Start.java --update
 */
class BootHelper {

    static final String REPO_ZIP   = "https://github.com/gazolla/MK8/archive/refs/heads/main.zip";
    static final String ZIP_PREFIX = "MK8-main/";
    static final Path   CACHE_DIR  =
            Path.of(System.getProperty("user.home"), ".jbang", "mk8");

    /**
     * Returns the resolved project root path.
     * Pass main()'s args so --update is honoured.
     */
    static Path findOrDownloadRoot(String[] args) throws Exception {
        boolean forceUpdate = false;
        for (String arg : args)
            if ("--update".equals(arg)) { forceUpdate = true; break; }

        // ── 1. Local clone ────────────────────────────────────────────────────
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("kernel/Kernel.java")))
                return p;
        }

        // ── 2. Local cache ────────────────────────────────────────────────────
        if (!forceUpdate && Files.exists(CACHE_DIR.resolve("kernel/Kernel.java"))) {
            System.out.println("[BOOT] Using cached MK8 at: " + CACHE_DIR);
            System.out.println("[BOOT] Run with --update to refresh.\n");
            return CACHE_DIR;
        }

        // ── 3. Download from GitHub ───────────────────────────────────────────
        return download(forceUpdate);
    }

    private static Path download(boolean isUpdate) throws Exception {
        System.out.println("[BOOT] " + (isUpdate ? "Updating" : "Downloading") + " MK8 from GitHub...");
        System.out.println("[BOOT] Source: " + REPO_ZIP);

        Path tmp = Files.createTempFile("mk8-", ".zip");
        try {
            // Download zip
            try (InputStream in = URI.create(REPO_ZIP).toURL().openStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            long sizeMb = Files.size(tmp) / 1024 / 1024;
            System.out.println("[BOOT] Download complete (" + sizeMb + " MB). Extracting...");

            // Wipe previous cache
            if (Files.exists(CACHE_DIR)) deleteTree(CACHE_DIR);
            Files.createDirectories(CACHE_DIR);

            // Extract — GitHub zips have a single root folder "MK8-main/"
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tmp))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!name.startsWith(ZIP_PREFIX)) { zis.closeEntry(); continue; }
                    name = name.substring(ZIP_PREFIX.length());
                    if (name.isBlank()) { zis.closeEntry(); continue; }

                    Path target = CACHE_DIR.resolve(name);
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zis.closeEntry();
                }
            }

            System.out.println("[BOOT] Extracted to: " + CACHE_DIR + "\n");
            return CACHE_DIR;

        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
