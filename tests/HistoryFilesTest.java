package dev.ghostlock.honor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;

public final class HistoryFilesTest {
    private static int count;
    private static void check(boolean ok) { count++; if(!ok) throw new AssertionError("history check "+count); }
    private interface Action { void run() throws Exception; }
    private static void rejected(Action action) throws Exception {
        boolean rejected=false;
        try { action.run(); } catch(IOException expected) { rejected=true; }
        check(rejected);
    }
    public static void main(String[] args) throws Exception {
        check(HistoryFiles.allowedName("record-123.txt"));
        check(!HistoryFiles.allowedName("../native-attempt.pending"));
        check(!HistoryFiles.allowedName("record-aXtxt"));
        check(!HistoryFiles.allowedName("record-123.txt/child"));
        check(!HistoryFiles.deletionIssue(true,false,false).isEmpty());
        check(!HistoryFiles.deletionIssue(false,true,false).isEmpty());
        check(!HistoryFiles.deletionIssue(false,false,true).isEmpty());
        check(HistoryFiles.deletionIssue(false,false,false).isEmpty());
        Path temp=Files.createTempDirectory("ghostlock-history-test-");
        try {
            Path app=Files.createDirectory(temp.resolve("files"));
            Path records=Files.createDirectory(app.resolve("unified-records"));
            Path guard=Files.write(app.resolve("native-attempt.pending"),new byte[]{7});
            Path boot=Files.write(app.resolve("attempt-boot-48"),new byte[]{8});
            Path setup=Files.write(temp.resolve("verified-setup.xml"),new byte[]{9});
            Path unrelated=Files.write(records.resolve("notes.txt"),new byte[]{10});
            Files.write(records.resolve("record-one.txt"),new byte[]{1});
            Files.write(records.resolve("record-orphan.txt"),new byte[]{2});
            HistoryFiles files=new HistoryFiles(app.toFile());
            check(files.remove("record-one.txt"));
            check(!Files.exists(records.resolve("record-one.txt")));
            check(files.remove("record-one.txt"));
            for(String name:files.names()) check(files.remove(name));
            check(Files.exists(unrelated));
            check(Files.readAllBytes(guard)[0]==7 && Files.readAllBytes(boot)[0]==8 && Files.readAllBytes(setup)[0]==9);
            rejected(()->files.remove("../native-attempt.pending"));
            Path outside=Files.write(temp.resolve("outside.txt"),new byte[]{11});
            Files.createSymbolicLink(records.resolve("record-link.txt"),outside);
            rejected(()->files.remove("record-link.txt"));
            check(Files.readAllBytes(outside)[0]==11);
            Files.delete(records.resolve("record-link.txt"));
            Path directory=Files.createDirectory(records.resolve("record-dir.txt"));
            rejected(()->files.remove("record-dir.txt"));
            check(Files.isDirectory(directory)); Files.delete(directory);
            Files.delete(unrelated); Files.delete(records); Files.createSymbolicLink(records,temp);
            rejected(()->files.names());
            check(Files.readAllBytes(outside)[0]==11);
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(temp)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException error){throw new RuntimeException(error);}});
            }
        }
        System.out.println(count+" history deletion safety checks passed");
    }
}
