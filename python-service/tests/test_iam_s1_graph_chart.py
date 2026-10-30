from dataocean.iam_s1.graph import _deterministic_chart


def test_chart_uses_java_protected_decimal_strings_as_numeric_values():
    result = _deterministic_chart(
        {"question": "按地区统计销售额", "rewrittenQuestion": "按地区统计销售额"},
        {
            "data": [
                {"region": "North", "revenue": "50.00"},
                {"region": "South", "revenue": "120.00"},
            ],
            "columns": [{"name": "region"}, {"name": "revenue"}],
            "maskedFields": {},
        },
    )

    assert result is not None
    assert result["xAxis"]["data"] == ["North", "South"]
    assert result["series"][0]["data"] == [50.0, 120.0]


def test_chart_refuses_to_visualize_masked_results():
    result = _deterministic_chart(
        {"question": "统计销售额"},
        {"data": [{"region": "North", "revenue": "50.00"}],
         "columns": [{"name": "region"}, {"name": "revenue"}],
         "maskedFields": {"revenue": "HASH"}},
    )

    assert result is None
