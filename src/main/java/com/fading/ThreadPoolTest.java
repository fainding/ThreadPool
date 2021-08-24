package com.fading;

import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class ThreadPoolTest {
    public static void main(String[] args) {
        ThreadPool threadPool = new ThreadPool(2, 6,1000,TimeUnit.MILLISECONDS);
        for (int i = 0; i < 6; i++) {
            int j = i;
            threadPool.execute(()->{
                System.out.println(j);
            });
        }
    }
}


@Slf4j
class ThreadPool {
    //核心工作线程数
    private int coreSize;
    private int count = 1;
    //任务队列
    private TaskQueue<Runnable> taskQueue;
    //线程队列
    private HashSet<Worker> workers;
    //阻塞等待时间
    private long timeOut;
    //时间单位
    private TimeUnit unit;

    public ThreadPool(int coreSize, int capacity) {
        this.coreSize = coreSize;
        this.taskQueue = new TaskQueue(capacity);
        this.workers = new HashSet<>();
    }
    public ThreadPool(int coreSize, int capacity,long timeOut,TimeUnit unit) {
        this.coreSize = coreSize;
        this.taskQueue = new TaskQueue(capacity);
        this.workers = new HashSet<>();
        this.timeOut = timeOut;
        this.unit = unit;
    }

    //执行任务方法
    public void execute(Runnable task) {

        synchronized (this) {
            if (workers.size() < coreSize) {
                //当工作线程数小于核心线程数，添加工作线程进行工作，否则进入任务队列等待
                Worker worker = new Worker(task, "线程" + count++);

                log.debug("新建worker {}",worker);

                workers.add(worker);
                worker.start();
            } else {
                log.debug("加入任务队列{}", task);
                taskQueue.putTask(task);
            }
        }
    }

    private class Worker extends Thread {
        private Runnable task;

        public Worker(Runnable task,String name) {
            super(name);
            this.task = task;
        }

        @Override
        public void run() {
            //当前线程执行任务，先检查当前任务是否为空，在检查任务队列是否存在残留任务
            while (task != null || (task = taskQueue.getTask(timeOut,unit)) != null) {
                try {
                    log.debug("{}执行任务{}",getName(),task);
                    task.run();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    task = null;
                }
            }

            //将当前工作线程移除线程表
            synchronized (workers) {
                log.debug("{}线程结束移除{}",getName(),this);
                count--;
                workers.remove(this);
            }
        }
    }

}

@Slf4j
class TaskQueue<T> {
    private LinkedList<T> queue;
    private int capacity;
    private ReentrantLock lock;
    private Condition putWait;
    private Condition getWait;

    public TaskQueue(int capacity) {
        this.queue = new LinkedList<T>();
        this.lock = new ReentrantLock();
        this.putWait = lock.newCondition();
        this.getWait = lock.newCondition();
        this.capacity = capacity;
    }

    //添加任务
    public boolean putTask(T task, long timeout, TimeUnit unit) {
        lock.lock();
        try {
            long nanos = unit.toNanos(timeout);
            //如果队列已满就进入添加条件变量中等待
            while (this.capacity == queue.size()) {
                try {
                    nanos = putWait.awaitNanos(nanos);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            queue.addLast(task);
            getWait.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean putTask(T task) {
        lock.lock();
        try {
            while (this.capacity == queue.size()) {
                try {
                    putWait.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            queue.addLast(task);
            getWait.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T getTask() {
        lock.lock();
        try {
            //如果任务队列为空，则进入获取条件变量中进行等待其他任务添加
            while (this.queue.isEmpty()) {
                try {
                    getWait.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            T task = queue.removeFirst();
            putWait.signalAll();
            return task;
        } finally {
            lock.unlock();
        }
    }

    public T getTask(long timeOut, TimeUnit unit) {
        lock.lock();
        try {
            long nanos = unit.toNanos(timeOut);
            while (this.queue.isEmpty()) {
                try {
                    //超时时间如果到达timeout 就不再等待直接返回null
                    if(nanos <= 0)return null;
                    //函数返回值就是剩余时间 nanos - spentTime
                    nanos = getWait.awaitNanos(nanos);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            T task = queue.removeFirst();
            putWait.signalAll();
            return task;
        } finally {
            lock.unlock();
        }
    }
}