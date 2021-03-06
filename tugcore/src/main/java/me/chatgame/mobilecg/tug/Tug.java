package me.chatgame.mobilecg.tug;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;

import me.chatgame.mobilecg.tug.db.TugDbHelper;
import me.chatgame.mobilecg.tug.db.TugTaskDao;
import me.chatgame.mobilecg.tug.util.FileUtils;
import me.chatgame.mobilecg.tug.util.LogUtil;
import me.chatgame.mobilecg.tug.util.NetworkType;
import me.chatgame.mobilecg.tug.util.NetworkUtil;
import me.chatgame.mobilecg.tug.util.interfaces.INetworkUtil;

/**
 * Created by star on 16/4/5.
 */
public class Tug {
    private static final int BUFFER_SIZE_WIFI = 250 * 1024;
    private static final int BUFFER_SIZE_4G = 100 * 1024;
    private static final int BUFFER_SIZE_3G = 50 * 1024;
    private static final int BUFFER_SIZE_2G = 10 * 1024;


    private static Tug instance;
    private int threads;
    private Executor taskExecutor;
    private Executor daoExecutor = Executors.newSingleThreadExecutor(new TugThreadFactory());
    private String rootPath;
    private Map<String, Set<DownloadListener>> listenerMap = new HashMap<>();
    private List<TugWorker> workers = new ArrayList<>();
    private BlockingQueue<TugTask> waitingQueue = new PriorityBlockingQueue<>();
    private Queue<TugTask> workingQueue = new ConcurrentLinkedQueue<>();
    private Queue<TugTask> idleQueue = new ConcurrentLinkedQueue<>();
    private TugTaskDao tugTaskDao;
    private Context context;
    private INetworkUtil networkUtil;

    private Tug() {

    }

    public synchronized static Tug getInstance() {
        if (instance == null) {
            throw new RuntimeException("Tug instance is not set!");
        }
        return instance;
    }

    public synchronized static void setInstance(Tug tug) {
        instance = tug;
    }

    TugTask takeFromWaitingQueue() throws InterruptedException {
        return waitingQueue.take();
    }

    private void moveTaskToIdleQueue(TugTask task) {
        workingQueue.remove(task);
        waitingQueue.remove(task);
        task.setStatus(TugTask.Status.IDLE);
        idleQueue.offer(task);
        tugTaskDao.updateTask(task);
    }

    void moveTaskFromIdleToWaitingQueue(TugTask task) {
        idleQueue.remove(task);
        task.setStatus(TugTask.Status.WAITING);
        waitingQueue.offer(task);
        tugTaskDao.updateTask(task);
    }

    void addRetryTask(TugTask task) {
        workingQueue.remove(task);
        task.setStatus(TugTask.Status.WAITING);
        waitingQueue.offer(task);
        tugTaskDao.updateTask(task);
    }

    public List<TugTask> getAllTasksInDb() {
        return tugTaskDao.getAllTasks();
    }

    public List<TugTask> getAllTasksInMemory() {
        List<TugTask> allTasks = new ArrayList<>();
        allTasks.addAll(waitingQueue);
        allTasks.addAll(workingQueue);
        allTasks.addAll(idleQueue);
        return allTasks;
    }

    public synchronized void addListener(String url, DownloadListener listener) {
        if (listener == null) {
            return;
        }
        Set<DownloadListener> list = listenerMap.get(url);
        if (list == null) {
            list = new HashSet<>();
            listenerMap.put(url, list);
        }
        list.add(listener);
    }

    /**
     * Just remove download listener without deleting task
     * @param url download url
     * @param listener callback listener to remove
     */
    public synchronized void removeListener(String url, DownloadListener listener) {
        if (listener == null) {
            return;
        }
        Set<DownloadListener> list = listenerMap.get(url);
        if (list != null) {
            list.remove(listener);
        }
    }

    /**
     * Remove specified listener from all urls
     * @param listener callback listener to remove
     */
    public synchronized void removeListener(DownloadListener listener) {
        if (listener == null) {
            return;
        }
        Collection<Set<DownloadListener>> collection = listenerMap.values();
        if (collection != null) {
            for (Set<DownloadListener> listenerSet : collection) {
                if (listenerSet != null) {
                    listenerSet.remove(listener);
                }
            }
        }
    }

    private synchronized void removeListeners(String url) {
        listenerMap.remove(url);
    }

    private TugTask findTaskFromQueue(Queue<TugTask> queue, TugTask dstTask) {
        for (TugTask task : queue) {
            if (task.equals(dstTask)) {
                return task;
            }
        }
        return null;
    }

    private TugTask findTaskInAllQueues(TugTask dstTask) {
        TugTask foundTask = findTaskFromQueue(waitingQueue, dstTask);
        if (foundTask == null) {
            foundTask = findTaskFromQueue(workingQueue, dstTask);
        }
        if (foundTask == null) {
            foundTask = findTaskFromQueue(idleQueue, dstTask);
        }
        return foundTask;
    }

    private boolean containsInAllQueues(TugTask task) {
        if (waitingQueue.contains(task) || workingQueue.contains(task) || idleQueue.contains(task)) {
            return true;
        }
        return false;
    }

    /**
     * Add download task
     * @param task tug task
     * @param listener callback listener to register
     * @return a tug task that is added, if task exists, the existed task will be returned
     */
    TugTask addTask(TugTask task, DownloadListener listener) {
        if (!containsInAllQueues(task)) {
            task.setStatus(TugTask.Status.WAITING);
            waitingQueue.offer(task);

            final TugTask finalTask = task;
            daoExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    tugTaskDao.addTask(finalTask);
                }
            });
        } else {
            TugTask foundTask = findTaskFromQueue(idleQueue, task);
            if (foundTask != null) {
                // move to waiting queue
                moveTaskFromIdleToWaitingQueue(foundTask);
            } else {
                foundTask = findTaskFromQueue(waitingQueue, task);
                if (foundTask == null) {
                    foundTask = findTaskFromQueue(workingQueue, task);
                }
                if (foundTask != null) {
                    foundTask.increaseRetryCount();
                    task = foundTask;
                }
            }
        }
        if (task != null) {
            addListener(task.getUrl(), listener);
        }
        return task;
    }

    /**
     * Add download task
     * @param url url to download
     * @param fileType see {@link TugTask.FileType}
     * @param destLocalPath local path to save the downloaded file
     * @param listener callback listener
     * @param priority task priority, see {@linkplain TugTask.Priority}
     * @return a tug task that is added, if task exists, the existed task will be returned
     */
    public TugTask addTask(String url, int fileType, String destLocalPath, DownloadListener listener, int priority) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        if (url.startsWith("http")) {
            String localPath = destLocalPath;
            if (TextUtils.isEmpty(destLocalPath)) {
                localPath = FileUtils.getLocalFilePath(url, fileType, rootPath);
            }
            TugTask task = new TugTask();
            task.setFileType(fileType);
            task.setStatus(TugTask.Status.WAITING);
            task.setUrl(url);
            task.setLocalPath(localPath);
            task.setPriority(priority);
            File localFile = new File(localPath);
            if (localFile.exists()) {
                if (listener != null) {
                    task.setFileTotalSize(localFile.length());
                    task.setDownloadedSize(localFile.length());
                    task.setProgress(100);
                    task.setStatus(TugTask.Status.DOWNLOADED);
                    listener.onDownloadProgress(task);
                    listener.onDownloadSuccess(task);
                }
                return task;
            } else {
                return addTask(task, listener);
            }
        }
        return null;
    }

    /**
     * Add download task
     * @param url url to download
     * @param fileType see {@link TugTask.FileType}
     * @param destLocalPath local path to save the downloaded file
     * @param listener callback listener
     * @return a tug task that is added, if task exists, the existed task will be returned
     */
    public TugTask addTask(String url, int fileType, String destLocalPath, DownloadListener listener) {
        return addTask(url, fileType, destLocalPath, listener, TugTask.Priority.NORMAL);
    }

    /**
     * Add download task
     * @param url url to download
     * @param fileType see {@link TugTask.FileType}
     * @param destLocalFolder parent folder to save the downloaded file
     * @param savedFileName file name to save
     * @param listener callback listener
     * @param priority task priority, see {@linkplain TugTask.Priority}
     * @return a tug task that is added, if task exists, the existed task will be returned
     */
    public TugTask addTask(String url, int fileType, String destLocalFolder, String savedFileName, DownloadListener listener, int priority) {
        String localPath = null;
        if (!TextUtils.isEmpty(destLocalFolder) && !TextUtils.isEmpty(savedFileName)) {
            localPath = destLocalFolder + savedFileName;
        } else if (!TextUtils.isEmpty(savedFileName)) {
            localPath = FileUtils.getLocalFilePathBySpecifiedName(savedFileName, fileType, rootPath);
        }
        return addTask(url, fileType, localPath, listener, priority);
    }

    /**
     * Add download task
     * @param url url to download
     * @param fileType see {@link TugTask.FileType}
     * @param destLocalFolder parent folder to save the downloaded file
     * @param savedFileName file name to save
     * @param listener callback listener
     * @return a tug task that is added, if task exists, the existed task will be returned
     */
    public TugTask addTask(String url, int fileType, String destLocalFolder, String savedFileName, DownloadListener listener) {
        return addTask(url, fileType, destLocalFolder, savedFileName, listener, TugTask.Priority.NORMAL);
    }

    /**
     * Remove and delete task with specified url, all listeners will be removed too
     * @param url download url for deleting task
     */
    public void deleteTask(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }

        cancelWorkingTask(url);
        TugTask task = removeTaskFromQueue(url);
        if (task == null) {
            task = new TugTask();
            task.setUrl(url);
        }
        tugTaskDao.deleteTask(task);
        downloadDeleted(task);
    }

    /**
     * Remove and delete task, if local file in task exists, it will also be deleted.
     * @param task tug task to delete
     */
    public void deleteTask(TugTask task) {
        if (task == null) {
            return;
        }
        if (!TextUtils.isEmpty(task.getLocalPath())) {
            FileUtils.deleteFile(new File(task.getLocalPath()));
        }
        deleteTask(task.getUrl());
    }

    /**
     * Pause task
     * @param url donwload url
     */
    public void pauseTask(String url) {
        cancelWorkingTask(url);
        TugTask task = new TugTask();
        task.setUrl(url);
        TugTask foundTask = findTaskFromQueue(idleQueue, task);
        if (foundTask == null) {
            foundTask = findTaskFromQueue(waitingQueue, task);
            if (foundTask == null) {
                foundTask = findTaskFromQueue(workingQueue, task);
            }
            if (foundTask != null) {
                moveTaskToIdleQueue(foundTask);
            }
        }

        if (foundTask != null) {
            task = foundTask;
        }
        downloadPaused(task);
    }

    /**
     * Resume task, if task doesn't exist a new task will be added
     * @param url download url
     */
    public void resumeTask(String url) {
        TugTask task = new TugTask();
        task.setUrl(url);
        TugTask foundTask = findTaskFromQueue(idleQueue, task);
        if (foundTask != null) {
            moveTaskFromIdleToWaitingQueue(foundTask);
            task = foundTask;
        } else {
            foundTask = findTaskFromQueue(waitingQueue, task);
            if (foundTask == null) {
                foundTask = findTaskFromQueue(workingQueue, task);
            }
            if (foundTask == null) {
                task = addTask(url, TugTask.FileType.FILE, null, null);
            } else {
                task = foundTask;
            }
        }
        downloadResumed(task);
    }

    private void cancelWorkingTask(String url) {
        TugTask task = new TugTask();
        task.setUrl(url);
        for (TugWorker worker : workers) {
            if (task.equals(worker.getCurrentTask())) {
                worker.cancelCurrentTask();
            }
        }
    }

    private TugTask removeTaskFromQueue(String url) {
        TugTask task = new TugTask();
        task.setUrl(url);
        TugTask foundTask = findTaskInAllQueues(task);
        if (foundTask != null) {
            waitingQueue.remove(foundTask);
            workingQueue.remove(foundTask);
            idleQueue.remove(foundTask);
        }
        return foundTask;
    }

    public void loadTaskFromDb() {
        List<TugTask> tasks = tugTaskDao.getUnFinishedTasks();
        for (TugTask task : tasks) {
            moveTaskToIdleQueue(task);
        }
    }

    public void start() {
        loadTaskFromDb();
        taskExecutor = Executors.newFixedThreadPool(threads, new TugThreadFactory());
        for (int i = 0; i < threads; i++) {
            TugWorker worker = new TugWorker(this);
            workers.add(worker);
            taskExecutor.execute(worker);
        }
    }

    synchronized void downloadStart(TugTask task) {
        task.setStatus(TugTask.Status.DOWNLOADING);
        workingQueue.offer(task);
        tugTaskDao.updateTask(task);
        Set<DownloadListener> listeners = listenerMap.get(task.getUrl());
        if (listeners != null) {
            for (DownloadListener listener : listeners) {
                listener.onDownloadStart(task);
            }
        }
    }

    synchronized void onDownloadProgress(TugTask task, int progress) {
        task.setProgress(progress);
        Set<DownloadListener> listeners = listenerMap.get(task.getUrl());
        if (listeners != null) {
            for (DownloadListener listener : listeners) {
                listener.onDownloadProgress(task);
            }
        }
    }

    synchronized void downloadSuccess(TugTask task) {
        task.setStatus(TugTask.Status.DOWNLOADED);
        workingQueue.remove(task);
        tugTaskDao.updateTask(task);
        Set<DownloadListener> listeners = listenerMap.get(task.getUrl());
        if (listeners != null) {
            for (DownloadListener listener : listeners) {
                listener.onDownloadSuccess(task);
            }
        }
        removeListeners(task.getUrl());
    }

    synchronized void downloadFail(TugTask task) {
        task.setStatus(TugTask.Status.FAILED);
        workingQueue.remove(task);
        tugTaskDao.updateTask(task);
        Set<DownloadListener> listeners = listenerMap.get(task.getUrl());
        if (listeners != null) {
            for (DownloadListener listener : listeners) {
                listener.onDownloadFail(task);
            }
        }
        removeListeners(task.getUrl());
    }

    synchronized void downloadDeleted(TugTask task) {
        Set<DownloadListener> listeners = listenerMap.get(task.getUrl());
        if (listeners != null) {
            for (DownloadListener listener : listeners) {
                listener.onDownloadDeleted(task);
            }
        }
        removeListeners(task.getUrl());
    }

    synchronized void downloadPaused(TugTask task) {
        Set<DownloadListener> listeners = listenerMap.get(task.getUrl());
        if (listeners != null) {
            for (DownloadListener listener : listeners) {
                listener.onDownloadPaused(task);
            }
        }
    }

    synchronized void downloadResumed(TugTask task) {
        Set<DownloadListener> listeners = listenerMap.get(task.getUrl());
        if (listeners != null) {
            for (DownloadListener listener : listeners) {
                listener.onDownloadResumed(task);
            }
        }
    }

    public int getCurrentWorkerNum() {
        return workers.size();
    }

    Context getContext() {
        return context;
    }

    int getBufferSizeByNetwork() {
        NetworkType type = networkUtil.getNetworkType();
        switch (type) {
            case NETWORK_WIFI:
                return BUFFER_SIZE_WIFI;
            case NETWORK_4G:
                return BUFFER_SIZE_4G;
            case NETWORK_3G:
                return BUFFER_SIZE_3G;
            default:
                return BUFFER_SIZE_2G;
        }
    }

    public static class Builder {
        private int threads = 2;
        private String rootPath;
        private Context context;
        private boolean needLog = true;
        private int logLevel = Log.INFO;
        public Builder(Context context) {
            this.context = context;
        }

        public Builder setThreads(int threads) {
            this.threads = threads;
            return this;
        }

        public Builder setRootPath(String rootPath) {
            this.rootPath = rootPath;
            return this;
        }

        public Builder setNeedLog(boolean needLog) {
            this.needLog = needLog;
            return this;
        }

        public Builder setLogLevel(int logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        public Tug build() {
            Tug tug = new Tug();
            tug.context = context.getApplicationContext();
            tug.threads = threads;
            tug.rootPath = rootPath;
            if (TextUtils.isEmpty(tug.rootPath)) {
                tug.rootPath = FileUtils.getCacheDir(context);
            }
            LogUtil.NEED_LOG = needLog;
            LogUtil.LOG_LEVEL = logLevel;
            TugDbHelper helper = new TugDbHelper(context.getApplicationContext());
            TugDbHelper.setInstance(helper);
            tug.tugTaskDao = new TugTaskDao();
            tug.networkUtil = new NetworkUtil(context);
            return tug;
        }
    }
}
