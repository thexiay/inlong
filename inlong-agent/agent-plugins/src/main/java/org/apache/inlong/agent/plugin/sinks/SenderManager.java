/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.agent.plugin.sinks;

import org.apache.inlong.agent.common.AgentThreadFactory;
import org.apache.inlong.agent.conf.AgentConfiguration;
import org.apache.inlong.agent.conf.JobProfile;
import org.apache.inlong.agent.constant.CommonConstants;
import org.apache.inlong.agent.core.task.MemoryManager;
import org.apache.inlong.agent.core.task.PositionManager;
import org.apache.inlong.agent.message.BatchProxyMessage;
import org.apache.inlong.agent.metrics.AgentMetricItem;
import org.apache.inlong.agent.metrics.AgentMetricItemSet;
import org.apache.inlong.agent.metrics.audit.AuditUtils;
import org.apache.inlong.agent.plugin.message.SequentialID;
import org.apache.inlong.agent.utils.AgentUtils;
import org.apache.inlong.agent.utils.ThreadUtils;
import org.apache.inlong.common.constant.ProtocolType;
import org.apache.inlong.common.metric.MetricRegister;
import org.apache.inlong.sdk.dataproxy.DefaultMessageSender;
import org.apache.inlong.sdk.dataproxy.ProxyClientConfig;
import org.apache.inlong.sdk.dataproxy.SendMessageCallback;
import org.apache.inlong.sdk.dataproxy.SendResult;

import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.inlong.agent.constant.CommonConstants.DEFAULT_PROXY_BATCH_FLUSH_INTERVAL;
import static org.apache.inlong.agent.constant.CommonConstants.PROXY_BATCH_FLUSH_INTERVAL;
import static org.apache.inlong.agent.constant.FetcherConstants.AGENT_GLOBAL_WRITER_PERMIT;
import static org.apache.inlong.agent.constant.FetcherConstants.AGENT_MANAGER_AUTH_SECRET_ID;
import static org.apache.inlong.agent.constant.FetcherConstants.AGENT_MANAGER_AUTH_SECRET_KEY;
import static org.apache.inlong.agent.constant.FetcherConstants.AGENT_MANAGER_VIP_HTTP_HOST;
import static org.apache.inlong.agent.constant.FetcherConstants.AGENT_MANAGER_VIP_HTTP_PORT;
import static org.apache.inlong.agent.constant.JobConstants.DEFAULT_JOB_PROXY_SEND;
import static org.apache.inlong.agent.constant.JobConstants.JOB_PROXY_SEND;
import static org.apache.inlong.agent.metrics.AgentMetricItem.KEY_INLONG_GROUP_ID;
import static org.apache.inlong.agent.metrics.AgentMetricItem.KEY_INLONG_STREAM_ID;
import static org.apache.inlong.agent.metrics.AgentMetricItem.KEY_PLUGIN_ID;

/**
 * proxy client
 */
public class SenderManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SenderManager.class);
    private static final SequentialID SEQUENTIAL_ID = SequentialID.getInstance();
    private final AtomicInteger SENDER_INDEX = new AtomicInteger(0);
    // cache for group and sender list, share the map cross agent lifecycle.
    private DefaultMessageSender sender;
    private LinkedBlockingQueue<AgentSenderCallback> resendQueue;
    private final ExecutorService resendExecutorService = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), new AgentThreadFactory("SendManager-Resend"));
    // sharing worker threads between sender client
    // in case of thread abusing.
    private ThreadFactory SHARED_FACTORY;
    private static final AtomicLong METRIC_INDEX = new AtomicLong(0);
    private final String managerHost;
    private final int managerPort;
    private final String netTag;
    private final String localhost;
    private final boolean isLocalVisit;
    private final int totalAsyncBufSize;
    private final int aliveConnectionNum;
    private final boolean isCompress;
    private final int msgType;
    private final boolean isFile;
    private final long maxSenderTimeout;
    private final int maxSenderRetry;
    private final long retrySleepTime;
    private final String inlongGroupId;
    private final int maxSenderPerGroup;
    private final String sourcePath;
    private final boolean proxySend;
    private volatile boolean shutdown = false;
    // metric
    private AgentMetricItemSet metricItemSet;
    private Map<String, String> dimensions;
    private PositionManager positionManager;
    private int ioThreadNum;
    private boolean enableBusyWait;
    private String authSecretId;
    private String authSecretKey;
    protected int batchFlushInterval;

    public SenderManager(JobProfile jobConf, String inlongGroupId, String sourcePath) {
        AgentConfiguration conf = AgentConfiguration.getAgentConf();
        managerHost = conf.get(AGENT_MANAGER_VIP_HTTP_HOST);
        managerPort = conf.getInt(AGENT_MANAGER_VIP_HTTP_PORT);
        proxySend = jobConf.getBoolean(JOB_PROXY_SEND, DEFAULT_JOB_PROXY_SEND);
        localhost = jobConf.get(CommonConstants.PROXY_LOCAL_HOST, CommonConstants.DEFAULT_PROXY_LOCALHOST);
        netTag = jobConf.get(CommonConstants.PROXY_NET_TAG, CommonConstants.DEFAULT_PROXY_NET_TAG);
        isLocalVisit = jobConf.getBoolean(
                CommonConstants.PROXY_IS_LOCAL_VISIT, CommonConstants.DEFAULT_PROXY_IS_LOCAL_VISIT);
        totalAsyncBufSize = jobConf
                .getInt(
                        CommonConstants.PROXY_TOTAL_ASYNC_PROXY_SIZE,
                        CommonConstants.DEFAULT_PROXY_TOTAL_ASYNC_PROXY_SIZE);
        aliveConnectionNum = jobConf
                .getInt(
                        CommonConstants.PROXY_ALIVE_CONNECTION_NUM, CommonConstants.DEFAULT_PROXY_ALIVE_CONNECTION_NUM);
        isCompress = jobConf.getBoolean(
                CommonConstants.PROXY_IS_COMPRESS, CommonConstants.DEFAULT_PROXY_IS_COMPRESS);
        maxSenderPerGroup = jobConf.getInt(
                CommonConstants.PROXY_MAX_SENDER_PER_GROUP, CommonConstants.DEFAULT_PROXY_MAX_SENDER_PER_GROUP);
        msgType = jobConf.getInt(CommonConstants.PROXY_MSG_TYPE, CommonConstants.DEFAULT_PROXY_MSG_TYPE);
        maxSenderTimeout = jobConf.getInt(
                CommonConstants.PROXY_SENDER_MAX_TIMEOUT, CommonConstants.DEFAULT_PROXY_SENDER_MAX_TIMEOUT);
        maxSenderRetry = jobConf.getInt(
                CommonConstants.PROXY_SENDER_MAX_RETRY, CommonConstants.DEFAULT_PROXY_SENDER_MAX_RETRY);
        retrySleepTime = jobConf.getLong(
                CommonConstants.PROXY_RETRY_SLEEP, CommonConstants.DEFAULT_PROXY_RETRY_SLEEP);
        isFile = jobConf.getBoolean(CommonConstants.PROXY_IS_FILE, CommonConstants.DEFAULT_IS_FILE);
        positionManager = PositionManager.getInstance();
        ioThreadNum = jobConf.getInt(CommonConstants.PROXY_CLIENT_IO_THREAD_NUM,
                CommonConstants.DEFAULT_PROXY_CLIENT_IO_THREAD_NUM);
        enableBusyWait = jobConf.getBoolean(CommonConstants.PROXY_CLIENT_ENABLE_BUSY_WAIT,
                CommonConstants.DEFAULT_PROXY_CLIENT_ENABLE_BUSY_WAIT);
        batchFlushInterval = jobConf.getInt(PROXY_BATCH_FLUSH_INTERVAL, DEFAULT_PROXY_BATCH_FLUSH_INTERVAL);
        authSecretId = conf.get(AGENT_MANAGER_AUTH_SECRET_ID);
        authSecretKey = conf.get(AGENT_MANAGER_AUTH_SECRET_KEY);

        this.sourcePath = sourcePath;
        this.inlongGroupId = inlongGroupId;

        this.dimensions = new HashMap<>();
        dimensions.put(KEY_PLUGIN_ID, this.getClass().getSimpleName());
        String metricName = String.join("-", this.getClass().getSimpleName(),
                String.valueOf(METRIC_INDEX.incrementAndGet()));
        this.metricItemSet = new AgentMetricItemSet(metricName);
        MetricRegister.register(metricItemSet);
        resendQueue = new LinkedBlockingQueue<>();
    }

    public void Start() throws Exception {
        sender = createMessageSender(inlongGroupId);
        resendExecutorService.execute(flushResendQueue());
    }

    public void Stop() {
        shutdown = true;
        resendExecutorService.shutdown();
        sender.close();
    }

    private AgentMetricItem getMetricItem(Map<String, String> otherDimensions) {
        Map<String, String> dimensions = new HashMap<>();
        dimensions.put(KEY_PLUGIN_ID, this.getClass().getSimpleName());
        dimensions.putAll(otherDimensions);
        return this.metricItemSet.findMetricItem(dimensions);
    }

    private AgentMetricItem getMetricItem(String groupId, String streamId) {
        Map<String, String> dims = new HashMap<>();
        dims.put(KEY_INLONG_GROUP_ID, groupId);
        dims.put(KEY_INLONG_STREAM_ID, streamId);
        return getMetricItem(dims);
    }

    /**
     * sender
     *
     * @param tagName group id
     * @return DefaultMessageSender
     */
    private DefaultMessageSender createMessageSender(String tagName) throws Exception {

        ProxyClientConfig proxyClientConfig = new ProxyClientConfig(
                localhost, isLocalVisit, managerHost, managerPort, tagName, netTag, authSecretId, authSecretKey);
        proxyClientConfig.setTotalAsyncCallbackSize(totalAsyncBufSize);
        proxyClientConfig.setFile(isFile);
        proxyClientConfig.setAliveConnections(aliveConnectionNum);

        proxyClientConfig.setIoThreadNum(ioThreadNum);
        proxyClientConfig.setEnableBusyWait(enableBusyWait);
        proxyClientConfig.setProtocolType(ProtocolType.TCP);

        SHARED_FACTORY = new DefaultThreadFactory("agent-client-" + sourcePath,
                Thread.currentThread().isDaemon());

        DefaultMessageSender sender = new DefaultMessageSender(proxyClientConfig, SHARED_FACTORY);
        sender.setMsgtype(msgType);
        sender.setCompress(isCompress);
        return sender;
    }

    public void sendBatch(BatchProxyMessage batchMessage) {
        sendBatchWithRetryCount(batchMessage, 0);
    }

    /**
     * Send message to proxy by batch, use message cache.
     */
    private void sendBatchWithRetryCount(BatchProxyMessage batchMessage, int retry) {
        boolean suc = false;
        while (!suc) {
            try {
                sender.asyncSendMessage(new AgentSenderCallback(batchMessage, retry),
                        batchMessage.getDataList(), batchMessage.getGroupId(), batchMessage.getStreamId(),
                        batchMessage.getDataTime(), SEQUENTIAL_ID.getNextUuid(), maxSenderTimeout, TimeUnit.SECONDS,
                        batchMessage.getExtraMap(), proxySend);
                getMetricItem(batchMessage.getGroupId(), batchMessage.getStreamId()).pluginSendCount.addAndGet(
                        batchMessage.getMsgCnt());
                suc = true;
            } catch (Exception exception) {
                suc = false;
                if (retry > maxSenderRetry) {
                    if (retry % 10 == 0) {
                        LOGGER.error("max retry reached, sample log Exception caught", exception);
                    }
                } else {
                    LOGGER.error("Exception caught", exception);
                }
                retry++;
                AgentUtils.silenceSleepInMs(retrySleepTime);
            }
        }
    }

    /**
     * flushResendQueue
     *
     * @return thread runner
     */
    private Runnable flushResendQueue() {
        return () -> {
            LOGGER.info("start flush resend queue {}:{}", inlongGroupId, sourcePath);
            while (!shutdown) {
                try {
                    AgentSenderCallback callback = resendQueue.poll(1, TimeUnit.SECONDS);
                    if (callback != null) {
                        sendBatchWithRetryCount(callback.batchMessage, callback.retry + 1);
                    }
                } catch (Exception ex) {
                    LOGGER.error("error caught", ex);
                } catch (Throwable t) {
                    ThreadUtils.threadThrowableHandler(Thread.currentThread(), t);
                } finally {
                    AgentUtils.silenceSleepInMs(batchFlushInterval);
                }
            }
            LOGGER.info("stop flush resend queue {}:{}", inlongGroupId, sourcePath);
        };
    }

    /**
     * put the data into resend queue and will be resent later.
     *
     * @param batchMessageCallBack
     */
    private void putInResendQueue(AgentSenderCallback batchMessageCallBack) {
        try {
            resendQueue.put(batchMessageCallBack);
        } catch (Throwable throwable) {
            LOGGER.error("putInResendQueue e = {}", throwable);
        }
    }

    /**
     * sender callback
     */
    private class AgentSenderCallback implements SendMessageCallback {

        private final int retry;
        private final BatchProxyMessage batchMessage;
        private final int msgCnt;

        AgentSenderCallback(BatchProxyMessage batchMessage, int retry) {
            this.batchMessage = batchMessage;
            this.retry = retry;
            this.msgCnt = batchMessage.getDataList().size();
        }

        @Override
        public void onMessageAck(SendResult result) {
            String groupId = batchMessage.getGroupId();
            String streamId = batchMessage.getStreamId();
            String jobId = batchMessage.getJobId();
            long dataTime = batchMessage.getDataTime();
            if (result != null && result.equals(SendResult.OK)) {
                MemoryManager.getInstance().release(AGENT_GLOBAL_WRITER_PERMIT, (int) batchMessage.getTotalSize());
                AuditUtils.add(AuditUtils.AUDIT_ID_AGENT_SEND_SUCCESS, groupId, streamId, dataTime, msgCnt,
                        batchMessage.getTotalSize());
                getMetricItem(groupId, streamId).pluginSendSuccessCount.addAndGet(msgCnt);
                PositionManager.getInstance()
                        .updateSinkPosition(batchMessage.getJobId(), sourcePath, msgCnt, false);
            } else {
                LOGGER.warn("send groupId {}, streamId {}, jobId {}, dataTime {} fail with times {}, "
                        + "error {}", groupId, streamId, jobId, dataTime, retry, result);
                getMetricItem(groupId, streamId).pluginSendFailCount.addAndGet(msgCnt);
                putInResendQueue(new AgentSenderCallback(batchMessage, retry));
            }
        }

        @Override
        public void onException(Throwable e) {
            getMetricItem(batchMessage.getGroupId(), batchMessage.getStreamId()).pluginSendFailCount.addAndGet(msgCnt);
            LOGGER.error("exception caught", e);
        }
    }

}
