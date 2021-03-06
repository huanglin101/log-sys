package cn.yunyichina.log.service.tracer.kafka.consumer;

import cn.yunyichina.log.common.ContextId;
import cn.yunyichina.log.common.entity.do_.LinkedTraceNode;
import cn.yunyichina.log.service.common.entity.do_.ReverseIndexDO;
import cn.yunyichina.log.service.common.entity.do_.TraceDO;
import cn.yunyichina.log.service.tracer.mapper.CommonMapper;
import cn.yunyichina.log.service.tracer.prop.TracerConsumerProp;
import cn.yunyichina.log.service.tracer.service.KafkaConsumerService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * @Author: Leo
 * @Blog: http://blog.csdn.net/lc0817
 * @CreateTime: 2017/5/21 10:37
 * @Description:
 */
@Component
public class TraceConsumer {

    final Logger logger = LoggerFactory.getLogger(TraceConsumer.class);

    public static final int MIN_BATCH_SIZE = 2;

    private ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
    private KafkaConsumer consumer;
    private FastDateFormat dateFormat = FastDateFormat.getInstance("yyyyMMdd");

    private AtomicBoolean hasStart = new AtomicBoolean(false);

    @Autowired
    TracerConsumerProp tracerConsumerProp;
    @Autowired
    CommonMapper commonMapper;

    @Autowired
    KafkaConsumerService kafkaConsumerService;

    public void createTable() {
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < 7; i++) {
            String curDateStr = dateFormat.format(c.getTime());
            commonMapper.createTraceTable(TableNamePrefix.TRACE.getVal() + curDateStr);
            commonMapper.createReverseIndexTable(TableNamePrefix.REVERSE_INDEX.getVal() + curDateStr);
            c.add(Calendar.DATE, 1);
        }
    }

    public void startConsumer() {
        if (!hasStart.get()) {
            singleThreadExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    consumer = new KafkaConsumer<>(defaultProp());
                    consumer.subscribe(tracerConsumerProp.getTopicList());
                    List<ConsumerRecord<String, LinkedTraceNode>> buffer = new ArrayList<>(MIN_BATCH_SIZE << 1);
                    while (true) {
                        try {
                            ConsumerRecords<String, LinkedTraceNode> records = consumer.poll(100);
                            for (ConsumerRecord<String, LinkedTraceNode> record : records) {
                                buffer.add(record);
                            }
                            if (buffer.size() >= MIN_BATCH_SIZE) {
                                insertIntoDb(buffer);
                                consumer.commitSync();
                                logger.info("========committed size: " + buffer.size() + " ========");
                                buffer.clear();
                            }
                        } catch (Exception e) {
                            buffer.clear();
                            logger.error("kafka提交失败:" + e.getMessage());
                        }
                    }
                }
            });
        }
    }

    public void insertIntoDb(List<ConsumerRecord<String, LinkedTraceNode>> buffer) throws Exception {
        if (CollectionUtils.isNotEmpty(buffer)) {
            for (ConsumerRecord<String, LinkedTraceNode> record : buffer) {
                LinkedTraceNode node = record.value();

                String traceId = node.getTraceId();
                String contextId = node.getContextId();

                TraceDO traceDO = new TraceDO()
                        .setTraceId(traceId)
                        .setContextId(contextId)
                        .setTableName(buildTraceTableName(TableNamePrefix.TRACE, traceId));

                ReverseIndexDO reverseIndexDO = new ReverseIndexDO()
                        .setContextId(contextId)
                        .setTraceId(traceId)
                        .setTableName(buildTraceTableName(TableNamePrefix.REVERSE_INDEX, contextId));

                kafkaConsumerService.insertTraceAndReverseIndex(traceDO, reverseIndexDO);
            }
        }
    }

    @PreDestroy
    private void preDestroy() {
        consumer.close();
    }


    public Properties defaultProp() {
        Properties props = new Properties();
        props.put("bootstrap.servers", tracerConsumerProp.getBootstrapServers());
        props.put("group.id", tracerConsumerProp.getGroupId());
        props.put("enable.auto.commit", "false");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "cn.yunyichina.log.service.tracer.serialization.TraceNodeDeserialization");
        return props;
    }

    private String buildTraceTableName(TableNamePrefix tableNamePrefix, String objectId) {
        return tableNamePrefix.getVal() + dateFormat.format(ContextId.getTimestamp(objectId));
    }

    public enum TableNamePrefix {
        TRACE("trace_"), REVERSE_INDEX("reverse_index_");

        private String val;

        TableNamePrefix(String val) {
            this.val = val;
        }

        public String getVal() {
            return val;
        }
    }
}
