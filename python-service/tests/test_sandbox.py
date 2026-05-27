import unittest

from dataocean.sandbox.rewriter import rewrite
from dataocean.sandbox.validator import validate


class SandboxRewriteTest(unittest.TestCase):
    def test_denied_column_checks_table_alias(self) -> None:
        result = rewrite(
            "SELECT u.phone FROM users u",
            denied_columns={"users": ["phone"]},
        )

        self.assertFalse(result.success)
        self.assertIn("users.phone", result.denied_reason)

    def test_unqualified_denied_column_checks_single_table_scope(self) -> None:
        result = rewrite(
            "SELECT phone FROM users",
            denied_columns={"users": ["phone"]},
        )

        self.assertFalse(result.success)
        self.assertIn("users.phone", result.denied_reason)

    def test_row_filter_applies_only_to_matching_table(self) -> None:
        result = rewrite(
            "SELECT id FROM products",
            row_filters={"orders": ["region = 'east'"]},
        )

        self.assertTrue(result.success)
        self.assertEqual(result.rewritten_sql, "SELECT id FROM products LIMIT 10000")

    def test_row_filter_is_qualified_with_matching_alias(self) -> None:
        result = rewrite(
            "SELECT o.id, u.name FROM orders o JOIN users u ON o.user_id = u.id",
            row_filters={
                "orders": ["region = 'east'"],
                "users": ["tenant_id = 1"],
            },
        )

        self.assertTrue(result.success)
        self.assertIn("WHERE o.region = 'east' AND u.tenant_id = 1", result.rewritten_sql)

    def test_outer_limit_is_injected_when_only_subquery_has_limit(self) -> None:
        result = rewrite(
            "SELECT id FROM users WHERE id IN (SELECT user_id FROM orders LIMIT 5)",
        )

        self.assertTrue(result.success)
        self.assertEqual(
            result.rewritten_sql,
            "SELECT id FROM users WHERE id IN (SELECT user_id FROM orders LIMIT 5) LIMIT 10000",
        )

    def test_mask_columns_return_physical_table_name_for_alias(self) -> None:
        result = rewrite(
            "SELECT u.email FROM users u",
            mask_columns={"users": ["email"]},
            mask_strategies={"users.email": "EMAIL"},
        )

        self.assertTrue(result.success)
        self.assertEqual(result.masked_fields, {"email": "EMAIL"})

    def test_mask_columns_with_alias_output(self) -> None:
        result = rewrite(
            "SELECT u.email AS contact FROM users u",
            mask_columns={"users": ["email"]},
            mask_strategies={"users.email": "EMAIL"},
        )

        self.assertTrue(result.success)
        self.assertEqual(result.masked_fields, {"contact": "EMAIL"})

    def test_mask_columns_in_expression(self) -> None:
        result = rewrite(
            "SELECT CONCAT(u.phone, 'x') AS info FROM users u",
            mask_columns={"users": ["phone"]},
            mask_strategies={"users.phone": "PHONE"},
        )

        self.assertTrue(result.success)
        self.assertEqual(result.masked_fields, {"info": "PHONE"})

    def test_mask_columns_derived_table(self) -> None:
        result = rewrite(
            "SELECT contact FROM (SELECT u.phone AS contact FROM users u) t",
            mask_columns={"users": ["phone"]},
            mask_strategies={"users.phone": "PHONE"},
        )

        self.assertTrue(result.success)
        self.assertEqual(result.masked_fields, {"contact": "PHONE"})

    def test_mask_columns_no_alias_expression(self) -> None:
        result = rewrite(
            "SELECT CONCAT(u.phone, 'x') FROM users u",
            mask_columns={"users": ["phone"]},
            mask_strategies={"users.phone": "PHONE"},
        )

        self.assertTrue(result.success)
        # output_name for CONCAT without alias is typically the expression text
        self.assertTrue(len(result.masked_fields) == 1)
        strategy = next(iter(result.masked_fields.values()))
        self.assertEqual(strategy, "PHONE")

    def test_mask_columns_multiple_subqueries_same_alias(self) -> None:
        sql = (
            "SELECT u.contact AS user_contact, o.contact AS order_contact "
            "FROM (SELECT phone AS contact FROM users) u "
            "JOIN (SELECT bank_card AS contact FROM orders) o ON 1=1"
        )
        result = rewrite(
            sql,
            mask_columns={"users": ["phone"], "orders": ["bank_card"]},
            mask_strategies={"users.phone": "PHONE", "orders.bank_card": "BANK_CARD"},
        )

        self.assertTrue(result.success)
        self.assertEqual(result.masked_fields.get("user_contact"), "PHONE")
        self.assertEqual(result.masked_fields.get("order_contact"), "BANK_CARD")


class SandboxValidationTest(unittest.TestCase):
    def test_dangerous_functions_and_wildcards_are_rejected(self) -> None:
        self.assertFalse(validate("SELECT SLEEP(1)", ["users"]).passed)
        self.assertFalse(validate("SELECT * FROM users", ["users"]).passed)


if __name__ == "__main__":
    unittest.main()
