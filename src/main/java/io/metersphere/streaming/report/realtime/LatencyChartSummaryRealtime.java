package io.metersphere.streaming.report.realtime;

import io.metersphere.streaming.commons.constants.ReportKeys;
import io.metersphere.streaming.report.base.ChartsData;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("latencyChartSummaryRealtime")
public class LatencyChartSummaryRealtime extends AbstractSummaryRealtime<List<ChartsData>> {

    @Override
    public String getReportKey() {
        return ReportKeys.LatencyChart.name();
    }

    @Override
    public List<ChartsData> execute(String reportId, int resourceIndex, int sort) {
        return handleAvgAction(reportId, resourceIndex, sort);
    }


}
