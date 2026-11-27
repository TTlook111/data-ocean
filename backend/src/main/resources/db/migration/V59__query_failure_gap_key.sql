-- 问数终态保存已核实的缺口指纹，可靠入队与恢复沿用原结论。
ALTER TABLE qry_request
    ADD COLUMN failure_gap_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
