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
package org.apache.kafka.clients.producer.internals;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A batch of records that is or will be sent.
 * 
 * This class is not thread safe and external synchronization must be used when modifying it
 */
public final class RecordBatch {

    private static final Logger log = LoggerFactory.getLogger(RecordBatch.class);

    public int recordCount = 0; //记录了保存的Record的个数
    public int maxRecordSize = 0; //最大的Record的字节数
    public volatile int attempts = 0; //尝试发送当前RecordBatch的次数
    public final long createdMs;
    public long drainedMs;
    public long lastAttemptMs; //最后一次尝试发送的时间戳
    public final MemoryRecords records; //指定用来存储数据的MemoryRecords对象
    public final TopicPartition topicPartition; //当前RecordBatch中缓存的消息都会发送给此TopicPartition
    public final ProduceRequestResult produceFuture; //标识RecordBatch状态的Future对象
    public long lastAppendTime; //最后一次向RecordBatch追加消息的时间戳
    private final List<Thunk> thunks; // Thunk对象集合 消息的回调对象队列
    private long offsetCounter = 0L; // 用来记录某消息在RecordBatch中的偏移量。
    private boolean retry; //是否正在重试。

    public RecordBatch(TopicPartition tp, MemoryRecords records, long now) {
        this.createdMs = now;
        this.lastAttemptMs = now;
        this.records = records;
        this.topicPartition = tp;
        this.produceFuture = new ProduceRequestResult();
        this.thunks = new ArrayList<Thunk>();
        this.lastAppendTime = createdMs;
        this.retry = false;
    }

    /**
     * Append the record to the current record set and return the relative offset within that record set
     * 
     * @return The RecordSend corresponding to this record or null if there isn't sufficient room.
     */
    public FutureRecordMetadata tryAppend(long timestamp, byte[] key, byte[] value, Callback callback, long now) {
        //估算的剩余空间不足，不是准确值
        if (!this.records.hasRoomFor(key, value)) {
            return null;
        } else {
            //向MemoryRecords中添加数据，注意，offsetCounter是在RecordBatch中的偏移量
            long checksum = this.records.append(offsetCounter++, timestamp, key, value);
            this.maxRecordSize = Math.max(this.maxRecordSize, Record.recordSize(key, value));
            this.lastAppendTime = now;
            //创建FutureMetadata对象
            FutureRecordMetadata future = new FutureRecordMetadata(this.produceFuture, this.recordCount,
                                                                   timestamp, checksum,
                                                                   key == null ? -1 : key.length,
                                                                   value == null ? -1 : value.length);
            //将用户自定义Callback 和 FutureRecordMetadata 封装成 Tunk，保存到thunks集合中
            if (callback != null)
                thunks.add(new Thunk(callback, future));
            this.recordCount++; //更新recordCount
            return future;
        }
    }

    /**
     * Complete the request
     * 
     * @param baseOffset The base offset of the messages assigned by the server
     * @param timestamp The timestamp returned by the broker.
     * @param exception The exception that occurred (or null if the request was successful)
     */
    public void done(long baseOffset, long timestamp, RuntimeException exception) {
        log.trace("Produced messages to topic-partition {} with base offset offset {} and error: {}.",
                  topicPartition,
                  baseOffset,
                  exception);
        // execute callbacks
        for (int i = 0; i < this.thunks.size(); i++) {
            try {
                Thunk thunk = this.thunks.get(i);
                if (exception == null) { //正常处理完成
                    // 将服务返回的信息 （offset 和 timestamp）和消息的其他信息封装成 RecordMetadata
                    // If the timestamp returned by server is NoTimestamp, that means CreateTime is used. Otherwise LogAppendTime is used.
                    RecordMetadata metadata = new RecordMetadata(this.topicPartition,  baseOffset, thunk.future.relativeOffset(),
                                                                 timestamp == Record.NO_TIMESTAMP ? thunk.future.timestamp() : timestamp,
                                                                 thunk.future.checksum(),
                                                                 thunk.future.serializedKeySize(),
                                                                 thunk.future.serializedValueSize());
                    thunk.callback.onCompletion(metadata, null);
                } else {
                    thunk.callback.onCompletion(null, exception);
                }
            } catch (Exception e) {
                log.error("Error executing user-provided callback on message for topic-partition {}:", topicPartition, e);
            }
        }
        //标识整个RecordBatch都已经处理完成
        this.produceFuture.done(topicPartition, baseOffset, exception);
    }

    /**
     * A callback and the associated FutureRecordMetadata argument to pass to it.
     */
    final private static class Thunk {
        final Callback callback; //对象消息的Callback对象
        final FutureRecordMetadata future;

        public Thunk(Callback callback, FutureRecordMetadata future) {
            this.callback = callback;
            this.future = future;
        }
    }

    @Override
    public String toString() {
        return "RecordBatch(topicPartition=" + topicPartition + ", recordCount=" + recordCount + ")";
    }

    /**
     * A batch whose metadata is not available should be expired if one of the following is true:
     * <ol>
     *     <li> the batch is not in retry AND request timeout has elapsed after it is ready (full or linger.ms has reached).
     *     <li> the batch is in retry AND request timeout has elapsed after the backoff period ended.
     * </ol>
     */
    public boolean maybeExpire(int requestTimeoutMs, long retryBackoffMs, long now, long lingerMs, boolean isFull) {
        boolean expire = false;

        if (!this.inRetry() && isFull && requestTimeoutMs < (now - this.lastAppendTime))
            expire = true;
        else if (!this.inRetry() && requestTimeoutMs < (now - (this.createdMs + lingerMs)))
            expire = true;
        else if (this.inRetry() && requestTimeoutMs < (now - (this.lastAttemptMs + retryBackoffMs)))
            expire = true;

        if (expire) {
            this.records.close();
            //超时
            this.done(-1L, Record.NO_TIMESTAMP, new TimeoutException("Batch containing " + recordCount + " record(s) expired due to timeout while requesting metadata from brokers for " + topicPartition));
        }

        return expire;
    }

    /**
     * Returns if the batch is been retried for sending to kafka
     */
    public boolean inRetry() {
        return this.retry;
    }

    /**
     * Set retry to true if the batch is being retried (for send)
     */
    public void setRetry() {
        this.retry = true;
    }
}
