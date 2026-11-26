-- 治理概览按实际确认时段读取，由时间索引限定两类追加台账。
ALTER TABLE gov_confirmation ADD KEY ix_gov_confirmation_time (confirmed_at,item_id);
ALTER TABLE gov_discovery_revision ADD KEY ix_gov_discovery_confirmation_time (state,created_at,candidate_id);
