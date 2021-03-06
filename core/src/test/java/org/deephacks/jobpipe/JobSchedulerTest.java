package org.deephacks.jobpipe;

import org.deephacks.jobpipe.TaskStatus.TaskStatusCode;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;
import static org.deephacks.jobpipe.TimeRangeType.MINUTE;
import static org.deephacks.jobpipe.TimeRangeType.SECOND;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class JobSchedulerTest {
  private JobObserver observer = new JobObserverLog();

  /**
   * See dag.png in this directory
   */
  @Test
  public void testDirectedAsyclicGraph() {
    Task1 task = new Task1();
    JobSchedule schedule = JobSchedule.newSchedule("2015-01-14T10:00")
      .observer(observer)
      .task(task).id("1").timeRange(MINUTE).add()
      .task(task).id("4").timeRange(MINUTE).add()
      .task(task).id("10").timeRange(MINUTE).add()
      .task(task).id("12").timeRange(MINUTE).add()
      .task(task).id("11").timeRange(MINUTE).depIds("12").add()
      .task(task).id("9").timeRange(MINUTE).depIds("10", "11", "12").add()
      .task(task).id("6").timeRange(MINUTE).depIds("4", "9").add()
      .task(task).id("5").timeRange(MINUTE).depIds("4").add()
      .task(task).id("0").timeRange(MINUTE).depIds("1", "5", "6").add()
      .task(task).id("3").timeRange(MINUTE).depIds("5").add()
      .task(task).id("2").timeRange(MINUTE).depIds("0", "3").add()
      .task(task).id("7").timeRange(MINUTE).depIds("6").add()
      .task(task).id("8").timeRange(MINUTE).depIds("7").add()
      .execute();
    List<String> taskIds = schedule.getScheduledTasks().stream()
      .map(t -> t.getContext().getId()).collect(Collectors.toList());
    assertThat(taskIds.size(), is(13));
    assertTrue(taskIds.containsAll(Arrays.asList(
      "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12")));
    schedule.awaitDone();
  }

  @Test
  public void testExecuteTaskId() {
    Task1 task = new Task1();
    JobSchedule schedule = JobSchedule.newSchedule("2015-12-01T10:00")
      .observer(observer)
      .task(task).id("1").timeRange(MINUTE).add()
      .task(task).id("4").timeRange(MINUTE).add()
      .task(task).id("10").timeRange(MINUTE).add()
      .task(task).id("12").timeRange(MINUTE).add()
      .task(task).id("11").timeRange(MINUTE).depIds("12").add()
      .task(task).id("9").timeRange(MINUTE).depIds("10", "11", "12").add()
      .task(task).id("6").timeRange(MINUTE).depIds("4", "9").add()
      .task(task).id("5").timeRange(MINUTE).depIds("4").add()
      .task(task).id("0").timeRange(MINUTE).depIds("1", "5", "6").add()
      .task(task).id("3").timeRange(MINUTE).depIds("5").add()
      .task(task).id("2").timeRange(MINUTE).depIds("0", "3").add()
      .task(task).id("7").timeRange(MINUTE).depIds("6").add()
      .task(task).id("8").timeRange(MINUTE).depIds("7").add()
      .targetTask("6")
      .execute();
    List<String> taskIds = schedule.getScheduledTasks().stream()
      .map(t -> t.getContext().getId()).collect(Collectors.toList());
    assertThat(taskIds.size(), is(6));
    assertTrue(taskIds.containsAll(Arrays.asList(
      "4", "6", "9", "10", "11", "12")));
    schedule.awaitDone();
  }

  @Test
  public void testExecutePipelineContext() {
    Task1 task = new Task1();
    TimeRange range = new TimeRange("2012-10-10T10:00");
    String taskId = "0";
    String[] args = new String[]{"hello"};
    PipelineContext context = new PipelineContext(range, taskId, true, args);
    JobSchedule schedule = JobSchedule.newSchedule(context)
      .observer(observer)
      .task(task).id("1").timeRange(MINUTE).add()
      .task(task).id("4").timeRange(MINUTE).add()
      .task(task).id("10").timeRange(MINUTE).add()
      .task(task).id("12").timeRange(MINUTE).add()
      .task(task).id("11").timeRange(MINUTE).depIds("12").add()
      .task(task).id("9").timeRange(MINUTE).depIds("10", "11", "12").add()
      .task(task).id("6").timeRange(MINUTE).depIds("4", "9").add()
      .task(task).id("5").timeRange(MINUTE).depIds("4").add()
      .task(task).id("0").timeRange(MINUTE).depIds("1", "5", "6").add()
      .task(task).id("3").timeRange(MINUTE).depIds("5").add()
      .task(task).id("2").timeRange(MINUTE).depIds("0", "3").add()
      .task(task).id("7").timeRange(MINUTE).depIds("6").add()
      .task(task).id("8").timeRange(MINUTE).depIds("7").add()
      .execute();
    List<String> taskIds = schedule.getScheduledTasks().stream()
      .map(t -> t.getContext().getId()).collect(Collectors.toList());
    assertThat(taskIds.size(), is(9));
    assertTrue(taskIds.containsAll(Arrays.asList(
      "4", "6", "9", "10", "11", "12", "0", "1")));
    schedule.awaitDone();
  }

  /**
   * Test that same task class can be scheduled with different time range types.
   */
  @Test
  public void testSameTaskDifferentTimeRange() {
    Task1 task = new Task1();
    JobSchedule schedule = JobSchedule.newSchedule("2011-10-17T15:16")
      .task(task).id("1-sec").retries(10).timeRange(SECOND).add()
      .task(task).id("1-min").retries(10).timeRange(MINUTE).depIds("1-sec").add()
      .execute();
    List<TaskStatus> tasks = schedule.getScheduledTasks();
    assertThat(tasks.size(), is(60 + 1));
    schedule.awaitDone();
  }

  @Test
  public void testTooShortTimePeriod() {
    JobSchedule schedule = JobSchedule.newSchedule("2006-01-17T15:16:01")
      .task(new Task1()).id("1-min").timeRange(MINUTE).add()
      .execute();
    assertThat(schedule.getScheduledTasks().size(), is(0));
  }

  @Test
  public void testMissingDep() {
    try {
      JobSchedule.newSchedule("2000-01-17T15:16")
        .task(new Task1()).id("1-min").timeRange(MINUTE).depIds("missing").add()
        .execute();
      fail("should fail");
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage(), containsString("missing"));
    }
  }

  @Test
  public void testMissingDepInput() {
    JobSchedule schedule = JobSchedule.newSchedule("1995-01-17")
      .task(new MissingOutputTask()).id("missing").timeRange(TimeRangeType.MINUTE).add()
      .task(new Task1()).id("task1").timeRange(TimeRangeType.HOUR).depIds("missing").add()
      .execute().awaitDone();
    Map<String, List<TaskStatus>> tasks = schedule.getScheduledTasksMap();
    List<TaskStatus> missing = tasks.get("missing");
    assertThat(missing.size(), is(1440));
    missing.stream().forEach(t -> assertThat(t.code(), is(TaskStatusCode.FINISHED)));
    List<TaskStatus> task1 = tasks.get("task1");
    assertThat(task1.size(), is(24));
    task1.stream().forEach(t -> assertThat(t.code(), is(TaskStatusCode.ERROR_NO_INPUT)));
  }

  @Test
  public void testDifferentTaskTypes() {
    Scheduler scheduler = new DefaultScheduler(1);
    JobSchedule.newSchedule("2011-01-17T15:16")
      .observer(observer)
      .task(new Task1()).retries(10).timeRange(SECOND).add()
      .task(new Task2()).retries(10).deps(Task1.class).scheduler(scheduler).add()
      .execute().awaitDone();
  }

  @Test
  public void testScheduleId() {
    JobSchedule schedule = JobSchedule.newSchedule("2011-01-17T15:16")
      .task(new Task1()).timeRange(SECOND).add()
      .task(new Task2()).deps(Task1.class).add()
      .execute()
      .awaitDone();

    int scheduleId = schedule.getScheduleId();
    schedule.getScheduledTasks().stream()
      .map(t -> t.getContext().getScheduleId())
      .forEach(id -> assertThat(id, is(scheduleId)));
  }

  @Test
  public void testMultipleIntervals() {
    JobSchedule schedule = JobSchedule.newSchedule("2011-01-17T10/2011-01-17T12")
      .task(new Task1()).timeRange(MINUTE).add()
      .task(new Task2()).timeRange(TimeRangeType.HOUR).deps(Task1.class).add()
      .execute().awaitDone();

    int numTask1 = schedule.getScheduledTasksMap().get("Task1").size();
    assertThat(numTask1, is(120));
    int numTask2 = schedule.getScheduledTasksMap().get("Task2").size();
    assertThat(numTask2, is(2));

    List<TaskStatus> tasks = schedule.getScheduledTasks();
    assertThat(tasks.get(0).getContext().toString(), is("[Task1,MINUTE,2011-01-17T10:59]"));
    assertThat(tasks.get(59).getContext().toString(), is("[Task1,MINUTE,2011-01-17T10:00]"));
    assertThat(tasks.get(60).getContext().toString(), is("[Task2,HOUR,2011-01-17T10]"));
    assertThat(tasks.get(61).getContext().toString(), is("[Task1,MINUTE,2011-01-17T11:59]"));
    assertThat(tasks.get(120).getContext().toString(), is("[Task1,MINUTE,2011-01-17T11:00]"));
    assertThat(tasks.get(121).getContext().toString(), is("[Task2,HOUR,2011-01-17T11]"));
  }

  @Test
  public void testOutputFromDependency() {
    JobSchedule.newSchedule("2013-01-17T15:16")
      .task(new Task1()).retries(10).timeRange(SECOND).add()
      .task(new CheckOutputTask()).timeRange(TimeRangeType.MINUTE).deps(Task1.class).add()
      .execute().awaitDone();
  }

  @Test
  public void testAbortingObserver() {
    JobSchedule schedule = JobSchedule.newSchedule("2013-12-18T15:16")
      .observer(status -> false)
      .task(new Task1()).timeRange(SECOND).add()
      .task(new Task2()).timeRange(TimeRangeType.MINUTE).deps(Task1.class).add()
      .execute().awaitDone();
    List<TaskStatus> failedTasks = schedule.getFailedTasks();
    assertThat(failedTasks.size(), is(61));
    failedTasks.stream().map(t -> t.code())
      .forEach(c -> assertThat(c, is(TaskStatusCode.ERROR_ABORTED)));
  }

  @Test
  public void testRetryFailingTask() {
    FailingTask failingTask = new FailingTask();
    JobSchedule schedule = JobSchedule.newSchedule("1913-12-18T15:16")
      .task(failingTask).retries(3).timeRange(MINUTE).add()
      .execute().awaitDone();
    List<TaskStatus> failedTasks = schedule.getFailedTasks();
    assertThat(failedTasks.size(), is(1));
    failedTasks.stream().forEach(c -> {
      assertThat(c.code(), is(TaskStatusCode.ERROR_EXECUTE));
      assertThat(c.getRetries(), is(3));
    });
  }

  @Test
  public void testRetryFailingTaskWithDep() {
    FailingTask failingTask = new FailingTask();
    JobSchedule schedule = JobSchedule.newSchedule("2013-12-18T15:16")
      .task(failingTask).retries(3).timeRange(SECOND).add()
      .task(new Task2()).timeRange(TimeRangeType.MINUTE).deps(FailingTask.class).add()
      .execute().awaitDone();

    List<TaskStatus> failedTasks = schedule.getScheduledTasksMap().get("FailingTask");

    assertThat(failedTasks.size(), is(60));
    failedTasks.stream().forEach(c -> {
      assertThat(c.code(), is(TaskStatusCode.ERROR_EXECUTE));
      assertThat(c.getRetries(), is(3));
    });

    schedule.getScheduledTasksMap().get("Task2")
      .stream().forEach(c -> {
      assertThat(c.code(), is(TaskStatusCode.ERROR_DEPENDENCY));
    });
  }

  @Test(timeout = 15_000)
  public void testFailedTaskAbortsExecution() {
    for (int i = 0; i < 3; i++) {
      JobSchedule schedule = JobSchedule.newSchedule("1999-01-17")
        .observer(observer)
        .task(new FailingTask()).timeRange(TimeRangeType.HOUR).add()
        .task(new Task1()).timeRange(TimeRangeType.DAY).deps(FailingTask.class).add()
        .execute().awaitDone();
      List<TaskStatus> tasks = schedule.getScheduledTasks();
      assertThat(tasks.size(), is(24 + 1));

      List<TaskStatus> errors = tasks.stream()
        .filter(t -> t.code() == TaskStatusCode.ERROR_EXECUTE)
        .collect(Collectors.toList());
      assertThat(errors.size(), is(24));
      for (TaskStatus t : errors) {
        assertThat(t.getContext().getId(), is("FailingTask"));
      }

      List<TaskStatus> deps = tasks.stream()
        .filter(t -> t.code() == TaskStatusCode.ERROR_DEPENDENCY)
        .collect(Collectors.toList());
      assertThat(deps.size(), is(1));
      assertThat(deps.get(0).getContext().getId(), is("Task1"));
    }
  }

  public static class FailingTask implements Task {
    @Override
    public void execute(TaskContext ctx) {
      throw new RuntimeException("message");
    }

    @Override
    public TaskOutput getOutput(TaskContext ctx) {
      return new TmpFileOutput();
    }
  }

  public static class MissingOutputTask implements Task {

    @Override
    public void execute(TaskContext ctx) {
      // do not write output
    }

    @Override
    public TaskOutput getOutput(TaskContext ctx) {
      return new TaskOutput() {
        @Override
        public boolean exist() {
          return false;
        }

        @Override
        public Object get() {
          return null;
        }
      };
    }
  }

  public static class CheckOutputTask implements Task {
    TmpFileOutput output = new TmpFileOutput();

    @Override
    public void execute(TaskContext ctx) {
      List<File> files = ctx.getDependecyOutput().stream()
        .map(o -> (File) o.get()).peek(file -> assertTrue(file.exists()))
        .collect(Collectors.toList());
      assertThat(files.size(), is(60));
      output.create();
    }

    @Override
    public TaskOutput getOutput(TaskContext ctx) {
      return output;
    }
  }


  @TaskSpec(timeRange = TimeRangeType.DAY)
  public static class Task1 implements Task {
    TmpFileOutput output = new TmpFileOutput();

    @Override
    public void execute(TaskContext ctx) {
      output.create();
    }

    @Override
    public TaskOutput getOutput(TaskContext ctx) {
      return output;
    }
  }

  @TaskSpec(timeRange = TimeRangeType.MINUTE)
  public static class Task2 implements Task {
    TmpFileOutput output = new TmpFileOutput();

    @Override
    public void execute(TaskContext ctx) {
      output.create();
    }

    @Override
    public TaskOutput getOutput(TaskContext ctx) {
      return output;
    }
  }
}
