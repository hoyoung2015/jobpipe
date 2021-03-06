package org.deephacks.jobpipe;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JobSchedule {
  private final TimeRange timeRange;
  private final int scheduleId;
  private final List<Map<String, List<Node>>> tasks;
  private final boolean verbose;
  private final List<Node> schedule = new ArrayList<>();

  private JobSchedule(JobScheduleBuilder builder) {
    this.timeRange = builder.timeRange;
    this.tasks = builder.tasks;
    this.verbose = builder.verbose;
    this.scheduleId = builder.scheduleId;
    if (builder.pipelineContext != null) {
      builder.pipelineContext.setSchedule(this);
    }
  }

  public static JobScheduleBuilder newSchedule(PipelineContext context) {
    return new JobScheduleBuilder(context);
  }

  /**
   * @param timeFormat the time range that this schedule should execute.
   */
  public static JobScheduleBuilder newSchedule(String timeFormat) {
    return new JobScheduleBuilder(timeFormat);
  }

  /**
   * @param range the time range that this schedule should execute.
   */
  public static JobScheduleBuilder newSchedule(TimeRange range) {
    return new JobScheduleBuilder(range);
  }

  public int getScheduleId() {
    return scheduleId;
  }

  private List<Node> getJobSchedule(String targetTaskId) {
    List<Node> fullGraph = new ArrayList<>();
    for (Map<String, List<Node>> intervalGraph : tasks) {
      List<Node> nodes = intervalGraph.values().stream()
        .flatMap(tasks -> tasks.stream())
        .collect(Collectors.toList());
      fullGraph.addAll(getJobSchedule(nodes, targetTaskId));

    }
    return fullGraph;
  }

  /**
   * Breadth first search.
   */
  private List<Node> getJobSchedule(List<Node> graph, String targetTaskId) {
    ArrayList<Node> result = new ArrayList<>();
    if (graph == null || graph.size() == 0) {
      return graph;
    }
    if (targetTaskId != null && !targetTaskId.isEmpty()) {
      graph = createGraphFrom(targetTaskId, graph);
    }
    // keep track of all neighbours
    HashMap<Node, Integer> neighbours = new HashMap<>();
    for (Node node : graph) {
      for (Node neighbour : node.getDirectDependencies()) {
        neighbours.compute(neighbour, (key, val) -> val == null ? 1 : val + 1);
      }
    }
    // add root nodes
    Queue<Node> queue = new LinkedList<>();
    for (Node node : graph) {
      if (!neighbours.containsKey(node)) {
        queue.offer(node);
        result.add(node);
      }
    }
    // go through all children
    while (!queue.isEmpty()) {
      Node node = queue.poll();
      for (Node n : node.getDirectDependencies()) {
        neighbours.put(n, neighbours.get(n) - 1);
        if (neighbours.get(n) == 0) {
          result.add(n);
          queue.offer(n);
        }
      }
    }
    Collections.reverse(result);
    return result;
  }

  private List<Node> createGraphFrom(String targetTaskId, List<Node> graph) {
    Pattern pattern = Pattern.compile(targetTaskId);
    ArrayList<Node> result = new ArrayList<>();
    for (Node node : graph) {
      if (pattern.matcher(node.getId()).find()) {
        result.add(node);
        result.addAll(node.getDependencies());
      }
    }
    return result;
  }

  private List<Node> execute(String targetTaskId) {
    List<Node> jobSchedule = getJobSchedule(targetTaskId);
    if (jobSchedule.isEmpty()) {
      return new ArrayList<>();
    }
    for (Node n : jobSchedule) {
      if (!n.getStatus().newTask()) {
        n.getStatus().abort();
      }
      schedule.add(n);
    }
    for (Node n : schedule) {
      new ScheduleTask(n).schedule();
    }
    return jobSchedule;
  }

  /**
   * @return all tasks are finished executing.
   */
  public boolean isDone() {
    for (TaskStatus status : getScheduledTasks()) {
      if (!status.isDone()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Waits until all tasks are finished executing.
   */
  public JobSchedule awaitDone() {
    while (!isDone()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Debug.debug(e, verbose);
        throw new RuntimeException(e);
      }
    }
    return this;
  }

  public void shutdownAfter() {
    for (TaskStatus status : getScheduledTasks()) {
      try {
        status.getContext().node.getScheduler().shutdown();
      } catch (Exception e) {
        Debug.debug(e, verbose);
      }
    }
  }

  /**
   * @return all tasks that have been scheduled, including finished tasks.
   */
  public List<TaskStatus> getScheduledTasks() {
    return schedule.stream().map(node -> node.getStatus()).collect(Collectors.toList());
  }

  /**
   * @return all tasks that have been scheduled, including finished tasks, mapped by task id.
   */
  public Map<String, List<TaskStatus>> getScheduledTasksMap() {
    return schedule.stream().map(n -> n.getStatus())
      .collect(Collectors.groupingBy(s -> s.getContext().getId()));
  }

  /**
   * @return all tasks that have failed up until now.
   */
  public List<TaskStatus> getFailedTasks() {
    return getScheduledTasks().stream()
      .filter(task -> task.hasFailed()).collect(Collectors.toList());
  }

  private class ScheduleTask implements Runnable {
    Node node;

    ScheduleTask(Node node) {
      this.node = node;
    }

    @Override
    public void run() {

      while (!node.getStatus().isDone()) {
        try {
          for (Node dep : node.getDependencies()) {
            if (dep.getStatus().hasFailed()) {
              // fail early
              node.getStatus().failedDep(dep.getContext());
              return;
            } else if (!node.dependenciesDone() && !dep.hasOutput()) {
              // wait and retry for output (this is not a failed RETRY)
              Thread.sleep(1000);
              continue;
            } else if (node.dependenciesDone() && !dep.hasOutput()) {
              // dependencies failed to produce output
              node.getStatus().failedDepNoInput(dep.getContext());
              return;
            }
          }

          if (!node.hasOutput()) {
            // don't set status to RUNNING for retries
            if (node.getStatus().code() == TaskStatus.TaskStatusCode.RETRY) {
              node.execute();
              node.getStatus().finished();
            } else if (node.getStatus().running()) {
              node.execute();
              node.getStatus().finished();
            } else {
              node.getStatus().abort();
            }
          } else {
            node.getStatus().skipped();
          }
        } catch (Throwable e) {
          if (node.getRetries() > node.getStatus().getRetries()) {
            if (!node.getStatus().retry()) {
              node.getStatus().failed(e);
            }
          } else {
            node.getStatus().failed(e);
          }
        }
      }
    }

    void schedule() {
      if (node.getStatus().scheduled()) {
        long timeout = node.getTimeout().getMillis() - System.currentTimeMillis();
        node.getScheduler().schedule(this, timeout, TimeUnit.MILLISECONDS);
      } else {
        node.getStatus().abort();
      }
    }
  }

  public static class JobScheduleBuilder {
    private PipelineContext pipelineContext;
    private TimeRange timeRange;
    private List<Map<String, List<Node>>> tasks = new ArrayList<>();
    private Scheduler defaultScheduler;
    private JobObserver observer;
    private String targetTaskId;
    private String[] args;
    private boolean verbose;
    private final int scheduleId = ThreadLocalRandom.current().nextInt();

    private JobScheduleBuilder(String timeFormat) {
      this(new TimeRange(timeFormat));
    }

    private JobScheduleBuilder(TimeRange range) {
      this.timeRange = range;
      for (int i = 0; i < timeRange.intervalsBetween(); i++) {
        tasks.add(new HashMap<>());
      }
    }

    public JobScheduleBuilder(PipelineContext context) {
      this.pipelineContext = context;
      this.timeRange = context.range;
      for (int i = 0; i < timeRange.intervalsBetween(); i++) {
        tasks.add(new HashMap<>());
      }
      this.args = context.args;
      this.targetTaskId = context.targetTaskId;
      this.verbose = context.verbose;
    }

    /**
     * @param task create a new task
     */
    public TaskBuilder task(Task task) {
      return new TaskBuilder(task, this);
    }

    public JobScheduleBuilder verbose(boolean verbose) {
      this.verbose = verbose;
      return this;
    }

    public JobScheduleBuilder args(String[] args) {
      this.args = args;
      return this;
    }

    /**
     * @param cls start executing at this task, including dependent tasks.
     */
    public JobScheduleBuilder targetTask(Class<? extends Task> cls) {
      this.targetTaskId = cls.getSimpleName();
      return this;
    }

    /**
     * @param taskId start execution at this task, including dependent tasks.
     */
    public JobScheduleBuilder targetTask(String taskId) {
      this.targetTaskId = taskId;
      return this;
    }

    /**
     * @param observer will get notified when tasks transitions into a new state.
     */
    public JobScheduleBuilder observer(JobObserver observer) {
      this.observer = observer;
      return this;
    }

    /**
     * @param scheduler the default scheduler to use for scheduling of tasks.
     */
    public JobScheduleBuilder scheduler(Scheduler scheduler) {
      this.defaultScheduler = scheduler;
      return this;
    }

    public JobSchedule execute() {
      JobSchedule jobSchedule = new JobSchedule(this);
      jobSchedule.execute(targetTaskId);
      return jobSchedule;
    }
  }


  public static class TaskBuilder {
    private final Task task;
    private String id;
    private List<String> deps = new ArrayList<>();
    private TimeRangeType timeRangeType;
    private Scheduler scheduler;
    private JobScheduleBuilder jobScheduleBuilder;
    private int retries = -1;

    private TaskBuilder(Task task, JobScheduleBuilder jobScheduleBuilder) {
      this.task = task;
      this.jobScheduleBuilder = jobScheduleBuilder;
    }

    /**
     * The id of this tasks. Defaults to the task simple classname if not explicitly
     * set by {@link org.deephacks.jobpipe.TaskSpec}.
     */
    public TaskBuilder id(String id) {
      this.id = id;
      return this;
    }

    public TaskBuilder retries(int retries) {
      this.retries = retries;
      return this;
    }

    /**
     * @param ids dependent tasks
     */
    public TaskBuilder depIds(String... ids) {
      depIds(Arrays.asList(ids));
      return this;
    }

    /**
     * @param ids dependent tasks
     */
    public TaskBuilder depIds(Collection<String> ids) {
      this.deps.addAll(ids);
      return this;
    }

    /**
     * @param tasks dependent tasks
     */
    public TaskBuilder deps(Collection<Class<?>> tasks) {
      List<String> ids = tasks.stream()
        .map(cls -> cls.getSimpleName())
        .collect(Collectors.toList());
      return depIds(ids);
    }

    /**
     * @param tasks dependent tasks
     */
    public TaskBuilder deps(Class<?>... tasks) {
      return deps(Arrays.asList(tasks));
    }

    /**
     * Defaults to {@link org.deephacks.jobpipe.TaskSpec} if set.
     *
     * @param type the time range that the task operates on.
     */
    public TaskBuilder timeRange(TimeRangeType type) {
      this.timeRangeType = type;
      return this;
    }

    /**
     * @param scheduler override the default scheduler for this task.
     */
    public TaskBuilder scheduler(Scheduler scheduler) {
      this.scheduler = scheduler;
      return this;
    }

    /**
     * Adds this task to the schedule.
     */
    public JobScheduleBuilder add() {
      TaskSpec taskSpec = task.getClass().getAnnotation(TaskSpec.class);
      if (taskSpec == null) {
        taskSpec = task.getTaskSpec();
      }
      if (taskSpec != null) {
        if (timeRangeType == null) {
          timeRangeType = taskSpec.timeRange();
        }
        if (id == null && !taskSpec.id().isEmpty()) {
          id = taskSpec.id();
        }
      }
      if (id == null) {
        id = task.getClass().getSimpleName();
      }
      if (timeRangeType == null) {
        throw new IllegalArgumentException(id + " does not have a time range.");
      }
      TimeRange currentRange = jobScheduleBuilder.timeRange.interval();
      for (int i = 0; i < jobScheduleBuilder.timeRange.intervalsBetween(); i++) {
        Map<String, List<Node>> tasks = jobScheduleBuilder.tasks.get(i);
        for (TimeRange range : timeRangeType.ranges(currentRange)) {
          Scheduler scheduler = Optional.ofNullable(this.scheduler)
            .orElseGet(() -> jobScheduleBuilder.defaultScheduler = Optional.ofNullable(jobScheduleBuilder.defaultScheduler)
              .orElseGet(() -> new DefaultScheduler()));
          Node node = new Node(id, jobScheduleBuilder.scheduleId, task, range,
            scheduler, jobScheduleBuilder.args, jobScheduleBuilder.observer,
            jobScheduleBuilder.verbose, retries);
          for (String dep : deps) {
            List<Node> nodes = tasks.get(dep);
            if (nodes == null) {
              throw new IllegalArgumentException("Dependency does not exist " + dep);
            }
            for (Node n : nodes) {
              node.addDependencies(n);
            }
          }

          List<Node> nodes = tasks.computeIfAbsent(id, key -> new ArrayList<>());
          if (nodes.contains(node)) {
            throw new IllegalArgumentException(node.getTask() + " already exist");
          }
          nodes.add(node);
        }
        currentRange = currentRange.next();
      }
      return jobScheduleBuilder;
    }
  }
}
