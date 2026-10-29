package dev.ghostlock.honor.shizuku;

interface IRestoreService {
    String snapshot() = 0;
    void inspect() = 1;
    void restore() = 2;
    String readLog() = 3;
    void destroy() = 16777114;
}
