package org.deephacks.jobpipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class TaskStatus {
  private static final Logger logger = LoggerFactory.getLogger(TaskStatus.class);
  private TaskContext context;
  private Object failReason;
  private TaskStatusCode code;
  private long lastUpdate = 0;

  public TaskStatus(TaskContext context) {
    this.context = context;
    setCode(TaskStatusCode.NEW);
    setLastUpdate();
  }

  public Optional<Object> getFailReason() {
    return Optional.ofNullable(failReason);
  }

  public TaskStatusCode code() {
    return code;
  }

  public boolean hasFailed() {
    return TaskStatusCode.ERROR_DEPENDENCY == code ||
      TaskStatusCode.ERROR_EXECUTE == code;
  }

  public long getLastUpdate() {
    return lastUpdate;
  }

  void setCode(TaskStatusCode code) {
    if (this.code != code) {
      this.code = code;
      logger.info("{} -> {}", context.node, code);
    }
    setLastUpdate();
  }

  void failed(Throwable e) {
    this.failReason = e;
    setCode(TaskStatusCode.ERROR_EXECUTE);
  }

  void failedDep(TaskContext failedDep) {
    this.failReason = failedDep;
    setCode(TaskStatusCode.ERROR_DEPENDENCY);
  }

  void finished() {
    setCode(TaskStatusCode.FINISHED);
  }

  void skipped() {
    setCode(TaskStatusCode.SKIPPED);
  }

  void running() {
    setCode(TaskStatusCode.RUNNING);
  }

  void scheduled() {
    setCode(TaskStatusCode.SCHEDULED);
  }

  void setLastUpdate() {
    this.lastUpdate = System.currentTimeMillis();
  }

  public enum TaskStatusCode {
    NEW, SCHEDULED, FINISHED, SKIPPED, RUNNING, ERROR_EXECUTE, ERROR_DEPENDENCY, ABORTED
  }
}
