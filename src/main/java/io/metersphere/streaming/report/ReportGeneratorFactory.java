package io.metersphere.streaming.report;

import io.metersphere.streaming.commons.utils.LogUtil;
import io.metersphere.streaming.report.impl.AbstractReport;
import org.apache.commons.collections4.CollectionUtils;
import org.reflections.Reflections;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ReportGeneratorFactory {
    private static Set<Class<? extends AbstractReport>> subTypes;

    private synchronized static Set<Class<? extends AbstractReport>> getSubTypes() {
        if (CollectionUtils.isNotEmpty(subTypes)) {
            return subTypes;
        }
        Reflections reflections = new Reflections(ReportGeneratorFactory.class);
        subTypes = reflections.getSubTypesOf(AbstractReport.class);
        return subTypes;
    }

    public static List<AbstractReport> getReportGenerators() {

        List<AbstractReport> result = new ArrayList<>();
        getSubTypes().forEach(s -> {
            try {
                result.add(s.getDeclaredConstructor().newInstance());
            } catch (Exception e) {
                LogUtil.error(e);
            }
        });
        return result;
    }
}

