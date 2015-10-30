package com.aspsine.multithreaddownload.core;

import com.aspsine.multithreaddownload.DownloadConfiguration;
import com.aspsine.multithreaddownload.DownloadException;
import com.aspsine.multithreaddownload.DownloadRequest;
import com.aspsine.multithreaddownload.architecture.ConnectTask;
import com.aspsine.multithreaddownload.architecture.DownloadResponse;
import com.aspsine.multithreaddownload.architecture.DownloadStatus;
import com.aspsine.multithreaddownload.architecture.DownloadTask;
import com.aspsine.multithreaddownload.architecture.Downloader;
import com.aspsine.multithreaddownload.db.DataBaseManager;
import com.aspsine.multithreaddownload.DownloadInfo;
import com.aspsine.multithreaddownload.db.ThreadInfo;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Created by Aspsine on 2015/10/28.
 */
public class DownloaderImpl implements Downloader, ConnectTask.OnConnectListener, DownloadTask.OnDownloadListener {

    private int mStatus;

    private DownloadRequest mRequest;

    private DownloadResponse mResponse;

    private Executor mExecutor;

    private DataBaseManager mDBManager;

    private String mTag;

    private DownloadConfiguration mConfig;

    private OnDownloaderDestroyedListener mListener;

    private ConnectTask mConnectTask;

    private List<DownloadTask> mDownloadTasks;

    public DownloaderImpl(DownloadRequest request, DownloadResponse response, Executor executor, DataBaseManager dbManager, String key, DownloadConfiguration config, OnDownloaderDestroyedListener listener) {
        mRequest = request;
        mResponse = response;
        mExecutor = executor;
        mDBManager = dbManager;
        mTag = key;
        mConfig = config;
        mListener = listener;
    }

    private void init() {
        //TODO restore last downloadInfo
    }

    @Override
    public boolean isRunning() {
        return mStatus == DownloadStatus.STATUS_START
                || mStatus == DownloadStatus.STATUS_CONNECTING
                || mStatus == DownloadStatus.STATUS_CONNECTED
                || mStatus == DownloadStatus.STATUS_PROGRESS;
    }

    @Override
    public void start() {
        mStatus = DownloadStatus.STATUS_START;
        mResponse.onStarted();
        connect();
    }

    @Override
    public void pause() {
        if (mConnectTask != null) {
            mConnectTask.cancel();
        }
        for (DownloadTask task : mDownloadTasks) {
            task.pause();
        }
    }

    @Override
    public void cancel() {
        if (mConnectTask != null) {
            mConnectTask.cancel();
        }
        for (DownloadTask task : mDownloadTasks) {
            task.cancel();
        }
    }

    @Override
    public void onDestroy() {
        // trigger the onDestroy callback tell download manager
        mListener.onDestroyed(mTag, this);
    }

    @Override
    public void onConnecting() {
        mStatus = DownloadStatus.STATUS_CONNECTING;
        mResponse.onConnecting();
    }

    @Override
    public void onConnected(long time, long length, boolean isAcceptRanges) {
        mStatus = DownloadStatus.STATUS_CONNECTED;
        mResponse.onConnected(time, length, isAcceptRanges);
        download(isAcceptRanges);
    }

    @Override
    public void onConnectFailed(DownloadException de) {
        mStatus = DownloadStatus.STATUS_FAILED;
        mResponse.onConnectFailed(de);
    }

    @Override
    public void onConnectCanceled() {
        mStatus = DownloadStatus.STATUS_CANCELED;
        mResponse.onConnectCanceled();
    }

    @Override
    public void onDownloadConnecting() {
    }

    @Override
    public void onDownloadProgress(long finished, long length) {
        mStatus = DownloadStatus.STATUS_PROGRESS;
        mResponse.onDownloadProgress();
    }

    @Override
    public void onDownloadCompleted() {
        if (isAllComplete()) {
            mStatus = DownloadStatus.STATUS_COMPLETED;
            mResponse.onDownloadCompleted();
        }
    }

    @Override
    public void onDownloadPaused() {
        if (isAllPaused()) {
            mStatus = DownloadStatus.STATUS_PAUSED;
            mResponse.onDownloadPaused();
        }
    }

    @Override
    public void onDownloadCanceled() {
        if (isAllCanceled()) {
            mStatus = DownloadStatus.STATUS_CANCELED;
            mResponse.onDownloadCanceled();
        }
    }

    @Override
    public void onDownloadFailed(DownloadException de) {
        if (isAllFailed()) {
            mStatus = DownloadStatus.STATUS_FAILED;
            mResponse.onDownloadFailed(de);
        }
    }

    private void connect() {
        mConnectTask = new ConnectTaskImpl(mRequest.getUri().toString(), this);
        mExecutor.execute(mConnectTask);
    }

    private void download(boolean acceptRanges) {
        initDownloadTasks(acceptRanges);
        // start tasks
        for (DownloadTask downloadTask : mDownloadTasks) {
            mExecutor.execute(downloadTask);
        }
    }

    private void initDownloadTasks(boolean acceptRanges) {
        mDownloadTasks = new LinkedList<>();
        if (acceptRanges) {
            List<ThreadInfo> threadInfos = getMultiThreadInfos();
            for (ThreadInfo info : threadInfos) {
                mDownloadTasks.add(new MultiDownloadTask(info));
            }
        } else {
            ThreadInfo threadInfo = getSingleThreadInfo();
            mDownloadTasks.add(new SingleDownloadTask(threadInfo));
        }
    }

    private List<ThreadInfo> getMultiThreadInfos() {
        // init threadInfo from db
        List<ThreadInfo> threadInfos = mDBManager.getThreadInfos(mDownloadInfo.getUrl());
        if (threadInfos.isEmpty()) {
            for (int i = 0; i < THREAD_NUM; i++) {
                // calculate average
                final long average = mDownloadInfo.getLength() / THREAD_NUM;
                long end = 0;
                long start = average * i;
                if (i == THREAD_NUM - 1) {
                    end = mDownloadInfo.getLength();
                } else {
                    end = start + average - 1;
                }
                ThreadInfo threadInfo = new ThreadInfo(i, mDownloadInfo.getUrl(), start, end, 0);
                threadInfos.add(threadInfo);
            }
        }
        return threadInfos;
    }

    private ThreadInfo getSingleThreadInfo() {
        ThreadInfo threadInfo = new ThreadInfo(0, mRequest.getUri().toString(), 0);
        return threadInfo;
    }

    private boolean isAllComplete() {
        boolean allFinished = true;
        for (DownloadTask task : mDownloadTasks) {
            if (!task.isComplete()) {
                allFinished = false;
                break;
            }
        }
        return allFinished;
    }

    private boolean isAllFailed() {
        boolean allFailed = true;
        for (DownloadTask task : mDownloadTasks) {
            if (!task.isFailure()) {
                allFailed = false;
                break;
            }
        }
        return allFailed;
    }

    private boolean isAllPaused() {
        boolean allPaused = true;
        for (DownloadTask task : mDownloadTasks) {
            if (!task.isPaused()) {
                allPaused = false;
                break;
            }
        }
        return allPaused;
    }

    private boolean isAllCanceled() {
        boolean allCanceled = true;
        for (DownloadTask task : mDownloadTasks) {
            if (task.isCanceled()) {
                allCanceled = false;
                break;
            }
        }
        return allCanceled;
    }
}
