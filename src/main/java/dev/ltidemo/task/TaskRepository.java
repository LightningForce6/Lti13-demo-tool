package dev.ltidemo.task;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Stand-in for whatever the real application's content model is.
 *
 * <p>Kept deliberately trivial: the interesting part of this project is the protocol layer, and
 * a fake repository keeps that visible instead of burying it in persistence code.
 */
@Repository
public class TaskRepository {

    private static final List<Task> TASKS = List.of(
            new Task("t-101", "Reading response: primary sources",
                    "Short written response to the assigned reading."),
            new Task("t-102", "Lab report structure check",
                    "Guided walkthrough of method, results and discussion sections."),
            new Task("t-103", "Argument mapping exercise",
                    "Break a published argument into premises and conclusions."),
            new Task("t-104", "Research question workshop",
                    "Draft and narrow a research question with structured prompts.")
    );

    public List<Task> findAll() {
        return TASKS;
    }

    public Optional<Task> findById(String id) {
        return TASKS.stream().filter(t -> t.id().equals(id)).findFirst();
    }
}
