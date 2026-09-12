-- C-12: budget_audit is append-only, enforced at the database level. The application
-- role owns these tables, so REVOKE/RLS would not bind (owners bypass both);
-- only a trigger aborts writes for every role including owners. Any future purge
-- policy must drop this trigger in a reviewed migration — never in application code.
CREATE OR REPLACE FUNCTION prevent_budget_audit_mutation() RETURNS trigger AS $$
BEGIN
	RAISE EXCEPTION 'budget_audit is append-only (trigger trg_budget_audit_immutable)';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_budget_audit_immutable ON budget_audit;
CREATE TRIGGER trg_budget_audit_immutable
	BEFORE UPDATE OR DELETE ON budget_audit
	FOR EACH ROW EXECUTE FUNCTION prevent_budget_audit_mutation();
