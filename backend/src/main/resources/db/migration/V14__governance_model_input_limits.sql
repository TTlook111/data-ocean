-- 只保留可披露原因编码，业务列名和原始资料不进入调用记录。
ALTER TABLE gov_model_call
    ADD COLUMN input_limits VARCHAR(120) CHARACTER SET ascii;
