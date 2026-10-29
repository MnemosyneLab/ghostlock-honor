package dev.ghostlock.honor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

public final class HistoryFiles {
    private final File appFiles;
    public HistoryFiles(File appFiles) { this.appFiles = appFiles; }
    public static boolean allowedName(String name) {
        return name != null && name.matches("record-[0-9A-Za-z_-]+\\.txt");
    }
    public static String deletionIssue(boolean busy, boolean pending, boolean corrupt) {
        if (busy) return "有操作正在进行，请完成后再管理记录。";
        if (pending) return "恢复记录尚待核对，请先导出并复核，暂不能删除。";
        if (corrupt) return "记录索引无法读取，请先导出诊断，暂不能删除。";
        return "";
    }
    private File directory() throws IOException {
        File expected = new File(appFiles.getCanonicalFile(), "unified-records");
        if (!expected.getCanonicalFile().equals(expected)) throw new IOException("诊断目录指向异常，未删除文件。");
        return expected;
    }
    public File record(String name) throws IOException {
        if (!allowedName(name)) throw new IOException("无效的记录文件名。");
        File dir = directory(), file = new File(dir, name);
        if (!dir.equals(file.getCanonicalFile().getParentFile()) || file.isDirectory())
            throw new IOException("记录文件路径异常，未处理文件。");
        return file;
    }
    public String[] names() throws IOException {
        File dir = directory();
        if (!dir.exists()) return new String[0];
        String[] names = dir.list((parent, name) -> allowedName(name));
        if (names == null) throw new IOException("无法读取诊断目录。");
        for (String name : names) record(name);
        return names;
    }
    public boolean remove(String name) throws IOException {
        File file = record(name);
        return !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.delete();
    }
}
