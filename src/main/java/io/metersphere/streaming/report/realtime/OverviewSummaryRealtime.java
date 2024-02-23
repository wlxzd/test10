package io.metersphere.streaming.report.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.metersphere.streaming.base.domain.LoadTestReportResultPart;
import io.metersphere.streaming.base.domain.LoadTestReportResultPartKey;
import io.metersphere.streaming.base.mapper.LoadTestReportResultPartMapper;
import io.metersphere.streaming.commons.constants.ReportKeys;
import io.metersphere.streaming.commons.utils.LogUtil;
import io.metersphere.streaming.report.base.ChartsData;
import io.metersphere.streaming.report.base.Statistics;
import io.metersphere.streaming.report.base.TestOverview;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component("overviewSummaryRealtime")
public class OverviewSummaryRealtime extends AbstractSummaryRealtime<TestOverview> {
    private final DecimalFormat format3 = new DecimalFormat("0.000");

    @Resource
    private LoadTestReportResultPartMapper loadTestReportResultPartMapper;

    @Override
    public String getReportKey() {
        return ReportKeys.Overview.name();
    }

    @Override
    public TestOverview execute(String reportId, int resourceIndex, int sort) {

        LoadTestReportResultPartKey key = new LoadTestReportResultPartKey();
        key.setReportId(reportId);
        key.setReportKey(getReportKey());
        key.setResourceIndex(resourceIndex);
        LoadTestReportResultPart loadTestReportResultPart = loadTestReportResultPartMapper.selectByPrimaryKey(key);

        AtomicReference<TestOverview> result = new AtomicReference<>();
        if (loadTestReportResultPart != null) {
            TestOverview testOverview = null;
            try {
                testOverview = objectMapper.readValue(loadTestReportResultPart.getReportValue(), TestOverview.class);
            } catch (JsonProcessingException e) {
                LogUtil.error(e);
            }
            result.set(testOverview);
        }

        SummaryRealtimeAction action = (resultRealtime) -> {
            try {
                String reportValue = resultRealtime.getReportValue();
                TestOverview reportContent = objectMapper.readValue(reportValue, TestOverview.class);
                // 验证 overview 值的有效性
                validate(reportContent);
                // 第一遍不需要汇总
                if (result.get() == null) {
                    result.set(reportContent);
                    return;
                }
                // 第二遍以后
                TestOverview testOverview = result.get();

                BigDecimal bigDecimal2 = new BigDecimal(testOverview.getMaxUsers());
                BigDecimal bigDecimal1 = new BigDecimal(reportContent.getMaxUsers());
                testOverview.setMaxUsers(bigDecimal1.max(bigDecimal2).toString());

                testOverview.setAvgBandwidth(new BigDecimal(testOverview.getAvgBandwidth()).add(new BigDecimal(reportContent.getAvgBandwidth())).toString());
                testOverview.setResponseTime90(new BigDecimal(testOverview.getResponseTime90()).add(new BigDecimal(reportContent.getResponseTime90())).toString());
                testOverview.setAvgResponseTime(new BigDecimal(testOverview.getAvgResponseTime()).add(new BigDecimal(reportContent.getAvgResponseTime())).toString());

                result.set(testOverview);

            } catch (Exception e) {
                LogUtil.error("OverviewSummaryRealtime:", e);
            }
        };
        selectRealtimeAndDoSummary(reportId, resourceIndex, sort, getReportKey(), action);

        BigDecimal divisor = new BigDecimal(2);
        TestOverview testOverview = result.get();

        testOverview.setResponseTime90(format3.format(new BigDecimal(testOverview.getResponseTime90()).divide(divisor, 4, RoundingMode.HALF_UP)));
        testOverview.setAvgResponseTime(format3.format(new BigDecimal(testOverview.getAvgResponseTime()).divide(divisor, 4, RoundingMode.HALF_UP)));
//        testOverview.setAvgBandwidth(format3.format(new BigDecimal(testOverview.getAvgBandwidth()).divide(divisor, 4, RoundingMode.HALF_UP)));

        testOverview.setAvgBandwidth(handleAvgBandwidth(reportId, resourceIndex));
        testOverview.setAvgTransactions(handleAvgTransactions(reportId, resourceIndex));
        testOverview.setErrors(handleErrors(reportId, resourceIndex));

        return testOverview;
    }

    private void validate(TestOverview reportContent) {
        if (!NumberUtils.isCreatable(reportContent.getAvgBandwidth())) {
            reportContent.setAvgBandwidth("0");
        }
        if (!NumberUtils.isCreatable(reportContent.getResponseTime90())) {
            reportContent.setResponseTime90("0");
        }
        if (!NumberUtils.isCreatable(reportContent.getAvgResponseTime())) {
            reportContent.setAvgResponseTime("0");
        }
    }

    private String handleAvgBandwidth(String reportId, int resourceIndex) {
        LoadTestReportResultPartKey key = new LoadTestReportResultPartKey();
        key.setReportId(reportId);
        key.setResourceIndex(resourceIndex);
        key.setReportKey(ReportKeys.BytesThroughputChart.name());
        LoadTestReportResultPart loadTestReportResultPart = loadTestReportResultPartMapper.selectByPrimaryKey(key);
        try {
            if (loadTestReportResultPart == null) {
                return "0";
            }
            List<ChartsData> chartsData = objectMapper.readValue(loadTestReportResultPart.getReportValue(), new TypeReference<List<ChartsData>>() {
            });
            Map<String, List<ChartsData>> collect = chartsData.stream().collect(Collectors.groupingBy(ChartsData::getxAxis));
            BigDecimal sum = new BigDecimal(0);
            Set<String> xAxisList = collect.keySet();
            for (String xAxis : xAxisList) {
                BigDecimal y1Sum = collect.get(xAxis).stream()
                        .filter(c -> StringUtils.equalsIgnoreCase("Bytes received per second", c.getGroupName()))
                        .map(ChartsData::getyAxis)
                        .reduce(new BigDecimal(0), BigDecimal::add);
                sum = sum.add(y1Sum);
            }
            BigDecimal avgTrans = sum.divide(new BigDecimal(xAxisList.size()), 4, RoundingMode.HALF_UP);
            return format3.format(avgTrans.divide(BigDecimal.valueOf(1024), 4, RoundingMode.HALF_UP));
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
        }
        return "0";
    }

    private String handleAvgTransactions(String reportId, int resourceIndex) {
        LoadTestReportResultPartKey key = new LoadTestReportResultPartKey();
        key.setReportId(reportId);
        key.setResourceIndex(resourceIndex);
        key.setReportKey(ReportKeys.TotalTransactionsChart.name());
        LoadTestReportResultPart loadTestReportResultPart = loadTestReportResultPartMapper.selectByPrimaryKey(key);
        try {
            if (loadTestReportResultPart == null) {
                return "0";
            }
            List<ChartsData> chartsData = objectMapper.readValue(loadTestReportResultPart.getReportValue(), new TypeReference<List<ChartsData>>() {
            });
            Map<String, List<ChartsData>> collect = chartsData.stream().collect(Collectors.groupingBy(ChartsData::getxAxis));
            BigDecimal sum = new BigDecimal(0);
            Set<String> xAxisList = collect.keySet();
            for (String xAxis : xAxisList) {
                BigDecimal y1Sum = collect.get(xAxis).stream().map(ChartsData::getyAxis).reduce(new BigDecimal(0), BigDecimal::add);
                sum = sum.add(y1Sum);
            }
            BigDecimal avgTrans = sum.divide(new BigDecimal(xAxisList.size()), 4, RoundingMode.HALF_UP);
            return format3.format(avgTrans);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
        }
        return "0";
    }

    private String handleErrors(String reportId, int resourceIndex) {
        LoadTestReportResultPartKey key = new LoadTestReportResultPartKey();
        key.setReportId(reportId);
        key.setResourceIndex(resourceIndex);
        key.setReportKey(ReportKeys.RequestStatistics.name());
        LoadTestReportResultPart requestStatistics = loadTestReportResultPartMapper.selectByPrimaryKey(key);
        try {
            if (requestStatistics == null) {
                return "0";
            }

            List<Statistics> statisticsList = objectMapper.readValue(requestStatistics.getReportValue(), new TypeReference<List<Statistics>>() {
            });
            double eSum = statisticsList.stream()
                    .filter(e -> StringUtils.equals("Total", e.getLabel()))
                    .mapToDouble(e -> Double.parseDouble(e.getError()))
                    .sum();
            return format3.format(eSum);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
        }
        return "0";
    }

}
