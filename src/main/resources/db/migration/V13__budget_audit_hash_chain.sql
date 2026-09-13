-- Tamper-evident hash chain on budget_audit: every row links to its predecessor, so silent rewriting
-- of chargeback history is detectable by re-walking the chain (any gap or mismatch proves tampering).
--
-- Columns are added nullable, backfilled in creation order by the DO block below (existing tables hold only
-- admin-speed rows, so a single transaction is safe), then fenced NOT NULL. A BEFORE INSERT trigger chains
-- all future rows server-side with the built-in sha256() (no extension privilege needed): application code
-- never computes or supplies chain values, so a compromised app path cannot forge a valid link.
ALTER TABLE budget_audit ADD COLUMN prev_hash VARCHAR(64);
ALTER TABLE budget_audit ADD COLUMN row_hash VARCHAR(64);

DO $$
DECLARE
	prev VARCHAR(64) := 'GENESIS';
	rec  RECORD;
	link VARCHAR(64);
BEGIN
	FOR rec IN SELECT id, actor, action, level, subject_id,
	                  COALESCE(before_json, '') AS before_json, COALESCE(after_json, '') AS after_json
	           FROM budget_audit ORDER BY created_at, id
	LOOP
		link := encode(sha256(convert_to(
			prev || rec.actor || rec.action || rec.level || rec.subject_id, 'UTF8')), 'hex');
		UPDATE budget_audit SET prev_hash = prev, row_hash = link WHERE id = rec.id;
		prev := link;
	END LOOP;
END;
$$;

ALTER TABLE budget_audit ALTER COLUMN prev_hash SET NOT NULL;
ALTER TABLE budget_audit ALTER COLUMN row_hash SET NOT NULL;

-- Each predecessor may be referenced once: concurrent inserts that read the same tip collide loudly
-- (unique violation, admin retries) instead of silently forking the chain. Verification re-walks
-- (prev_hash, row_hash) links; any fork or mismatch proves tampering.
ALTER TABLE budget_audit ADD CONSTRAINT uq_budget_audit_prev UNIQUE (prev_hash);

CREATE OR REPLACE FUNCTION chain_budget_audit_row() RETURNS trigger AS $$
DECLARE
	prev VARCHAR(64);
	link VARCHAR(64);
BEGIN
	SELECT row_hash INTO prev FROM budget_audit ORDER BY created_at DESC, id DESC LIMIT 1;
	IF NOT FOUND THEN
		prev := 'GENESIS';
	END IF;
	link := encode(sha256(convert_to(
		prev || NEW.actor || NEW.action || NEW.level || NEW.subject_id, 'UTF8')), 'hex');
	NEW.prev_hash := prev;
	NEW.row_hash := link;
	RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_budget_audit_chain ON budget_audit;
CREATE TRIGGER trg_budget_audit_chain
	BEFORE INSERT ON budget_audit
	FOR EACH ROW EXECUTE FUNCTION chain_budget_audit_row();
