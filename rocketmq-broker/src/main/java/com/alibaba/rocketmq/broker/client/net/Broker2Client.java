/**
 * Copyright (C) 2010-2013 Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.rocketmq.broker.client.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.FileRegion;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.rocketmq.broker.BrokerController;
import com.alibaba.rocketmq.broker.client.ClientChannelInfo;
import com.alibaba.rocketmq.broker.pagecache.OneMessageTransfer;
import com.alibaba.rocketmq.common.MQVersion;
import com.alibaba.rocketmq.common.TopicConfig;
import com.alibaba.rocketmq.common.UtilAll;
import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.rocketmq.common.protocol.MQProtos.MQRequestCode;
import com.alibaba.rocketmq.common.protocol.body.GetConsumerStatusBody;
import com.alibaba.rocketmq.common.protocol.body.ResetOffsetBody;
import com.alibaba.rocketmq.common.protocol.header.CheckTransactionStateRequestHeader;
import com.alibaba.rocketmq.common.protocol.header.GetConsumerStatusRequestHeader;
import com.alibaba.rocketmq.common.protocol.header.NotifyConsumerIdsChangedRequestHeader;
import com.alibaba.rocketmq.common.protocol.header.ResetOffsetRequestHeader;
import com.alibaba.rocketmq.remoting.common.RemotingHelper;
import com.alibaba.rocketmq.remoting.protocol.RemotingCommand;
import com.alibaba.rocketmq.remoting.protocol.RemotingProtos;
import com.alibaba.rocketmq.store.SelectMapedBufferResult;


/**
 * Broker主动调用客户端接口
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 * @since 2013-7-26
 */
public class Broker2Client {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.BrokerLoggerName);
    private final BrokerController brokerController;


    public Broker2Client(BrokerController brokerController) {
        this.brokerController = brokerController;
    }


    /**
     * Broker主动回查Producer事务状态，Oneway
     */
    public void checkProducerTransactionState(//
            final Channel channel,//
            final CheckTransactionStateRequestHeader requestHeader,//
            final SelectMapedBufferResult selectMapedBufferResult//
    ) {
        RemotingCommand request =
                RemotingCommand.createRequestCommand(MQRequestCode.CHECK_TRANSACTION_STATE_VALUE,
                    requestHeader);
        request.markOnewayRPC();

        try {
            FileRegion fileRegion =
                    new OneMessageTransfer(request.encodeHeader(selectMapedBufferResult.getSize()),
                        selectMapedBufferResult);
            channel.writeAndFlush(fileRegion).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    selectMapedBufferResult.release();
                    if (!future.isSuccess()) {
                        log.error("invokeProducer failed,", future.cause());
                    }
                }
            });
        }
        catch (Throwable e) {
            log.error("invokeProducer exception", e);
            selectMapedBufferResult.release();
        }
    }


    /**
     * Broker主动通知Consumer，Id列表发生变化，Oneway
     */
    public void notifyConsumerIdsChanged(//
            final Channel channel,//
            final String consumerGroup//
    ) {
        NotifyConsumerIdsChangedRequestHeader requestHeader = new NotifyConsumerIdsChangedRequestHeader();
        requestHeader.setConsumerGroup(consumerGroup);
        RemotingCommand request =
                RemotingCommand.createRequestCommand(MQRequestCode.NOTIFY_CONSUMER_IDS_CHANGED_VALUE,
                    requestHeader);

        try {
            this.brokerController.getRemotingServer().invokeOneway(channel, request, 1000);
        }
        catch (Exception e) {
            log.error("notifyConsumerIdsChanged exception, " + consumerGroup, e);
        }
    }


    /**
     * Broker 主动通知 Consumer，offset 需要进行重置列表发生变化
     */
    public RemotingCommand resetOffset(String topic, String group, long timeStamp, boolean isForce) {
        final RemotingCommand response = RemotingCommand.createResponseCommand(null);

        TopicConfig topicConfig = this.brokerController.getTopicConfigManager().selectTopicConfig(topic);
        if (null == topicConfig) {
            log.error("[reset-offset] reset offset failed, no topic in this broker. topic={}", topic);
            response.setCode(RemotingProtos.ResponseCode.SYSTEM_ERROR_VALUE);
            response.setRemark("[reset-offset] reset offset failed, no topic in this broker. topic=" + topic);
            return response;
        }

        Map<MessageQueue, Long> offsetTable = new HashMap<MessageQueue, Long>();
        for (int i = 0; i < topicConfig.getWriteQueueNums(); i++) {
            MessageQueue mq = new MessageQueue();
            mq.setBrokerName(this.brokerController.getBrokerConfig().getBrokerName());
            mq.setTopic(topic);
            mq.setQueueId(i);

            long consumerOffset =
                    this.brokerController.getConsumerOffsetManager().queryOffset(group, topic, i);
            long timeStampOffset =
                    this.brokerController.getMessageStore().getOffsetInQueueByTime(topic, i, timeStamp);
            if (isForce || timeStampOffset < consumerOffset) {
                offsetTable.put(mq, timeStampOffset);
            }
            else {
                offsetTable.put(mq, consumerOffset);
            }
        }

        ResetOffsetRequestHeader requestHeader = new ResetOffsetRequestHeader();
        requestHeader.setTopic(topic);
        requestHeader.setGroup(group);
        requestHeader.setTimestamp(timeStamp);
        RemotingCommand request =
                RemotingCommand.createRequestCommand(MQRequestCode.RESET_CONSUMER_CLIENT_OFFSET_VALUE,
                    requestHeader);
        ResetOffsetBody body = new ResetOffsetBody();
        body.setOffsetTable(offsetTable);
        request.setBody(body.encode());

        ConcurrentHashMap<Channel, ClientChannelInfo> channelInfoTable =
                this.brokerController.getConsumerManager().getConsumerGroupInfo(group).getChannelInfoTable();
        for (Channel channel : channelInfoTable.keySet()) {
            int version = channelInfoTable.get(channel).getVersion();
            if (version >= MQVersion.Version.V3_0_7_SNAPSHOT.ordinal()) {
                try {
                    this.brokerController.getRemotingServer().invokeOneway(channel, request, 5000);
                    log.info("[reset-offset] reset offset success. topic={}, group={}, clientId={}",
                        new Object[] { topic, group, channelInfoTable.get(channel).getClientId() });
                }
                catch (Exception e) {
                    log.error("[reset-offset] reset offset exception. topic={}, group={}",
                        new Object[] { topic, group }, e);
                }
            }
            else {
                // 如果有一个客户端是不支持该功能的，则直接返回错误，需要应用方升级。
                response.setCode(RemotingProtos.ResponseCode.SYSTEM_ERROR_VALUE);
                response.setRemark("the client does not support this feature. version=" + version);
                log.warn("[reset-offset] the client does not support this feature. version={}",
                    RemotingHelper.parseChannelRemoteAddr(channel), version);
                return response;
            }
        }

        response.setCode(RemotingProtos.ResponseCode.SUCCESS_VALUE);
        ResetOffsetBody resBody = new ResetOffsetBody();
        resBody.setOffsetTable(offsetTable);
        response.setBody(resBody.encode());
        return response;
    }


    /**
     * Broker主动获取Consumer端的消息情况
     */
    public RemotingCommand getConsumeStatus(String topic, String group, String originClientId) {
        final RemotingCommand result = RemotingCommand.createResponseCommand(null);

        GetConsumerStatusRequestHeader requestHeader = new GetConsumerStatusRequestHeader();
        requestHeader.setTopic(topic);
        requestHeader.setGroup(group);
        RemotingCommand request =
                RemotingCommand.createRequestCommand(MQRequestCode.GET_CONSUMER_STATUS_FROM_CLIENT_VALUE,
                    requestHeader);

        Map<String, Map<MessageQueue, Long>> consumerStatusTable =
                new HashMap<String, Map<MessageQueue, Long>>();
        ConcurrentHashMap<Channel, ClientChannelInfo> channelInfoTable =
                this.brokerController.getConsumerManager().getConsumerGroupInfo(group).getChannelInfoTable();
        for (Channel channel : channelInfoTable.keySet()) {
            int version = channelInfoTable.get(channel).getVersion();
            String clientId = channelInfoTable.get(channel).getClientId();
            if (version < MQVersion.Version.V3_0_7_SNAPSHOT.ordinal()) {
                // 如果有一个客户端是不支持该功能的，则直接返回错误，需要应用方升级。
                result.setCode(RemotingProtos.ResponseCode.SYSTEM_ERROR_VALUE);
                result.setRemark("the client does not support this feature. version=" + version);
                log.warn("[reset-offset] the client does not support this feature. version={}",
                    RemotingHelper.parseChannelRemoteAddr(channel), version);
                return result;
            }
            else if (UtilAll.isBlank(originClientId) || originClientId.equals(clientId)) {
                // 不指定 originClientId 则对所有的 client 进行处理；若指定 originClientId 则只对当前
                // originClientId 进行处理
                try {
                    RemotingCommand response =
                            this.brokerController.getRemotingServer().invokeSync(channel, request, 5000);
                    assert response != null;
                    switch (response.getCode()) {
                    case RemotingProtos.ResponseCode.SUCCESS_VALUE: {
                        if (response.getBody() != null) {
                            GetConsumerStatusBody body =
                                    GetConsumerStatusBody.decode(response.getBody(),
                                        GetConsumerStatusBody.class);

                            consumerStatusTable.put(clientId, body.getMessageQueueTable());
                            log.info("get consumer status success. topic={}, group={}, channelRemoteAddr={}",
                                new Object[] { topic, group, clientId });
                        }
                    }
                    default:
                        break;
                    }
                }
                catch (Exception e) {
                    log.error("get consumer status exception. topic={}, group={}, offset={}",
                        new Object[] { topic, group }, e);
                }

                // 若指定 originClientId 相应的 client 处理完成，则退出循环
                if (!UtilAll.isBlank(originClientId) && originClientId.equals(clientId)) {
                    break;
                }
            }
        }

        result.setCode(RemotingProtos.ResponseCode.SUCCESS_VALUE);
        GetConsumerStatusBody resBody = new GetConsumerStatusBody();
        resBody.setConsumerTable(consumerStatusTable);
        result.setBody(resBody.encode());
        return result;
    }
}
