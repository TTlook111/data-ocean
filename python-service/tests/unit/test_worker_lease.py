"""验证治理执行租约保护任务以及终态回写。"""

from unittest.mock import MagicMock

import pytest

from dataocean_ai.governance import worker
from dataocean_ai.governance.queue import QueueState


@pytest.mark.parametrize("current_owner", [True, False])
def test_persists_result_only_with_current_lease(monkeypatch, current_owner: bool) -> None:
    """
    只允许租约持有者保存终态，并在结果保存后确认流消息。
    """
    client = MagicMock()
    lease = client.lock.return_value
    lease.name = "fixture:lease"
    lease.acquire.return_value = True
    client.get.return_value = QueueState(runVersion=1, state="RUNNING").model_dump_json()
    pipeline = client.pipeline.return_value.__enter__.return_value

    def owner(_key: str) -> str:
        """
        执行期间可能发生接管，模拟写入状态时 Redis 返回的当前租约。
        """
        if pipeline.get.call_count == 1 or current_owner:
            # 执行开始时租约有效，回写终态前可能失效。
            return lease.acquire.call_args.kwargs["token"]
        return "replacement"

    pipeline.get.side_effect = owner
    result = QueueState(runVersion=1, state="SUCCEEDED", result={})
    monkeypatch.setattr(worker, "process", MagicMock(return_value=result))

    worker.consume(client, "fixture:stream", "1-0", {"task": "fixture", "run": "1"}, None, "")

    if current_owner:
        # 保存终态的事务先于消息确认。
        pipeline.set.assert_called_with(
            worker.task_key("fixture", 1), result.model_dump_json(), ex=3600
        )
        pipeline.xack.assert_called_once_with("fixture:stream", worker.GROUP, "1-0")
        assert [call[0] for call in pipeline.method_calls][-6:] == [
            "multi",
            "set",
            "execute",
            "xack",
            "xdel",
            "execute",
        ]
    else:
        # 旧工作者来不及交付时，新租约仍可继续处理。
        assert QueueState.model_validate_json(pipeline.set.call_args.args[1]).state == "RUNNING"
        assert pipeline.set.call_count == 1
        pipeline.xack.assert_not_called()
