-- Keep legacy daily rows and attribute future usage to the correct model.
ALTER TABLE token_usage_log DROP INDEX uk_token_usage_user_date;
ALTER TABLE token_usage_log ADD UNIQUE KEY uk_token_usage_user_date_model (user_id, date_key, model);

ALTER TABLE user_quota ADD COLUMN monthly_reset_at DATE NULL;
ALTER TABLE ai_model_config MODIFY COLUMN api_key_alias VARCHAR(512) NULL;
UPDATE user_quota SET monthly_reset_at = DATE_FORMAT(CURRENT_DATE(), '%Y-%m-01') WHERE monthly_reset_at IS NULL;

-- Existing users without a role could log in but had no user permissions.
INSERT IGNORE INTO sys_user_role (user_id, role_id, created_at)
SELECT u.id, r.id, NOW() FROM sys_user u CROSS JOIN sys_role r
WHERE r.code = 'USER' AND NOT EXISTS (
  SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id
);
