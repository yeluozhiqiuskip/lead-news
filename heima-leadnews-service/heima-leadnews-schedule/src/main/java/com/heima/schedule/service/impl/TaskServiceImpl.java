package com.heima.schedule.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.heima.common.constants.ScheduleConstants;
import com.heima.common.redis.CacheService;
import com.heima.model.schedule.dtos.Task;
import com.heima.model.schedule.pojos.Taskinfo;
import com.heima.model.schedule.pojos.TaskinfoLogs;
import com.heima.schedule.mapper.TaskinfoLogsMapper;
import com.heima.schedule.mapper.TaskinfoMapper;
import com.heima.schedule.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.lang.reflect.InvocationTargetException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;


@Service
@Transactional
@Slf4j
public class TaskServiceImpl implements TaskService {

    @Autowired
    private TaskinfoMapper taskinfoMapper;
    @Autowired
    private TaskinfoLogsMapper taskinfoLogsMapper;

    @Autowired
    private CacheService cacheService;

    @Override
    public Long addTask(Task task) {
        //1 add task to databse
        Boolean sucess = null;
        try {
            sucess = addTaskToDb(task);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        //2 add task to redis
        if(sucess){
             addTaskToCache(task);
        }

        //2.1 if execution time <= now, add to list

        //2.2 if execution time > now&& executiion time < preset time, add to zset
        return null;
    }

    @Override
    public Boolean cancelTask(Long taskId) {

        Boolean flag = false;

        //1 delete task, update taskinfo
       Task task = updateDb(taskId, ScheduleConstants.CANCELLED);

        //2 delete task in Redis
       if(task!= null){
           removeTaskFromCache(task);
           flag = true;

        }
        return flag;
    }

    @Override
    public Task poll(int type, int priority) {


        Task task = null;

        try{
            String key = type + "_" + priority;

            String task_json = cacheService.lRightPop(ScheduleConstants.TOPIC + key);
            if(StringUtils.isNotBlank(task_json)){
                task = JSON.parseObject(task_json, Task.class);
            }
            updateDb(task.getTaskId(),ScheduleConstants.EXECUTED);

        }catch(Exception e){
            e.printStackTrace();
            log.error("task execution exception");
        }

        return task;
    }

    private void removeTaskFromCache(Task task) {

        String key = task.getTaskId()+""+task.getPriority();
        if(task.getExecuteTime()<= System.currentTimeMillis()){
            cacheService.lRemove(ScheduleConstants.TOPIC +key, 0, JSON.toJSONString(task));
        }else{
            cacheService.zRemove(ScheduleConstants.FUTURE +key,JSON.toJSON(task));
        }
    }

    private Task updateDb(Long taskId, int status) {

        Task task =null;
        try {
            task = new Task();
            taskinfoMapper.deleteById(taskId);
            TaskinfoLogs taskinfoLogs = taskinfoLogsMapper.selectById(taskId);
            taskinfoLogs.setStatus(status);
            taskinfoLogsMapper.updateById(taskinfoLogs);
            BeanUtils.copyProperties(taskinfoLogs,task);
            task.setExecuteTime(taskinfoLogs.getExecuteTime().getTime());
        } catch (Exception e) {
            log.error("task cancel failed. The task_id is {}", task.getTaskId());
            throw new RuntimeException(e);
        }

        return task;
    }

    private void addTaskToCache(Task task) {
        String key = task.getTaskType() + "" + task.getPriority();
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MINUTE,5);
        long setTime = calendar.getTimeInMillis();

        if(task.getExecuteTime() <= System.currentTimeMillis()){
            cacheService.lLeftPush(ScheduleConstants.TOPIC + key, JSON.toJSONString(task));
        }else if(task.getExecuteTime()>= System.currentTimeMillis() && task.getExecuteTime() <= setTime){
            cacheService.zAdd(ScheduleConstants.FUTURE+key, JSON.toJSONString(task), task.getExecuteTime());


        }
    }

    private Boolean addTaskToDb(Task task) throws InvocationTargetException, IllegalAccessException {

        Boolean flag = false;
        try{
            Taskinfo taskinfo = new Taskinfo();
            BeanUtils.copyProperties(task, taskinfo);
            taskinfo.setExecuteTime(new Date(task.getExecuteTime()));
            taskinfoMapper.insert(taskinfo);

            TaskinfoLogs taskinfoLogs = new TaskinfoLogs();
            BeanUtils.copyProperties(task, taskinfoLogs);
            taskinfoLogs.setVersion(1);
            taskinfoLogs.setStatus(ScheduleConstants.SCHEDULED);
            taskinfoLogsMapper.insert(taskinfoLogs);
            flag = true;
        }catch (Exception e){
            e.printStackTrace();
        }
        return flag;

    }

    @Scheduled(cron = "0 */1 * * * ?")
    public void refresh() {

        String token = cacheService.tryLock("FUTURE_TASK_SYNC", 1000 * 30);

        if (!token.isEmpty()) {

            log.info("scheduled refresh of the future tasks");
            //attain all keys of future tasks
            Set<String> futureKeys = cacheService.scan(ScheduleConstants.FUTURE + "*");

            //query the data with conditions
            for (String futureKey : futureKeys) {

                //get the topic key of the task
                String topicKey = ScheduleConstants.TOPIC + futureKey.split(ScheduleConstants.FUTURE)[1];
                Set<String> tasks = cacheService.zRangeByScore(futureKey, 0, System.currentTimeMillis());

                if (!tasks.isEmpty()) {
                    cacheService.refreshWithPipeline(futureKey, topicKey, tasks);
                    log.info("succesfully refreshed the task " + futureKey + "to" + topicKey);
                }

            }

        }
    }

    @PostConstruct
    @Scheduled(cron = "0 */5 * * * ?")
    public void reloadData() throws InvocationTargetException, IllegalAccessException {
        // clear data in cache List and zset
        clearCache();
        // query the tasks which fulfill the condition
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MINUTE, 5);
        List<Taskinfo> taskinfoList = taskinfoMapper.selectList(Wrappers.<Taskinfo>lambdaQuery().lt(Taskinfo::getExecuteTime, calendar.getTime()));
        
        if(taskinfoList!= null && taskinfoList.size()>0){
            for (Taskinfo taskinfo : taskinfoList) {
                Task task = new Task();
                BeanUtils.copyProperties(taskinfo,task);
                task.setExecuteTime(taskinfo.getExecuteTime().getTime());
                addTaskToCache(task);
            }
        }
    }

    public void clearCache(){
        Set<String> topicKeys = cacheService.scan(ScheduleConstants.TOPIC + "*");
        Set<String> futureKeys = cacheService.scan(ScheduleConstants.FUTURE + "*");
        cacheService.delete(topicKeys);
        cacheService.delete(futureKeys);

    }
}
