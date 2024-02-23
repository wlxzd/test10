package io.metersphere.streaming.report.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.metersphere.streaming.base.domain.LoadTestReportResultPart;
import io.metersphere.streaming.base.domain.LoadTestReportResultPartKey;
import io.metersphere.streaming.commons.constants.ReportKeys;
import io.metersphere.streaming.commons.utils.LogUtil;
import io.metersphere.streaming.report.base.Errors;
import io.metersphere.streaming.report.base.Statistics;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component("errorsSummaryRealtime")
public class ErrorsSummaryRealtime extends AbstractSummaryRealtime<List<Errors>> {
    private final BigDecimal oneHundred = new BigDecimal(100);
    private final DecimalFormat format3 = new DecimalFormat("0.000");

    @Override
    public String getReportKey() {
        return ReportKeys.Errors.name();
    }

    @Override
    public List<Errors> execute(String reportId, int resourceIndex, int sort) {

        LoadTestReportResultPartKey key = new LoadTestReportResultPartKey();
        key.setReportId(reportId);
        key.setReportKey(getReportKey());
        key.setResourceIndex(resourceIndex);
        LoadTestReportResultPart loadTestReportResultPart = loadTestReportResultPartMapper.selectByPrimaryKey(key);

        List<Errors> result = new ArrayList<>();
        if (loadTestReportResultPart != null) {
            try {
                result = objectMapper.readValue(loadTestReportResultPart.getReportValue(), new TypeReference<List<Errors>>() {
                });
            } catch (JsonProcessingException e) {
                LogUtil.error(e);
            }
        }

        List<Errors> finalResult = result;
        SummaryRealtimeAction action = (resultPart) -> {
            try {
                String reportValue = resultPart.getReportValue();
                List<Errors> reportContent = objectMapper.readValue(reportValue, new TypeReference<List<Errors>>() {
                });
                // 第一遍不需要汇总
                if (CollectionUtils.isEmpty(finalResult)) {
                    finalResult.addAll(reportContent);
                    return;
                }
                // 第二遍以后
                finalResult.addAll(reportContent);

                BigDecimal errors = finalResult.stream().map(e -> new BigDecimal(e.getErrorNumber())).reduce(BigDecimal::add).get();

                Map<String, List<Errors>> collect = finalResult.stream().collect(Collectors.groupingBy(Errors::getErrorType));

                List<Errors> summaryDataList = collect.keySet().stream().map(k -> {

                    List<Errors> errorsList = collect.get(k);

                    Errors c = new Errors();
                    BigDecimal eSum = errorsList.stream().map(e -> new BigDecimal(e.getErrorNumber())).reduce(BigDecimal::add).get();
                    c.setErrorType(k);
                    c.setErrorNumber(eSum.toString());
                    c.setPercentOfErrors(format.format(eSum.divide(errors, 4, RoundingMode.HALF_UP).multiply(oneHundred)));

                    return c;
                }).collect(Collectors.toList());
                // 清空
                finalResult.clear();
                // 保留前几次的结果
                finalResult.addAll(summaryDataList);
                // 返回
            } catch (Exception e) {
                LogUtil.error("ErrorsSummaryRealtime: ", e);
            }
        };
        selectRealtimeAndDoSummary(reportId, resourceIndex, sort, getReportKey(), action);
        // 处理错误率
        handleErrors(reportId, resourceIndex, finalResult);
        return finalResult;
    }

    private void handleErrors(String reportId, int resourceIndex, List<Errors> errors) {
        LoadTestReportResultPartKey key = new LoadTestReportResultPartKey();
        key.setReportId(reportId);
        key.setResourceIndex(resourceIndex);
        key.setReportKey(ReportKeys.RequestStatistics.name());
        LoadTestReportResultPart requestStatistics = loadTestReportResultPartMapper.selectByPrimaryKey(key);
        try {
            if (requestStatistics == null) {
                return;
            }
            List<Statistics> statisticsList = objectMapper.readValue(requestStatistics.getReportValue(), new TypeReference<List<Statistics>>() {
            });
            BigDecimal allSamples = statisticsList.stream()
                    .filter(e -> StringUtils.equals("Total", e.getLabel()))
                    .map(e -> new BigDecimal(e.getSamples()))
                    .reduce(BigDecimal::add)
                    .get();
            errors.forEach(e -> {
                e.setPercentOfAllSamples(format.format(new BigDecimal(e.getErrorNumber()).divide(allSamples, 4, RoundingMode.HALF_UP).multiply(oneHundred)));
            });
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
        }
    }

}
