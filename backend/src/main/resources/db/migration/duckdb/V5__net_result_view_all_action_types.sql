-- V2's hand_net_results only ever saw PokerStars' action-type vocabulary (CALL/BET/RAISE, the
-- three blind-post types, POST_ANTE, UNCALLED_RETURN) — it silently ignored ALL_IN, STRADDLE,
-- POST_DEAD_BLIND and POSTS entirely, invisible until now because PokerStarsParser never produces
-- them (its "and is all-in" is a suffix on bet/call/raise, not its own action). Now that
-- PartyPoker/888/iPoker/Winning Poker Network cash are actually imported, hands from those rooms
-- containing any of those four types would silently overstate every involved player's net_result
-- by exactly the un-subtracted amount — the same class of bug V2's own comment already documents
-- fixing once, for a different action type.
--
-- ALL_IN is folded into last_raise's definition: verified against a real Winning Poker Network
-- fixture (do not-call's river all-in (124), followed by digbick30's call of exactly 53 = 124 - 71,
-- digbick30's own prior flop bet) that an all-in over an existing bet is a raise in substance, and
-- when there's no prior street contribution the same cumulative formula reduces to the plain shove
-- amount — so one rule covers both shapes, same as RAISE already did.
--
-- STRADDLE/POST_DEAD_BLIND/POSTS are folded into per_hand_blinds (all three are preflop-only forced
-- bets in every parser that emits them), under the same "already subsumed by a preflop raise's
-- cumulative total" exclusion blinds already had.

CREATE OR REPLACE VIEW hand_net_results AS
WITH distinct_street_participants AS (
    SELECT DISTINCT hand_id, player_id, street
    FROM actions
    WHERE action_type IN ('CALL', 'BET', 'RAISE', 'ALL_IN')
),
last_raise AS (
    SELECT hand_id, player_id, street, amount AS raise_amount, sequence_no AS raise_seq
    FROM (
        SELECT hand_id, player_id, street, amount, sequence_no,
               ROW_NUMBER() OVER (PARTITION BY hand_id, player_id, street ORDER BY sequence_no DESC) AS rn
        FROM actions
        WHERE action_type IN ('RAISE', 'ALL_IN')
    ) ranked
    WHERE rn = 1
),
post_raise_calls AS (
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
    SELECT hand_id, player_id FROM street_contributions
    WHERE street = 'PREFLOP' AND raised_this_street
),
per_hand_blinds AS (
    SELECT a.hand_id, a.player_id, SUM(a.amount) AS blinds
    FROM actions a
    WHERE a.action_type IN ('POST_SMALL_BLIND', 'POST_BIG_BLIND', 'POST_SMALL_AND_BIG_BLIND',
                             'STRADDLE', 'POST_DEAD_BLIND', 'POSTS')
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
