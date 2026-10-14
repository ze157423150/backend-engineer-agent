package dev.backendagent.runtime;

/** A recoverable complete-batch stop; applied changes are retained. */
public final class NoProgressException extends IllegalStateException {
    public NoProgressException() {
        super("连续12个工具批次没有新增进展，或同一文件补丁失败达到4次，已停止；请检查失败记录，再显式恢复。");
    }
}
