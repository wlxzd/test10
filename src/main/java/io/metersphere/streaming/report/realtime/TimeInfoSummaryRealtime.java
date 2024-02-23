package io.metersphere.streaming.report.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.metersphere.streaming.base.domain.LoadTestReportResultPart;
import io.metersphere.streaming.base.domain.LoadTestReportResultPartKey;
import io.metersphere.streaming.commons.constants.ReportKeys;
import io.metersphere.streaming.commons.utils.LogUtil;
import io.metersphere.streaming.report.base.ReportTimeInfo;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component("timeInfoSummaryRealtime")
public class TimeInfoSummaryRealtime extends AbstractSummaryRealtime<ReportTimeInfo> {

    @Override
    public String getReportKey() {
        return ReportKeys.TimeInfo.name();
    }

    @Override
    public ReportTimeInfo execute(String reportId, int resourceIndex, int sort) {
        LoadTestReportResultPartKey key = new LoadTestReportResultPartKey();
        key.setReportId(reportId);
        key.setReportKey(getReportKey());
        key.setResourceIndex(resourceIndex);
        LoadTestReportResultPart loadTestReportResultPart = loadTestReportResultPartMapper.selectByPrimaryKey(key);

        AtomicReference<ReportTimeInfo> result = new AtomicReference<>();
        if (loadTestReportResultPart != null) {
            try {
                result.set(objectMapper.readValue(loadTestReportResultPart.getReportValue(), ReportTimeInfo.class));
            } catch (JsonProcessingException e) {
                LogUtil.error(e);
            }
        }
        SummaryRealtimeAction action = (resultPart) -> {
            try {
                String reportValue = resultPart.getReportValue();
                ReportTimeInfo reportContent = objectMapper.readValue(reportValue, ReportTimeInfo.class);

                // 第一遍不需要汇总
                if (result.get() == null) {
                    result.set(reportContent);
                    return;
                }
                // 第二遍以后
                ReportTimeInfo reportTimeInfo = result.get();
                if (reportContent.getStartTime() < reportTimeInfo.getStartTime()) {
                    reportTimeInfo.setStartTime(reportContent.getStartTime());
                }

                if (reportContent.getEndTime() > reportTimeInfo.getEndTime()) {
                    reportTimeInfo.setEndTime(reportContent.getEndTime());
                }
                long seconds = Duration.between(Instant.ofEpochSecond(reportTimeInfo.getStartTime() / 1000), Instant.ofEpochSecond((reportTimeInfo.getEndTime() / 1000))).getSeconds();

                reportTimeInfo.setDuration(seconds);
                result.set(reportTimeInfo);

            } catch (Exception e) {
                LogUtil.error("TimeInfoSummaryRealtime: ", e);
            }
        };
        selectRealtimeAndDoSummary(reportId, resourceIndex, sort, getReportKey(), action);

        ReportTimeInfo timeInfo = result.get();

        return timeInfo;
    }


}
