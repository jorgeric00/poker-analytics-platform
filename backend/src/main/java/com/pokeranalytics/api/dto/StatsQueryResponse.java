package com.pokeranalytics.api.dto;

import java.util.List;
import java.util.Map;

/** docs/10-api-design.md's {@code POST /api/v1/stats/query} response shape. */
public record StatsQueryResponse(List<StatRowDto> rows, List<WarningDto> warnings) {

    /**
     * @param breakdownKey {@code null} for the no-breakdown case, else e.g. {@code {"POSITION": "BTN"}}
     * @param values       requested-but-unavailable stats are omitted here, not present as {@code null}
     *                     — see {@link WarningDto} for why a given stat/row is missing
     */
    public record StatRowDto(Map<String, String> breakdownKey, long sampleSize, Map<String, Object> values) {
    }

    /** A requested stat that couldn't be computed for a row — omitted rather than failing the
     *  whole request, per docs/10-api-design.md's Error Handling section. */
    public record WarningDto(String stat, Map<String, String> breakdownKey, String reason) {
    }
}
