package io.metersphere.streaming.engine.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.metersphere.streaming.base.domain.LoadTestReportResultPart;
import io.metersphere.streaming.base.domain.LoadTestReportResultRealtime;
import io.metersphere.streaming.commons.utils.LogUtil;
import io.metersphere.streaming.commons.utils.ReportTasks;
import io.metersphere.streaming.model.ReportResult;
import io.metersphere.streaming.service.TestResultSaveService;
import io.metersphere.streaming.service.TestResultService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ReportConsumer {
    public static final String CONSUME_ID = "report-data";
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private TestResultSaveService testResultSaveService;
    @Resource
    private TestResultService testResultService;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(20, 20,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>());

    private final ThreadPoolExecutor saveExecutor = new ThreadPoolExecutor(30, 30,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>());

    @KafkaListener(id = CONSUME_ID, topics = "${kafka.report.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<?, String> record) throws Exception {
        List<ReportResult> content = objectMapper.readValue(record.value(), new TypeReference<List<ReportResult>>() {
        });
        if (CollectionUtils.isEmpty(content)) {
            return;
        }
        ReportResult reportResult = content.get(0);
        String reportId = reportResult.getReportId();
        int resourceIndex = reportResult.getResourceIndex();
        Integer sort = reportResult.getSort();
        if (BooleanUtils.toBoolean(reportResult.getCompleted())) {
            // 最后汇总所有的信息
            Runnable task = getCompletedTask(content, reportId, resourceIndex);
            // 清理未处理的任务
            ReportTasks.clearUnExecuteTasks(reportId);
            executor.submit(task);
            return;
        }
        String key = reportId + "_" + resourceIndex;
        LogUtil.info("处理报告: reportId_resourceIndex: {}", key);
        // 保存每个报告的任务队列
        Runnable task = getRealtimeTask(content, reportId, resourceIndex, sort);
        Future<?> future = executor.submit(task);
        ReportTasks.addTask(reportId, future);
        LogUtil.info("当前处理中的任务: {}, 待处理队列: {}", executor.getActiveCount(), executor.getQueue().size());
    }

    private Runnable getRealtimeTask(List<ReportResult> content, String reportId, int resourceIndex, int sort) {
        return () -> {
            boolean b = testResultSaveService.checkReportStatus(reportId);
            if (!b) {
                // 报告不存在
                return;
            }

            long start = System.currentTimeMillis();
            List<String> reportKeys = new CopyOnWriteArrayList<>();
            CountDownLatch countDownLatch = new CountDownLatch(content.size());
            content.forEach(result -> saveExecutor.submit(() -> {
                String reportKey = result.getReportKey();
                try {
                    long summaryStart = System.currentTimeMillis();
                    reportKeys.add(reportKey);

                    LoadTestReportResultRealtime testResult = new LoadTestReportResultRealtime();
                    testResult.setReportId(result.getReportId());
                    testResult.setReportKey(reportKey);
                    testResult.setResourceIndex(result.getResourceIndex());
                    testResult.setSort(sort);
                    testResult.setReportValue(objectMapper.writeValueAsString(result.getContent()));
                    testResultSaveService.saveResultRealtime(testResult);
                    LogUtil.debug("报告: " + reportId + ", 保存" + reportKey + "耗时: " + (System.currentTimeMillis() - summaryStart));
                } catch (Exception e) {
                    LogUtil.error("接收结果处理异常: " + reportId + "reportKey: " + reportKey, e);
                } finally {
                    countDownLatch.countDown();
                }
            }));
            try {
                countDownLatch.await();
                long summaryStart = System.currentTimeMillis();
                LogUtil.debug("报告: " + reportId + ", 保存耗时: " + (summaryStart - start));
                // 汇总信息
                testResultSaveService.saveAllSummaryRealtime(reportId, resourceIndex, sort, reportKeys);
                LogUtil.debug("报告: " + reportId + ", 汇总耗时: " + (System.currentTimeMillis() - summaryStart));
            } catch (InterruptedException e) {
                LogUtil.error(e);
            }
        };
    }

    private Runnable getCompletedTask(List<ReportResult> content, String reportId, Integer resourceIndex) {
        return () -> {
            boolean b = testResultSaveService.checkReportStatus(reportId);
            if (!b) {
                // 报告不存在
                return;
            }

            long start = System.currentTimeMillis();
            List<String> reportKeys = new CopyOnWriteArrayList<>();
            CountDownLatch countDownLatch = new CountDownLatch(content.size());
            content.forEach(result -> saveExecutor.submit(() -> {
                String reportKey = result.getReportKey();
                try {
                    long summaryStart = System.currentTimeMillis();
                    reportKeys.add(reportKey);

                    LoadTestReportResultPart testResult = new LoadTestReportResultPart();
                    testResult.setReportId(result.getReportId());
                    testResult.setReportKey(reportKey);
                    testResult.setResourceIndex(result.getResourceIndex());
                    testResult.setReportValue(objectMapper.writeValueAsString(result.getContent()));
                    testResultSaveService.saveResultPart(testResult);
                    LogUtil.debug("报告: " + reportId + ", 保存" + reportKey + "耗时: " + (System.currentTimeMillis() - summaryStart));
                } catch (Exception e) {
                    LogUtil.error("接收结果处理异常: " + reportId + "reportKey: " + reportKey, e);
                } finally {
                    countDownLatch.countDown();
                }
            }));
            testResultSaveService.saveReportPartReportingStatus(reportId, resourceIndex);
            try {
                countDownLatch.await();
                long summaryStart = System.currentTimeMillis();
                LogUtil.debug("报告: " + reportId + ", 保存耗时: " + (summaryStart - start));
                // 汇总信息
                testResultSaveService.forceSaveAllSummary(reportId, reportKeys);
                testResultService.completeReport(reportId);
                LogUtil.debug("报告: " + reportId + ", 汇总耗时: " + (System.currentTimeMillis() - summaryStart));
            } catch (InterruptedException e) {
                LogUtil.error(e);
            }
        };
    }

}
