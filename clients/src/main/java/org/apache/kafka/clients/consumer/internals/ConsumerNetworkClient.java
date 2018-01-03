/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.ClientRequest;
import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.KafkaClient;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.RequestCompletionHandler;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.RequestSend;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Higher level consumer access to the network layer with basic support for futures and
 * task scheduling. This class is not thread-safe, except for wakeup().
 */
public class ConsumerNetworkClient implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(ConsumerNetworkClient.class);

    /**
     * NetworkClient对象
     */
    private final KafkaClient client;
    /**
     * 由调用KafkaConsumer对象的消费者线程之外的其他线程设置，表示要中断KafkaConsumer线程
     */
    private final AtomicBoolean wakeup = new AtomicBoolean(false);
    /**
     * 定时任务队列，DelayedTaskQueue是Kafka提供的定时任务队列的实现，这个定时任务是心跳任务。
     */
    private final DelayedTaskQueue delayedTasks = new DelayedTaskQueue();
    /**
     * 缓冲队列，key是Node节点，value是发往此Node的ClientRequest集合
     */
    private final Map<Node, List<ClientRequest>> unsent = new HashMap<>();
    private final Metadata metadata;
    private final Time time;
    private final long retryBackoffMs;
    /**
     * ClientRequest在unsent中的超时时长
     */
    private final long unsentExpiryMs;

    // this count is only accessed from the consumer's main thread
    /**
     * KafkaConsumer是否正在执行不可中断的方法。每进入一个不可中断的方法时，则增加一，
     * 退出不可中断的方法时，则减少一。wakeupDisabledCount只会被KafkaConsumer线程修改，
     * 其他线程不可修改。
     */
    private int wakeupDisabledCount = 0;


    public ConsumerNetworkClient(KafkaClient client,
                                 Metadata metadata,
                                 Time time,
                                 long retryBackoffMs,
                                 long requestTimeoutMs) {
        this.client = client;
        this.metadata = metadata;
        this.time = time;
        this.retryBackoffMs = retryBackoffMs;
        this.unsentExpiryMs = requestTimeoutMs;
    }

    /**
     * Schedule a new task to be executed at the given time. This is "best-effort" scheduling and
     * should only be used for coarse synchronization.
     * @param task The task to be scheduled
     * @param at The time it should run
     */
    public void schedule(DelayedTask task, long at) {
        delayedTasks.add(task, at);
    }

    /**
     * Unschedule a task. This will remove all instances of the task from the task queue.
     * This is a no-op if the task is not scheduled.
     * @param task The task to be unscheduled.
     */
    public void unschedule(DelayedTask task) {
        delayedTasks.remove(task);
    }

    /**
     * Send a new request. Note that the request is not actually transmitted on the
     * network until one of the {@link #poll(long)} variants is invoked. At this
     * point the request will either be transmitted successfully or will fail.
     * Use the returned future to obtain the result of the send. Note that there is no
     * need to check for disconnects explicitly on the {@link ClientResponse} object;
     * instead, the future will be failed with a {@link DisconnectException}.
     * @param node The destination of the request
     * @param api The Kafka API call
     * @param request The request payload
     * @return A future which indicates the result of the send.
     */
    public RequestFuture<ClientResponse> send(Node node,
                                              ApiKeys api,
                                              AbstractRequest request) {
        long now = time.milliseconds();
        RequestFutureCompletionHandler future = new RequestFutureCompletionHandler();
        RequestHeader header = client.nextRequestHeader(api);
        RequestSend send = new RequestSend(node.idString(), header, request.toStruct());
        //创建ClientRequest对象，并保存到unsent集合中
        put(node, new ClientRequest(now, true, send, future));
        return future;
    }

    private void put(Node node, ClientRequest request) {
        List<ClientRequest> nodeUnsent = unsent.get(node);
        if (nodeUnsent == null) {
            nodeUnsent = new ArrayList<>();
            unsent.put(node, nodeUnsent);
        }
        nodeUnsent.add(request);
    }

    public Node leastLoadedNode() {
        return client.leastLoadedNode(time.milliseconds());
    }

    /**
     * Block until the metadata has been refreshed.
     */
    public void awaitMetadataUpdate() {
        int version = this.metadata.requestUpdate();
        do {
            poll(Long.MAX_VALUE);
        } while (this.metadata.version() == version);
    }

    /**
     * Ensure our metadata is fresh (if an update is expected, this will block
     * until it has completed).
     */
    public void ensureFreshMetadata() {
        if (this.metadata.updateRequested() || this.metadata.timeToNextUpdate(time.milliseconds()) == 0)
            awaitMetadataUpdate();
    }

    /**
     * Wakeup an active poll. This will cause the polling thread to throw an exception either
     * on the current poll if one is active, or the next poll.
     */
    public void wakeup() {
        this.wakeup.set(true);
        this.client.wakeup();
    }

    /**
     * Block indefinitely until the given request future has finished.
     * @param future The request future to await.
     * @throws WakeupException if {@link #wakeup()} is called from another thread
     */
    public void poll(RequestFuture<?> future) {
        while (!future.isDone())
            poll(Long.MAX_VALUE);
    }

    /**
     * Block until the provided request future request has finished or the timeout has expired.
     * @param future The request future to wait for
     * @param timeout The maximum duration (in ms) to wait for the request
     * @return true if the future is done, false otherwise
     * @throws WakeupException if {@link #wakeup()} is called from another thread
     */
    public boolean poll(RequestFuture<?> future, long timeout) {
        long begin = time.milliseconds();
        long remaining = timeout;
        long now = begin;
        do {
            poll(remaining, now, true);
            now = time.milliseconds();
            long elapsed = now - begin;
            remaining = timeout - elapsed;
        } while (!future.isDone() && remaining > 0);
        return future.isDone();
    }

    /**
     * Poll for any network IO.
     * @param timeout The maximum time to wait for an IO event.
     * @throws WakeupException if {@link #wakeup()} is called from another thread
     */
    public void poll(long timeout) {
        poll(timeout, time.milliseconds(), true);
    }

    /**
     * Poll for any network IO.
     * @param timeout timeout in milliseconds
     * @param now current time in milliseconds
     */
    public void poll(long timeout, long now) {
        poll(timeout, now, true);
    }

    /**
     * 不可中断的poll()方法
     * Poll for network IO and return immediately. This will not trigger wakeups,
     * nor will it execute any delayed tasks.
     */
    public void pollNoWakeup() {
        disableWakeups();
        try {
            poll(0, time.milliseconds(), false);
        } finally {
            enableWakeups();
        }
    }

    /**
     *
     * @param timeout 表示执行poll()方法的最大阻塞时间（单位是ms），如果为0，则表示不阻塞。
     * @param now 表示当前时间戳
     * @param executeDelayedTasks 表示是否执行delayedTasks队列中的定时任务。
     */
    private void poll(long timeout, long now, boolean executeDelayedTasks) {
        // send all the requests we can send now
        //步骤1：检测发送条件，将请求放入KafkaChannel.send字段，待发送...
        trySend(now);

        // ensure we don't poll any longer than the deadline for
        // the next scheduled task
        //计算超时时间
        timeout = Math.min(timeout, delayedTasks.nextTimeout(now));
        //步骤3、4:调用NetworkClient.poll()方法，并检测是否有中断请求
        clientPoll(timeout, now);
        //重置当前时间
        now = time.milliseconds();

        // handle any disconnects by failing the active requests. note that disconnects must
        // be checked immediately following poll since any subsequent call to client.ready()
        // will reset the disconnect status
        //步骤5：根据连接状态，处理unsent中的请求
        checkDisconnects(now);

        // execute scheduled tasks
        //步骤6：处理定时任务
        if (executeDelayedTasks)
            delayedTasks.poll(now);

        // try again to send requests since buffer space may have been
        // cleared or a connect finished in the poll
        //步骤7：检测发送条件，重新设置KafkaChannel.send字段，并超时断开重连
        trySend(now);

        // fail requests that couldn't be sent if they have expired
        //步骤8：处理unsent中的超时任务
        failExpiredRequests(now);
    }

    /**
     * Execute delayed tasks now.
     * @param now current time in milliseconds
     * @throws WakeupException if a wakeup has been requested
     */
    public void executeDelayedTasks(long now) {
        delayedTasks.poll(now);
        maybeTriggerWakeup();
    }

    /**
     * Block until all pending requests from the given node have finished.
     * @param node The node to await requests from
     */
    public void awaitPendingRequests(Node node) {
        while (pendingRequestCount(node) > 0)
            poll(retryBackoffMs);
    }

    /**
     * Get the count of pending requests to the given node. This includes both request that
     * have been transmitted (i.e. in-flight requests) and those which are awaiting transmission.
     * @param node The node in question
     * @return The number of pending requests
     */
    public int pendingRequestCount(Node node) {
        List<ClientRequest> pending = unsent.get(node);
        int unsentCount = pending == null ? 0 : pending.size();
        return unsentCount + client.inFlightRequestCount(node.idString());
    }

    /**
     * Get the total count of pending requests from all nodes. This includes both requests that
     * have been transmitted (i.e. in-flight requests) and those which are awaiting transmission.
     * @return The total count of pending requests
     */
    public int pendingRequestCount() {
        int total = 0;
        for (List<ClientRequest> requests: unsent.values())
            total += requests.size();
        return total + client.inFlightRequestCount();
    }

    private void checkDisconnects(long now) {
        // any disconnects affecting requests that have already been transmitted will be handled
        // by NetworkClient, so we just need to check whether connections for any of the unsent
        // requests have been disconnected; if they have, then we complete the corresponding future
        // and set the disconnect flag in the ClientResponse
        Iterator<Map.Entry<Node, List<ClientRequest>>> iterator = unsent.entrySet().iterator();
        //变量unset集合中的每个Node
        while (iterator.hasNext()) {
            Map.Entry<Node, List<ClientRequest>> requestEntry = iterator.next();
            Node node = requestEntry.getKey();
            //检测消费者与每个Node之间的连接状态
            if (client.connectionFailed(node)) {
                // Remove entry before invoking request callback to avoid callbacks handling
                // coordinator failures traversing the unsent list again.
                //从unset集合中删除此Node对应的全部ClientRequest
                iterator.remove();
                for (ClientRequest request : requestEntry.getValue()) {
                    RequestFutureCompletionHandler handler =
                            (RequestFutureCompletionHandler) request.callback();
                    //调用ClientRequest的回调函数
                    handler.onComplete(new ClientResponse(request, now, true, null));
                }
            }
        }
    }

    private void failExpiredRequests(long now) {
        // clear all expired unsent requests and fail their corresponding futures
        Iterator<Map.Entry<Node, List<ClientRequest>>> iterator = unsent.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Node, List<ClientRequest>> requestEntry = iterator.next();
            Iterator<ClientRequest> requestIterator = requestEntry.getValue().iterator();
            while (requestIterator.hasNext()) {
                ClientRequest request = requestIterator.next();
                //检查是否超时
                if (request.createdTimeMs() < now - unsentExpiryMs) {
                    RequestFutureCompletionHandler handler =
                            (RequestFutureCompletionHandler) request.callback();
                    //调用回调函数
                    handler.raise(new TimeoutException("Failed to send request after " + unsentExpiryMs + " ms."));
                    //删除ClientRequest
                    requestIterator.remove();
                } else
                    break;
            }
            //队列已经为空，则从unsent集合中删除
            if (requestEntry.getValue().isEmpty())
                iterator.remove();
        }
    }

    protected void failUnsentRequests(Node node, RuntimeException e) {
        // clear unsent requests to node and fail their corresponding futures
        List<ClientRequest> unsentRequests = unsent.remove(node);
        if (unsentRequests != null) {
            for (ClientRequest request : unsentRequests) {
                RequestFutureCompletionHandler handler = (RequestFutureCompletionHandler) request.callback();
                handler.raise(e);
            }
        }
    }

    private boolean trySend(long now) {
        // send any requests that can be sent now
        boolean requestsSent = false;
        //遍历unsent集合
        for (Map.Entry<Node, List<ClientRequest>> requestEntry: unsent.entrySet()) {
            Node node = requestEntry.getKey();
            Iterator<ClientRequest> iterator = requestEntry.getValue().iterator();
            while (iterator.hasNext()) {
                ClientRequest request = iterator.next();
                //调用NetworkClient.ready()检测是否可以发送请求
                if (client.ready(node, now)) {
                    //调用NetworkClient.send()方法，等待发送请求
                    client.send(request, now);
                    //从unsent集合中删除此请求
                    iterator.remove();
                    requestsSent = true;
                }
            }
        }
        return requestsSent;
    }

    private void clientPoll(long timeout, long now) {
        client.poll(timeout, now);
        maybeTriggerWakeup();
    }

    private void maybeTriggerWakeup() {
        //通过wakeupDisabledCount检测是否在执行不可中断的方法，通过wakeup检测是否有中断请求
        if (wakeupDisabledCount == 0 && wakeup.get()) {
            //重置中断标志
            wakeup.set(false);
            throw new WakeupException();
        }
    }

    public void disableWakeups() {
        wakeupDisabledCount++;
    }

    public void enableWakeups() {
        if (wakeupDisabledCount <= 0)
            throw new IllegalStateException("Cannot enable wakeups since they were never disabled");

        wakeupDisabledCount--;

        // re-wakeup the client if the flag was set since previous wake-up call
        // could be cleared by poll(0) while wakeups were disabled
        if (wakeupDisabledCount == 0 && wakeup.get())
            this.client.wakeup();
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    /**
     * Find whether a previous connection has failed. Note that the failure state will persist until either
     * {@link #tryConnect(Node)} or {@link #send(Node, ApiKeys, AbstractRequest)} has been called.
     * @param node Node to connect to if possible
     */
    public boolean connectionFailed(Node node) {
        return client.connectionFailed(node);
    }

    /**
     * Initiate a connection if currently possible. This is only really useful for resetting the failed
     * status of a socket. If there is an actual request to send, then {@link #send(Node, ApiKeys, AbstractRequest)}
     * should be used.
     * @param node The node to connect to
     */
    public void tryConnect(Node node) {
        client.ready(node, time.milliseconds());
    }

    public static class RequestFutureCompletionHandler
            extends RequestFuture<ClientResponse>
            implements RequestCompletionHandler {

        @Override
        public void onComplete(ClientResponse response) {
            //因连接故障而产生的ClientResponse对象
            if (response.wasDisconnected()) {
                ClientRequest request = response.request();
                RequestSend send = request.request();
                ApiKeys api = ApiKeys.forId(send.header().apiKey());
                int correlation = send.header().correlationId();
                log.debug("Cancelled {} request {} with correlation id {} due to node {} being disconnected",
                        api, request, correlation, send.destination());
                //调用继承自父类RequestFuture的raise()方法
                raise(DisconnectException.INSTANCE);
            } else {
                //调用继承自父类的RequestFuture的complete()方法
                complete(response);
            }
        }
    }
}
