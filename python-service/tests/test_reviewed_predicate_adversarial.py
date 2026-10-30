"""Synthetic business contract: only COMPLETED orders count as completed sales."""
import pytest
from dataocean.iam_s1.sql_security import missing_reviewed_predicates

TERMS = [{"name": "已完成", "description": "orders.status = 'COMPLETED'",
          "columns": ["orders.status"]}]

@pytest.mark.parametrize("sql", [
    "SELECT COUNT(*) FROM orders WHERE status='COMPLETED' OR 1=1",
    "SELECT COUNT(*) FROM orders WHERE NOT(status='COMPLETED')",
    "SELECT COUNT(*) FROM orders WHERE status='COMPLETED' XOR 1=1",
    "SELECT COUNT(*) FROM orders WHERE EXISTS(SELECT 1 FROM orders x WHERE x.status='COMPLETED')",
    "SELECT COUNT(*) FROM orders WHERE status='COMPLETED' UNION ALL SELECT COUNT(*) FROM orders",
    "SELECT COUNT(*) FROM orders a JOIN orders b ON a.id=b.id WHERE a.status='COMPLETED'",
    "SELECT COUNT(*) FROM orders a WHERE EXISTS(SELECT 1 FROM orders a WHERE a.status='COMPLETED')",
])
def test_mentioning_a_reviewed_condition_does_not_prove_it_applies(sql):
    assert missing_reviewed_predicates(sql, "已完成订单数量", TERMS) == ["orders.status = 'COMPLETED'"]

@pytest.mark.parametrize("sql", [
    "SELECT COUNT(*) FROM orders WHERE status='COMPLETED'",
    "SELECT COUNT(*) FROM orders o WHERE (o.status='COMPLETED' AND id>1)",
    "SELECT COUNT(*) FROM orders WHERE (status='COMPLETED' AND id=1) OR (status='COMPLETED' AND id=2)",
    "SELECT COUNT(*) FROM orders WHERE status='COMPLETED' UNION ALL SELECT COUNT(*) FROM orders WHERE status='COMPLETED'",
    "SELECT COUNT(*) FROM orders a JOIN orders b ON a.id=b.id WHERE a.status='COMPLETED' AND b.status='COMPLETED'",
    "WITH eligible AS (SELECT id FROM orders WHERE status='COMPLETED') SELECT COUNT(*) FROM eligible",
    "WITH unused AS (SELECT 1) SELECT COUNT(*) FROM orders WHERE status='COMPLETED'",
])
def test_required_condition_is_proven_for_every_scan(sql):
    assert missing_reviewed_predicates(sql, "已完成订单数量", TERMS) == []
