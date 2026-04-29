package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class TaskList {
  private final List<Task> tasks = new ArrayList<>();

  public Task add(String description) {
    Task t = new Task(UUID.randomUUID().toString(), description);
    tasks.add(t);
    return t;
  }

  public List<Task> all() {
    return List.copyOf(tasks);
  }

  public Optional<Task> findById(String id) {
    return tasks.stream().filter(t -> t.getId().equals(id)).findFirst();
  }
}
