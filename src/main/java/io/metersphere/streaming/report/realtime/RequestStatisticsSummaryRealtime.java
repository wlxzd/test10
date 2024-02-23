package io.metersphere.streaming.report.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.metersphere.streaming.base.domain.LoadTestReportResultPart;
import io.metersphere.streaming.base.domain.LoadTestReportResultPartKey;
import io.metersphere.streaming.base.domain.LoadTestReportResultRealtime;
import io.metersphere.streaming.base.domain.LoadTestReportResultRealtimeKey;
import io.metersphere.streaming.commons.constants.ReportKeys;
import io.metersphere.streaming.commons.utils.CommonBeanFactory;
import io.metersphere.streaming.commons.utils.LogUtil;
import io.metersphere.streaming.report.base.ReportTimeInfo;
import io.metersphere.streaming.report.base.Statistics;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component("requestStatisticsSummaryRealtime")
public class RequestStatisticsSummaryRealtime extends AbstractSummaryRealtime<List<Statistics>> {
    private final BigDecimal oneHundred = new BigDecimal(100);

    @Override
    public String getReportKey() {
        return ReportKeys.RequestStatistics.name();
    }

    @Override
    public List<Statistics> execute(String reportId, int resourceIndex, int sort) {
        LoadTestReportResultPartKey testReportResultPartKey = new LoadTestReportResultPartKey();
        testReportResultPartKey.setReportId(reportId);
        testReportResultPartKey.setReportKey(ReportKeys.TimeInfo.name());
        testReportResultPartKey.setResourceIndex(resourceIndex);
        LoadTestReportResultPart timeInfoPart = loadTestReportResultPartMapper.selectByPrimaryKey(testReportResultPartKey);
        AtomicReference<ReportTimeInfo> timeInfo = new AtomicReference<>();
        if (timeInfoPart != null) {
            try {
                timeInfo.set(objectMapper.readValue(timeInfoPart.getReportValue(), ReportTimeInfo.class));
            } catch (JsonProcessingException e) {
                LogUtil.error(e);
                timeInfo.set(CommonBeanFactory.getBean(TimeInfoSummaryRealtime.class).execute(reportId, resourceIndex, sort));
            }
        } else {
            timeInfo.set(CommonBeanFactory.getBean(TimeInfoSummaryRealtime.class).execute(reportId, resourceIndex, sort));
        }

        LoadTestReportResultPartKey key = new LoadTestReportResultPartKey();
        key.setReportId(reportId);
        key.setReportKey(getReportKey());
        key.setResourceIndex(resourceIndex);
        LoadTestReportResultPart loadTestReportResultPart = loadTestReportResultPartMapper.selectByPrimaryKey(key);

        List<Statistics> result = new ArrayList<>();
        if (loadTestReportResultPart != null) {
            try {
                result = objectMapper.readValue(loadTestReportResultPart.getReportValue(), new TypeReference<List<Statistics>>() {
                });
            } catch (JsonProcessingException e) {
                LogUtil.error(e);
            }
        }
        List<Statistics> finalResult = result;
        AtomicLong realtimeInfo = new AtomicLong(0);
        SummaryRealtimeAction action = (resultPart) -> {
            try {
                List<Statistics> reportContent = objectMapper.readValue(resultPart.getReportValue(), new TypeReference<List<Statistics>>() {
                });

                LoadTestReportResultRealtimeKey reportResultRealtimeKey = new LoadTestReportResultRealtimeKey();
                reportResultRealtimeKey.setReportId(reportId);
                reportResultRealtimeKey.setReportKey(ReportKeys.TimeInfo.name());
                reportResultRealtimeKey.setResourceIndex(resourceIndex);
                reportResultRealtimeKey.setSort(sort);
                LoadTestReportResultRealtime reportResultRealtime = loadTestReportResultRealtimeMapper.selectByPrimaryKey(reportResultRealtimeKey);
                ReportTimeInfo reportTimeInfo = objectMapper.readValue(reportResultRealtime.getReportValue(), ReportTimeInfo.class);

                realtimeInfo.set(reportTimeInfo.getDuration());

                reportContent.forEach(statistics -> {
                    if (realtimeInfo.get() > 0) {
                        statistics.setTransactions(format.format(new BigDecimal(statistics.getTransactions()).multiply(BigDecimal.valueOf(realtimeInfo.get()))));
                        statistics.setReceived(format.format(new BigDecimal(statistics.getReceived()).multiply(BigDecimal.valueOf(realtimeInfo.get()))));
                        statistics.setSent(format.format(new BigDecimal(statistics.getSent()).multiply(BigDecimal.valueOf(realtimeInfo.get()))));
                    }
                });

                // 保存顺序
                List<String> orderList = reportContent.stream().map(Statistics::getLabel).collect(Collectors.toList());
                // 第一遍不需要汇总
                if (CollectionUtils.isEmpty(finalResult)) {
                    finalResult.addAll(reportContent);
                    return;
                }

                finalResult.forEach(statistics -> {
                    long duration = timeInfo.get().getDuration();
                    if (duration > 0) {
                        statistics.setTransactions(format.format(new BigDecimal(statistics.getTransactions()).multiply(BigDecimal.valueOf(duration))));
                        statistics.setReceived(format.format(new BigDecimal(statistics.getReceived()).multiply(BigDecimal.valueOf(duration))));
                        statistics.setSent(format.format(new BigDecimal(statistics.getSent()).multiply(BigDecimal.valueOf(duration))));
                    }
                });

                // 第二遍以后
                finalResult.addAll(reportContent);

                Map<String, List<Statistics>> collect = finalResult.stream().collect(Collectors.groupingBy(Statistics::getLabel));
                List<Statistics> summaryDataList = collect.keySet().stream().map(k -> {

                    List<Statistics> errorsList = collect.get(k);
                    return getStatistics(k, errorsList);
                }).collect(Collectors.toList());
                // 清空
                finalResult.clear();
                // 保留前几次的结果
                finalResult.addAll(summaryDataList);
                // 按照原始顺序重新排序
                finalResult.sort(Comparator.comparingInt(a -> orderList.indexOf(a.getLabel())));
            } catch (Exception e) {
                LogUtil.error("RequestStatisticsSummaryRealtime: ", e);
            }
        };
        selectRealtimeAndDoSummary(reportId, resourceIndex, sort, getReportKey(), action);
        BigDecimal divisor = new BigDecimal(2);
        //

        finalResult.forEach(statistics -> {
            statistics.setError(format.format(new BigDecimal(statistics.getFail()).divide(new BigDecimal(statistics.getSamples()), 4, RoundingMode.HALF_UP).multiply(oneHundred)));
            statistics.setAverage(format.format(new BigDecimal(statistics.getAverage()).divide(divisor, 4, RoundingMode.HALF_UP)));
            statistics.setMedian(format.format(new BigDecimal(statistics.getMedian()).divide(divisor, 4, RoundingMode.HALF_UP)));
            statistics.setTp90(format.format(new BigDecimal(statistics.getTp90()).divide(divisor, 4, RoundingMode.HALF_UP)));
            statistics.setTp95(format.format(new BigDecimal(statistics.getTp95()).divide(divisor, 4, RoundingMode.HALF_UP)));
            statistics.setTp99(format.format(new BigDecimal(statistics.getTp99()).divide(divisor, 4, RoundingMode.HALF_UP)));
            if (timeInfo.get().getDuration() > 0) {
                statistics.setTransactions(format.format(new BigDecimal(statistics.getTransactions()).divide(BigDecimal.valueOf(timeInfo.get().getDuration() + realtimeInfo.get()), 4, RoundingMode.HALF_UP)));
                statistics.setReceived(format.format(new BigDecimal(statistics.getReceived()).divide(BigDecimal.valueOf(timeInfo.get().getDuration() + realtimeInfo.get()), 4, RoundingMode.HALF_UP)));
                statistics.setSent(format.format(new BigDecimal(statistics.getSent()).divide(BigDecimal.valueOf(timeInfo.get().getDuration() + realtimeInfo.get()), 4, RoundingMode.HALF_UP)));
            }
        });

        // 把 total 放到最后
        List<Statistics> total = finalResult.stream().filter(r -> StringUtils.equalsAnyIgnoreCase(r.getLabel(), "Total")).collect(Collectors.toList());
        finalResult.removeAll(total);
        finalResult.addAll(total);
        return finalResult;
    }

    private Statistics getStatistics(String k, List<Statistics> statisticsList) {
        BigDecimal samples = BigDecimal.ZERO;
        BigDecimal fail = BigDecimal.ZERO;
        BigDecimal error = BigDecimal.ZERO;
        BigDecimal avg = BigDecimal.ZERO;
        BigDecimal min = new BigDecimal(Integer.MAX_VALUE);
        BigDecimal max = new BigDecimal(Integer.MIN_VALUE);
        BigDecimal med = BigDecimal.ZERO;
        BigDecimal tp90 = BigDecimal.ZERO;
        BigDecimal tp95 = BigDecimal.ZERO;
        BigDecimal tp99 = BigDecimal.ZERO;
        BigDecimal trans = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal sent = BigDecimal.ZERO;
        for (Statistics statistics : statisticsList) {
            samples = samples.add(new BigDecimal(statistics.getSamples()));
            fail = fail.add(new BigDecimal(statistics.getFail()));
            error = error.add(new BigDecimal(statistics.getError()));
            avg = avg.add(new BigDecimal(statistics.getAverage()));
            if (min.compareTo(new BigDecimal(statistics.getMin())) > 0) {
                min = new BigDecimal(statistics.getMin());
            }

            if (max.compareTo(new BigDecimal(statistics.getMax())) < 0) {
                max = new BigDecimal(statistics.getMax());
            }
            med = med.add(new BigDecimal(statistics.getMedian()));
            tp90 = tp90.add(new BigDecimal(statistics.getTp90()));
            tp95 = tp95.add(new BigDecimal(statistics.getTp95()));
            tp99 = tp99.add(new BigDecimal(statistics.getTp99()));
            trans = trans.add(new BigDecimal(statistics.getTransactions()));
            received = received.add(new BigDecimal(statistics.getReceived()));
            sent = sent.add(new BigDecimal(statistics.getSent()));

        }

        Statistics c = new Statistics();
        c.setLabel(k);
        c.setSamples(samples.toString());
        c.setFail(fail.toString());
        c.setError(error.toString());

        c.setAverage(avg.toString());
        c.setMin(min.toString());
        c.setMax(max.toString());
        c.setMedian(med.toString());

        c.setTp90(tp90.toString());
        c.setTp95(tp95.toString());
        c.setTp99(tp99.toString());

        c.setTransactions(trans.toString());
        c.setReceived(received.toString());
        c.setSent(sent.toString());
        return c;
    }

}
