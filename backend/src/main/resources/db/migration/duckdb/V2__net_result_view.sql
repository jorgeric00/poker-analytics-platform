-- docs/07-analytics-engine.md's Metrics layer, docs/08-storage-strategy.md's decision to keep
-- Opportunities/Events/Metrics as query-time SQL, not materialized (DuckDB's own limitation:
-- CREATE VIEW always re-runs live, confirmed while designing that document).
--
-- hand_net_results: each (hand, player)'s net chip/money result for that hand — feeds BB/100 and,
-- later, All-in Adjusted P&L / EV. Not a trivial "amount_won minus a simple action sum": PokerStars'
-- own grammar (and every other room ported so far) states a RAISE's amount as the player's
-- resulting TOTAL commitment for that street ("raises X to Y" — Y is cumulative), while CALL/BET
-- amounts are each INCREMENTAL (the amount added by that specific action). Naively summing every
-- action's amount would double-count a player who calls, faces a raise, then calls again on the
-- same street. Correct rule, applied per (hand, player, street): if the player's LAST action on
-- that street was a RAISE, their street contribution is that raise's amount (already cumulative);
-- otherwise it's the sum of their CALL/BET amounts on that street (each safely incremental).
--
-- The same "raise states a cumulative total" rule reaches into the blinds, too: a preflop raise's
-- "to Y" total already includes any blind that player posted (blinds are part of the preflop
-- betting round) — so a player who raises preflop must NOT also have their blind added separately,
-- or it gets double-counted (caught by handNetResultsConserveMoneyAcrossEveryRealHand while
-- building this view: every real hand with a preflop raiser drifted from money conservation by
-- exactly their blind amount, before this was accounted for). Antes are unaffected — they're
-- posted before, and separate from, the preflop betting round a raise's total refers to.
-- UNCALLED_RETURN is subtracted back out in all cases.

CREATE VIEW hand_net_results AS
WITH distinct_street_participants AS (
    SELECT DISTINCT hand_id, player_id, street
    FROM actions
    WHERE action_type IN ('CALL', 'BET', 'RAISE')
),
last_raise AS (
    -- a RAISE's amount is already the player's full cumulative total for that street up to and
    -- including it (see comment block above) — anchor every street's total on their LAST raise,
    -- not their last action, since a call/bet *after* a raise (facing a further re-raise) is a
    -- genuinely separate, incremental amount on top of it, not a replacement for it.
    SELECT hand_id, player_id, street, amount AS raise_amount, sequence_no AS raise_seq
    FROM (
        SELECT hand_id, player_id, street, amount, sequence_no,
               ROW_NUMBER() OVER (PARTITION BY hand_id, player_id, street ORDER BY sequence_no DESC) AS rn
        FROM actions
        WHERE action_type = 'RAISE'
    ) ranked
    WHERE rn = 1
),
post_raise_calls AS (
    -- CALL/BET amounts strictly after that player's own last raise on the street (or every
    -- CALL/BET on the street, if they never raised there at all)
    SELECT dsp.hand_id, dsp.player_id, dsp.street,
           SUM(CASE WHEN a.action_type IN ('CALL', 'BET')
                      AND (lr.raise_seq IS NULL OR a.sequence_no > lr.raise_seq)
                    THEN a.amount ELSE 0 END) AS extra
    FROM distinct_street_participants dsp
    JOIN actions a ON a.hand_id = dsp.hand_id AND a.player_id = dsp.player_id AND a.street = dsp.street
    LEFT JOIN last_raise lr ON lr.hand_id = dsp.hand_id AND lr.player_id = dsp.player_id AND lr.street = dsp.street
    GROUP BY dsp.hand_id, dsp.player_id, dsp.street
),
street_contributions AS (
    SELECT dsp.hand_id, dsp.player_id, dsp.street,
           COALESCE(lr.raise_amount, 0) + COALESCE(prc.extra, 0) AS street_contribution,
           (lr.raise_amount IS NOT NULL) AS raised_this_street
    FROM distinct_street_participants dsp
    LEFT JOIN last_raise lr ON lr.hand_id = dsp.hand_id AND lr.player_id = dsp.player_id AND lr.street = dsp.street
    LEFT JOIN post_raise_calls prc ON prc.hand_id = dsp.hand_id AND prc.player_id = dsp.player_id AND prc.street = dsp.street
),
per_hand_wagered AS (
    SELECT hand_id, player_id, SUM(street_contribution) AS wagered
    FROM street_contributions
    GROUP BY hand_id, player_id
),
preflop_raisers AS (
    -- their PREFLOP total (above) already subsumes whatever blind they posted, whether or not the
    -- raise was their last preflop action
    SELECT hand_id, player_id FROM street_contributions
    WHERE street = 'PREFLOP' AND raised_this_street
),
per_hand_blinds AS (
    SELECT a.hand_id, a.player_id, SUM(a.amount) AS blinds
    FROM actions a
    WHERE a.action_type IN ('POST_SMALL_BLIND', 'POST_BIG_BLIND', 'POST_SMALL_AND_BIG_BLIND')
      AND NOT EXISTS (
          SELECT 1 FROM preflop_raisers pr WHERE pr.hand_id = a.hand_id AND pr.player_id = a.player_id
      )
    GROUP BY a.hand_id, a.player_id
),
per_hand_antes AS (
    SELECT hand_id, player_id, SUM(amount) AS antes
    FROM actions
    WHERE action_type = 'POST_ANTE'
    GROUP BY hand_id, player_id
),
per_hand_uncalled AS (
    SELECT hand_id, player_id, SUM(amount) AS uncalled
    FROM actions
    WHERE action_type = 'UNCALLED_RETURN'
    GROUP BY hand_id, player_id
)
SELECT
    r.hand_id,
    r.player_id,
    r.amount_won
        - COALESCE(w.wagered, 0)
        - COALESCE(b.blinds, 0)
        - COALESCE(an.antes, 0)
        + COALESCE(u.uncalled, 0) AS net_result
FROM results r
LEFT JOIN per_hand_wagered w ON w.hand_id = r.hand_id AND w.player_id = r.player_id
LEFT JOIN per_hand_blinds b ON b.hand_id = r.hand_id AND b.player_id = r.player_id
LEFT JOIN per_hand_antes an ON an.hand_id = r.hand_id AND an.player_id = r.player_id
LEFT JOIN per_hand_uncalled u ON u.hand_id = r.hand_id AND u.player_id = r.player_id;
