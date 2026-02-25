-- IDEMPIERE-6874 Copy from a Product - Process Adjustment
SELECT register_migration_script('202602241739_IDEMPIERE-6874.sql') FROM dual;

-- Feb 23, 2026, 2:06:26 PM CET
UPDATE AD_Column SET IsAllowCopy='N', SeqNoSelection=10,Updated=TO_TIMESTAMP('2026-02-23 14:06:24','YYYY-MM-DD HH24:MI:SS'),UpdatedBy=100 WHERE AD_Column_ID=53321
;
