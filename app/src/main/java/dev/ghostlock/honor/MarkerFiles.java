package dev.ghostlock.honor;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class MarkerFiles {
    private MarkerFiles() {}
    public static boolean marker(String name) {
        return name.equals("native-attempt.pending") || name.matches("attempt-boot-[0-9]+")
            || name.matches("direct-run-[0-9a-f]{32}");
    }
    public static File archive(File files) throws IOException {
        File[] entries=files.listFiles();
        if(entries==null) throw new IOException("无法读取本机标记，未清除。");
        List<File> selected=new ArrayList<>();
        for(File entry:entries) if(marker(entry.getName())) {
            boolean directory=entry.getName().startsWith("direct-run-");
            if(!(directory?Files.isDirectory(entry.toPath(),LinkOption.NOFOLLOW_LINKS)
                :Files.isRegularFile(entry.toPath(),LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("标记路径类型异常，保留现场："+entry.getName());
            selected.add(entry);
        }
        File root=new File(files,"interruption-archive");
        if(Files.exists(root.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS)) throw new IOException("封存目录异常。");
        } else if(!root.mkdir()) throw new IOException("无法建立封存目录。");
        File archive=new File(root,"reset-"+UUID.randomUUID().toString());
        if(!archive.mkdir()) throw new IOException("无法建立本次封存目录。");
        for(File entry:selected) Files.move(entry.toPath(),new File(archive,entry.getName()).toPath());
        return archive;
    }
}
