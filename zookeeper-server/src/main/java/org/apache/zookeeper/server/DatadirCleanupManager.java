/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

/**
 * This class manages the cleanup of snapshots and corresponding transaction
 * logs by scheduling the auto purge task with the specified
 * 'autopurge.purgeInterval'. It keeps the most recent
 * 'autopurge.snapRetainCount' number of snapshots and corresponding transaction
 * logs.
 *
 * 面对大流量的网络访问，ZooKeeper 会因此产生海量的数据，如果磁盘数据过多或者磁盘空间不足，则会导致 ZooKeeper 服务器
 * 不能正常运行，进而影响整个分布式系统。面对这种问题，ZooKeeper 采用了 DatadirCleanupManager 类作为历史文件的清理工具类。
 * 在 3.4.0 版本后的 ZooKeeper 中更是增加了自动清理历史数据的功能以尽量避免磁盘空间的浪费。
 */
public class DatadirCleanupManager {

    private static final Logger LOG = LoggerFactory.getLogger(DatadirCleanupManager.class);

    /**
     * Status of the dataDir purge task
     */
    public enum PurgeTaskStatus {
        NOT_STARTED,
        STARTED,
        COMPLETED
    }

    private PurgeTaskStatus purgeTaskStatus = PurgeTaskStatus.NOT_STARTED;

    /**
     * 数据快照存放地址
     */
    private final File snapDir;

    /**
     * 日志数据存放地址
     */
    private final File dataLogDir;

    /**
     * 表示需要保留的文件数目，默认保留 3 个
     */
    private final int snapRetainCount;

    /**
     * 表示清理频率，以小时为单位，需要填写一个 1 或者更大的整数。默认是 0，表示不开启定时清理功能。
     */
    private final int purgeInterval;

    /**
     * 定时器
     */
    private Timer timer;

    /**
     * Constructor of DatadirCleanupManager. It takes the parameters to schedule
     * the purge task.
     *
     * @param snapDir
     *            snapshot directory
     * @param dataLogDir
     *            transaction log directory
     * @param snapRetainCount
     *            number of snapshots to be retained after purge
     * @param purgeInterval
     *            purge interval in hours
     */
    public DatadirCleanupManager(File snapDir, File dataLogDir, int snapRetainCount, int purgeInterval) {
        this.snapDir = snapDir;
        this.dataLogDir = dataLogDir;
        this.snapRetainCount = snapRetainCount;
        this.purgeInterval = purgeInterval;
        LOG.info("autopurge.snapRetainCount set to {}", snapRetainCount);
        LOG.info("autopurge.purgeInterval set to {}", purgeInterval);
    }

    /**
     * Validates the purge configuration and schedules the purge task. Purge
     * task keeps the most recent <code>snapRetainCount</code> number of
     * snapshots and deletes the remaining for every <code>purgeInterval</code>
     * hour(s).
     * <p>
     * <code>purgeInterval</code> of <code>0</code> or
     * <code>negative integer</code> will not schedule the purge task.
     * </p>
     *
     * @see PurgeTxnLog#purge(File, File, int)
     */
    public void start() {
        if (PurgeTaskStatus.STARTED == purgeTaskStatus) {
            LOG.warn("Purge task is already running.");
            return;
        }
        // Don't schedule the purge task with zero or negative purge interval.
        if (purgeInterval <= 0) {
            LOG.info("Purge task is not scheduled.");
            return;
        }

        // 创建一个定时器，该定时器关联的线程名称指定为 PurgeTask，isDaemon = true 表示该定时器关联的线程以守护线程的方式运行
        timer = new Timer("PurgeTask", true);

        // 创建定时器任务并启动定时任务，以 purgeInterval 作为清理频率
        TimerTask task = new PurgeTask(dataLogDir, snapDir, snapRetainCount);
        timer.scheduleAtFixedRate(task, 0, TimeUnit.HOURS.toMillis(purgeInterval));

        purgeTaskStatus = PurgeTaskStatus.STARTED;
    }

    /**
     * Shutdown the purge task.
     */
    public void shutdown() {
        if (PurgeTaskStatus.STARTED == purgeTaskStatus) {
            LOG.info("Shutting down purge task.");
            timer.cancel();
            purgeTaskStatus = PurgeTaskStatus.COMPLETED;
        } else {
            LOG.warn("Purge task not started. Ignoring shutdown!");
        }
    }

    static class PurgeTask extends TimerTask {

        private File logsDir;
        private File snapsDir;
        private int snapRetainCount;

        public PurgeTask(File dataDir, File snapDir, int count) {
            logsDir = dataDir;
            snapsDir = snapDir;
            snapRetainCount = count;
        }

        @Override
        public void run() {
            LOG.info("Purge task started.");
            try {
                PurgeTxnLog.purge(logsDir, snapsDir, snapRetainCount);
            } catch (Exception e) {
                LOG.error("Error occurred while purging.", e);
            }
            LOG.info("Purge task completed.");
        }

    }

    /**
     * Returns the status of the purge task.
     *
     * @return the status of the purge task
     */
    public PurgeTaskStatus getPurgeTaskStatus() {
        return purgeTaskStatus;
    }

    /**
     * Returns the snapshot directory.
     *
     * @return the snapshot directory.
     */
    public File getSnapDir() {
        return snapDir;
    }

    /**
     * Returns transaction log directory.
     *
     * @return the transaction log directory.
     */
    public File getDataLogDir() {
        return dataLogDir;
    }

    /**
     * Returns purge interval in hours.
     *
     * @return the purge interval in hours.
     */
    public int getPurgeInterval() {
        return purgeInterval;
    }

    /**
     * Returns the number of snapshots to be retained after purge.
     *
     * @return the number of snapshots to be retained after purge.
     */
    public int getSnapRetainCount() {
        return snapRetainCount;
    }

}
