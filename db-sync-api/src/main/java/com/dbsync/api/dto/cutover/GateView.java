package com.dbsync.api.dto.cutover;

import com.dbsync.common.model.GateResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gate 判定视图（4 项指标）。
 *
 * @param pass        是否通过
 * @param evaluatedAt 评估时间
 * @param items       各项明细
 * @param reasons     阻断原因
 */
public record GateView(boolean pass, String evaluatedAt, List<Map<String, Object>> items,
                       List<String> reasons) {

    /**
     * 从模型转换。
     *
     * @param result Gate 结果
     * @return 视图
     */
    public static GateView of(GateResult result) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (GateResult.GateItem item : result.getItems()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", item.getCode() == null ? null : item.getCode().name());
            row.put("actual", Double.valueOf(item.getActual()));
            row.put("threshold", Double.valueOf(item.getThreshold()));
            row.put("pass", Boolean.valueOf(item.isPass()));
            row.put("source", item.getSource());
            row.put("hint", item.getHint());
            items.add(row);
        }
        return new GateView(result.isPass(),
                result.getEvaluatedAt() == null ? null : result.getEvaluatedAt().toString(),
                items, result.getReasons());
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pass", pass);
        map.put("evaluatedAt", evaluatedAt);
        map.put("items", items);
        map.put("reasons", reasons);
        return map;
    }
}
