package dev.ghostlock.honor;

import java.io.*;
import java.nio.file.*;
import java.util.Comparator;

public final class MarkerFilesTest {
    private static int count;
    private static void check(boolean ok) { count++;if(!ok) throw new AssertionError("marker check "+count); }
    private interface Action { void run() throws Exception; }
    private static void rejected(Action action) throws Exception {
        boolean no=false;try{action.run();}catch(IOException expected){no=true;}check(no);
    }
    public static void main(String[] args) throws Exception {
        check(MarkerFiles.marker("native-attempt.pending"));
        check(MarkerFiles.marker("attempt-boot-54"));
        check(MarkerFiles.marker("direct-run-0123456789abcdef0123456789abcdef"));
        check(!MarkerFiles.marker("../native-attempt.pending"));
        check(!MarkerFiles.marker("verified-setup.xml"));
        check(!MarkerFiles.marker("attempt-boot-54.bak"));
        Path temp=Files.createTempDirectory("ghostlock-marker-test-");
        try {
            Path files=Files.createDirectory(temp.resolve("files"));
            Path pending=Files.write(files.resolve("native-attempt.pending"),new byte[]{1});
            Path attempt=Files.write(files.resolve("attempt-boot-54"),new byte[]{2});
            Path run=Files.createDirectory(files.resolve("direct-run-0123456789abcdef0123456789abcdef"));
            Files.write(run.resolve("native.log"),new byte[]{3});
            Path setup=Files.write(temp.resolve("verified-setup.xml"),new byte[]{4});
            Path records=Files.createDirectory(files.resolve("unified-records"));
            Path record=Files.write(records.resolve("record-1.txt"),new byte[]{5});
            Path unrelated=Files.write(files.resolve("last-state.json"),new byte[]{6});
            File archived=MarkerFiles.archive(files.toFile());
            check(!Files.exists(pending)&&!Files.exists(attempt)&&!Files.exists(run));
            check(Files.readAllBytes(archived.toPath().resolve("native-attempt.pending"))[0]==1);
            check(Files.readAllBytes(archived.toPath().resolve("attempt-boot-54"))[0]==2);
            check(Files.readAllBytes(archived.toPath().resolve(run.getFileName()).resolve("native.log"))[0]==3);
            check(Files.readAllBytes(setup)[0]==4 && Files.readAllBytes(record)[0]==5 && Files.readAllBytes(unrelated)[0]==6);
            File second=MarkerFiles.archive(files.toFile());
            check(!second.equals(archived)&&archived.isDirectory());
            // A malformed/empty journal can still be explicitly reset without root or parsing it.
            Files.write(pending,new byte[0]);File third=MarkerFiles.archive(files.toFile());
            check(Files.size(third.toPath().resolve("native-attempt.pending"))==0);
            Path outside=Files.write(temp.resolve("outside"),new byte[]{9});
            Files.createSymbolicLink(pending,outside);
            rejected(()->MarkerFiles.archive(files.toFile()));
            check(Files.readAllBytes(outside)[0]==9 && Files.isSymbolicLink(pending));Files.delete(pending);
            Files.createSymbolicLink(run,temp);
            rejected(()->MarkerFiles.archive(files.toFile()));Files.delete(run);
            Path other=Files.createDirectory(temp.resolve("other"));
            Files.createSymbolicLink(other.resolve("interruption-archive"),temp);
            Path guard=Files.write(other.resolve("native-attempt.pending"),new byte[]{10});
            rejected(()->MarkerFiles.archive(other.toFile()));
            check(Files.readAllBytes(guard)[0]==10);
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(temp)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException e){throw new RuntimeException(e);}});
            }
        }
        System.out.println(count+" marker archival checks passed");
    }
}
