package com.example;

public class App {
  public static void main(String[] args) {
    TaskList list = new TaskList();
    if (args.length == 0) {
      System.out.println("Usage: todo <add|list> [description]");
      return;
    }
    String cmd = args[0];
    switch (cmd) {
      case "add" -> {
        if (args.length < 2) {
          System.err.println("add: description required");
          System.exit(1);
        }
        Task t = list.add(args[1]);
        System.out.println("added " + t.getId());
      }
      case "list" -> list.all().forEach(t ->
        System.out.printf("[%s] %s %s%n",
          t.isDone() ? "x" : " ", t.getId(), t.getDescription()));
      default -> {
        System.err.println("unknown command: " + cmd);
        System.exit(1);
      }
    }
  }
}
