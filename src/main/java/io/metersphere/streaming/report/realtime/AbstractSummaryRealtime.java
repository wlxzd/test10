package io.metersphere.streaming.report.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.metersphere.streaming.base.domain.LoadTestReportResultPart;
import io.metersphere.streaming.base.domain.LoadTestReportResultPartKey;
import io.metersphere.streaming.base.domain.LoadTestReportResultRealtime;
import io.metersphere.streaming.base.mapper.LoadTestReportResultPartMapper;
import io.metersphere.streaming.base.mapper.LoadTestReportResultRealtimeMapper;
import io.metersphere.streaming.base.mapper.ext.ExtLoadTestReportMapper;
import io.metersphere.streaming.commons.utils.LogUtil;
import io.metersphere.streaming.report.base.ChartsData;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.collections4.CollectionUtils;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class AbstractSummaryRealtime<T> implements SummaryRealtime<T> {
    protected DecimalFormat format = new DecimalFormat("0.000");

    @Resource
    protected ObjectMapper objectMapper;
    @Resource
    protected ExtLoadTestReportMapper extLoadTestReportMapper;
    @Resource
    protected LoadTestReportResultPartMapper loadTestReportResultPartMapper;
    @Resource
    protected LoadTestReportResultRealtimeMapper loadTestReportResultRealtimeMapper;

    protected SummaryRealtimeAction getSumAction(List<ChartsData> result) {
        return (resultPart) -> {
            try {
                String reportValue = resultPart.getReportValue();
                List<ChartsData> reportContent = objectMapper.readValue(reportValue, new TypeReference<List<ChartsData>>() {
                });
                // 第一遍不需要汇总
                if (CollectionUtils.isEmpty(result)) {
                    result.addAll(reportContent);
                    return;
                }
                // 第二遍以后
                result.addAll(reportContent);

                Map<Tuple, List<ChartsData>> collect = result.stream().collect(Collectors.groupingBy(data -> new Tuple(data.getxAxis(), data.getGroupName())));
                List<ChartsData> summaryDataList = collect.keySet().stream().map(k -> {
                    ChartsData c = new ChartsData();
                    BigDecimal y1Sum = collect.get(k).stream().map(ChartsData::getyAxis).reduce(new BigDecimal(0), BigDecimal::add);
                    BigDecimal y2Sum = collect.get(k).stream().map(ChartsData::getyAxis2).reduce(new BigDecimal(0), BigDecimal::add);
                    c.setxAxis(k.getxAxis());
                    if (y1Sum.compareTo(BigDecimal.ZERO) < 0) {
                        y1Sum = new BigDecimal(-1);
                    }
                    if (y2Sum.compareTo(BigDecimal.ZERO) < 0) {
                        y2Sum = new BigDecimal(-1);
                    }
                    c.setyAxis(y1Sum);
                    c.setyAxis2(y2Sum);
                    c.setGroupName(k.getGroupName());
                    return c;
                }).collect(Collectors.toList());
                // 清空
                result.clear();
                // 保留前几次的结果
                result.addAll(summaryDataList);
                // 返回
            } catch (Exception e) {
                LogUtil.error("getSumAction: ", e);
            }
        };
    }

    protected SummaryRealtimeAction getMaxAction(List<ChartsData> result) {
        return (resultPart) -> {
            try {
                String reportValue = resultPart.getReportValue();
                List<ChartsData> reportContent = objectMapper.readValue(reportValue, new TypeReference<List<ChartsData>>() {
                });
                // 第一遍不需要汇总
                if (CollectionUtils.isEmpty(result)) {
                    result.addAll(reportContent);
                    return;
                }
                // 第二遍以后
                result.addAll(reportContent);

                Map<Tuple, List<ChartsData>> collect = result.stream().collect(Collectors.groupingBy(data -> new Tuple(data.getxAxis(), data.getGroupName())));
                List<ChartsData> summaryDataList = collect.keySet().stream().map(k -> {
                    ChartsData c = new ChartsData();
                    // 这里是vu 需要 max
                    BigDecimal y1Sum = collect.get(k).stream().map(ChartsData::getyAxis).max(BigDecimal::compareTo).get();
                    // 这里专门处理 tps，需要 sum
                    BigDecimal y2Sum = collect.get(k).stream().map(ChartsData::getyAxis2).reduce(new BigDecimal(0), BigDecimal::add);

                    c.setxAxis(k.getxAxis());
                    if (y1Sum.compareTo(BigDecimal.ZERO) < 0) {
                        y1Sum = new BigDecimal(-1);
                    }
                    if (y2Sum.compareTo(BigDecimal.ZERO) < 0) {
                        y2Sum = new BigDecimal(-1);
                    }
                    c.setyAxis(y1Sum);
                    c.setyAxis2(y2Sum);
                    c.setGroupName(k.getGroupName());
                    return c;
                }).collect(Collectors.toList());
                // 清空
                result.clear();
                // 保留前几次的结果
                result.addAll(summaryDataList);
                // 返回
            } catch (Exception e) {
                LogUtil.error("getMaxAction: ", e);
            }
        };
    }

    protected void selectRealtimeAndDoSummary(String reportId, int resourceIndex, int sort, String reportKey, SummaryRealtimeAction action) {
        try {
            LoadTestReportResultRealtime resultRealtime = extLoadTestReportMapper.fetchTestReportRealtime(reportId, reportKey, resourceIndex, sort);
            action.execute(resultRealtime);
        } catch (Exception e) {
            LogUtil.error("查询分布结果失败: ", e);
        }
    }

    protected void handleAvgChartData(List<ChartsData> result, int count) {
        result.forEach(d -> {
            if (d.getyAxis().compareTo(new BigDecimal(0)) > 0) {
                d.setyAxis(d.getyAxis().divide(new BigDecimal(count), 4, RoundingMode.HALF_UP));
            }

            if (d.getyAxis2().compareTo(new BigDecimal(0)) > 0) {
                d.setyAxis2(d.getyAxis2().divide(new BigDecimal(count), 4, RoundingMode.HALF_UP));
            }
        });
    }

    protected List<ChartsData> handleAvgAction(String reportId, int resourceIndex, int sort) {
        LoadTestReportResultPartKey key = new LoadTestReportResultPartKey();
        key.setReportId(reportId);
        key.setReportKey(getReportKey());
        key.setResourceIndex(resourceIndex);
        LoadTestReportResultPart loadTestReportResultPart = loadTestReportResultPartMapper.selectByPrimaryKey(key);

        try {
            List<ChartsData> result = new ArrayList<>();
            if (loadTestReportResultPart != null) {
                result = objectMapper.readValue(loadTestReportResultPart.getReportValue(), new TypeReference<List<ChartsData>>() {
                });
            }
            SummaryRealtimeAction summaryAction = getMaxAction(result);
            selectRealtimeAndDoSummary(reportId, resourceIndex, sort, getReportKey(), summaryAction);
            handleAvgChartData(result, 1);
            return result;
        } catch (JsonProcessingException e) {
            LogUtil.error(e);
            return new ArrayList<>();
        }
    }

    protected List<ChartsData> handleMaxAction(String reportId, int resourceIndex, int sort) {
        LoadTestReportResultPartKey key = new LoadTestReportResultPartKey();
        key.setReportId(reportId);
        key.setReportKey(getReportKey());
        key.setResourceIndex(resourceIndex);
        LoadTestReportResultPart loadTestReportResultPart = loadTestReportResultPartMapper.selectByPrimaryKey(key);

        try {
            List<ChartsData> result = new ArrayList<>();
            if (loadTestReportResultPart != null) {
                result = objectMapper.readValue(loadTestReportResultPart.getReportValue(), new TypeReference<List<ChartsData>>() {
                });
            }
            SummaryRealtimeAction summaryAction = getMaxAction(result);
            selectRealtimeAndDoSummary(reportId, resourceIndex, sort, getReportKey(), summaryAction);
            return result;
        } catch (JsonProcessingException e) {
            LogUtil.error(e);
            return new ArrayList<>();
        }
    }

    protected List<ChartsData> handleSumAction(String reportId, int resourceIndex, int sort) {
        LoadTestReportResultPartKey key = new LoadTestReportResultPartKey();
        key.setReportId(reportId);
        key.setReportKey(getReportKey());
        key.setResourceIndex(resourceIndex);
        LoadTestReportResultPart loadTestReportResultPart = loadTestReportResultPartMapper.selectByPrimaryKey(key);

        try {
            List<ChartsData> result = new ArrayList<>();
            if (loadTestReportResultPart != null) {
                result = objectMapper.readValue(loadTestReportResultPart.getReportValue(), new TypeReference<List<ChartsData>>() {
                });
            }
            SummaryRealtimeAction summaryAction = getSumAction(result);
            selectRealtimeAndDoSummary(reportId, resourceIndex, sort, getReportKey(), summaryAction);
            return result;
        } catch (JsonProcessingException e) {
            LogUtil.error(e);
            return new ArrayList<>();
        }

    }

    @Data
    @AllArgsConstructor
    public static class Tuple {
        String xAxis;
        String groupName;

        public String getxAxis() {
            return xAxis;
        }

        public void setxAxis(String xAxis) {
            this.xAxis = xAxis;
        }
    }
}
