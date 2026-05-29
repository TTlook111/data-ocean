import unittest

from dataocean.agent.prompt_tracking import record_prompt_version
from dataocean.agent.schema import QueryResult


class AgentPromptTrackingTest(unittest.TestCase):
    def test_record_prompt_version_deduplicates_template_version_pairs(self) -> None:
        state = {}

        state = record_prompt_version(state, "sql_generation", 2)
        state = record_prompt_version(state, "sql_generation", 2)
        state = record_prompt_version(state, "chart_generation", 1)

        self.assertEqual(
            state["prompt_versions"],
            [
                {"templateCode": "sql_generation", "versionNo": 2},
                {"templateCode": "chart_generation", "versionNo": 1},
            ],
        )

    def test_query_result_serializes_prompt_versions_for_java(self) -> None:
        result = QueryResult(
            task_id="task-1",
            status="COMPLETED",
            prompt_versions=[{"templateCode": "sql_generation", "versionNo": 2}],
        )

        payload = result.model_dump(by_alias=True)

        self.assertEqual(
            payload["promptVersions"],
            [{"templateCode": "sql_generation", "versionNo": 2}],
        )


if __name__ == "__main__":
    unittest.main()
