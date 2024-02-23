package io.metersphere.streaming.engine.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.metersphere.streaming.base.domain.LoadTestReport;
import io.metersphere.streaming.commons.utils.LogUtil;
import io.metersphere.streaming.commons.utils.ReportTasks;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

@Service
public class LoadTestConsumer {

    public static final String CONSUME_ID = "load-test";
    @Resource
    private ObjectMapper objectMapper;

    @KafkaListener(id = CONSUME_ID, topics = "${kafka.test.topic}", groupId = CONSUME_ID + "_" + "${random.uuid}")
    public void consume(ConsumerRecord<?, String> record) throws Exception {
        try {
            LoadTestReport loadTestReport = objectMapper.readValue(record.value(), LoadTestReport.class);
            ReportTasks.clearUnExecuteTasks(loadTestReport.getId());
        } catch (Exception e) {
            LogUtil.error("测试结束删除本地待处理队列失败: ", e);
        }
    }
}
