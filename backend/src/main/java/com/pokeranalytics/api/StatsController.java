package com.pokeranalytics.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pokeranalytics.analytics.BreakdownDimension;
import com.pokeranalytics.analytics.CashStats;
import com.pokeranalytics.analytics.CashStatsCalculator;
import com.pokeranalytics.analytics.CashStatsRow;
import com.pokeranalytics.analytics.StatId;
import com.pokeranalytics.api.dto.ScopeDto;
import com.pokeranalytics.api.dto.StatsQueryRequest;
import com.pokeranalytics.api.dto.StatsQueryResponse;
import com.pokeranalytics.api.dto.StatsQueryResponse.StatRowDto;
import com.pokeranalytics.api.dto.StatsQueryResponse.WarningDto;

/**
 * docs/06-query-catalog.md's QF-1 Aggregate Stat Query, docs/10-api-design.md's {@code POST
 * /api/v1/stats/query}. Only {@code scope.type = "HERO"} and a single (or no) breakdown dimension
 * are implemented so far — see {@link CashStatsCalculator}'s own class doc for what's not.
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final CashStatsCalculator calculator;
    private final JdbcTemplate duckDb;

    public StatsController(CashStatsCalculator calculator, @Qualifier("duckdb") JdbcTemplate duckDb) {
        this.calculator = calculator;
        this.duckDb = duckDb;
    }

    @PostMapping("/query")
    public StatsQueryResponse query(@RequestBody StatsQueryRequest request) {
        long heroId = resolveHeroScope(request.scope());
        BreakdownDimension dimension = resolveDimension(request.breakdown());
        int minSampleSize = request.minSampleSize() == null ? 0 : request.minSampleSize();
        List<String> statSet = request.statSet() == null ? List.of() : request.statSet();

        List<CashStatsRow> rows = calculator.computeBreakdown(heroId, dimension, minSampleSize);

        List<StatRowDto> dtoRows = new ArrayList<>();
        List<WarningDto> warnings = new ArrayList<>();
        for (CashStatsRow row : rows) {
            Map<String, String> breakdownKey = dimension == BreakdownDimension.NONE
                    ? null
                    : Map.of(dimension.name(), row.breakdownKey());

            Map<String, Object> values = new LinkedHashMap<>();
            for (String statName : statSet) {
                StatId statId = parseStatId(statName);
                Object value = valueOf(row.stats(), statId);
                if (value == null) {
                    warnings.add(new WarningDto(statName, breakdownKey, notImplementedOrNoSample(statId)));
                } else {
                    values.put(statName, value);
                }
            }
            dtoRows.add(new StatRowDto(breakdownKey, row.stats().hands(), values));
        }
        return new StatsQueryResponse(dtoRows, warnings);
    }

    private long resolveHeroScope(ScopeDto scope) {
        if (scope == null || !"HERO".equals(scope.type())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_SCOPE",
                    "Only scope.type = \"HERO\" is implemented so far");
        }
        List<Long> heroIds = duckDb.queryForList("SELECT player_id FROM players WHERE is_hero = TRUE", Long.class);
        if (heroIds.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NO_HERO_DATA", "No hands have been imported yet");
        }
        return heroIds.get(0);
    }

    private BreakdownDimension resolveDimension(List<String> breakdown) {
        if (breakdown == null || breakdown.isEmpty()) {
            return BreakdownDimension.NONE;
        }
        if (breakdown.size() > 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MULTI_DIMENSION_BREAKDOWN_NOT_SUPPORTED",
                    "Only a single breakdown dimension is implemented so far");
        }
        try {
            return BreakdownDimension.valueOf(breakdown.get(0));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_BREAKDOWN_DIMENSION",
                    "Unknown breakdown dimension: " + breakdown.get(0));
        }
    }

    private static StatId parseStatId(String statName) {
        try {
            return StatId.valueOf(statName);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_STAT", "Unknown stat identifier: " + statName);
        }
    }

    private static Object valueOf(CashStats stats, StatId statId) {
        return switch (statId) {
            case HANDS -> stats.hands();
            case VPIP -> stats.vpip();
            case PFR -> stats.pfr();
            case THREE_BET -> stats.threeBet();
            case FLOP_CBET -> stats.flopCbet();
            case FOLD_VS_FLOP_CBET -> stats.foldVsFlopCbet();
            case STACK_SIZE_BB -> stats.stackSizeBb();
            case BB_100 -> stats.bb100();
            case ROI, BUBBLE_FACTOR -> null; // tournament-only, not implemented — see StatId's class doc
        };
    }

    private static String notImplementedOrNoSample(StatId statId) {
        return switch (statId) {
            case ROI, BUBBLE_FACTOR -> "NOT_IMPLEMENTED";
            default -> "NO_SAMPLE"; // e.g. BB/100 with zero cash hands in this breakdown group
        };
    }

    // BigDecimal values (rates, BB/100, stack size) serialize as plain JSON numbers via Jackson's
    // default BigDecimal handling — no custom serializer needed for this endpoint yet; money
    // fields (docs/10-api-design.md's string-not-number rule) will need one once a money-valued
    // stat/endpoint is added.
}
