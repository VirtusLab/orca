package com.example;

public class Task {
  private final String id;
  private final String description;
  private boolean done;

  public Task(String id, String description) {
    this.id = id;
    this.description = description;
    this.done = false;
  }

  public String getId() {
    return id;
  }

  public String getDescription() {
    return description;
  }

  public boolean isDone() {
    return done;
  }

  public void markDone() {
    this.done = true;
  }
}
