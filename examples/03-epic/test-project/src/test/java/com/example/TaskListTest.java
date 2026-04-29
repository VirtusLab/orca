package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskListTest {
  @Test
  void addsTasks() {
    TaskList list = new TaskList();
    Task t = list.add("buy milk");
    assertEquals("buy milk", t.getDescription());
    assertEquals(1, list.all().size());
  }

  @Test
  void marksTaskAsDone() {
    TaskList list = new TaskList();
    Task t = list.add("buy milk");
    t.markDone();
    assertTrue(list.findById(t.getId()).orElseThrow().isDone());
  }
}
