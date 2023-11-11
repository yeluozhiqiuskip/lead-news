package com.heima.schedule.service;

import com.heima.model.schedule.dtos.Task;

public interface TaskService {

    /*
    add tasks
     */
    public Long addTask(Task taks);

    public Boolean cancelTask(Long taskId);
    public Task poll(int type, int priority);
}
