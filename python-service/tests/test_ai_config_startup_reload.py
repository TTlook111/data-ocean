"""Configuration reload regression; dummy secrets, no provider calls."""
import logging
import os
import sys
from pathlib import Path
import pytest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataocean.core import config

@pytest.fixture(autouse=True)
def isolate(monkeypatch):
    old = config.settings
    monkeypatch.setenv("INTERNAL_TOKEN", "offline-test-internal-token-at-least-32-bytes")
    monkeypatch.setenv("DASHSCOPE_API_KEY", "offline-old-key-sentinel")
    monkeypatch.setenv("QWEN_MODEL", "offline-original-model")
    monkeypatch.delenv("EMBEDDING_DIMENSION", raising=False)
    config.get_settings.cache_clear()
    monkeypatch.setattr(config, "settings", config.get_settings())
    yield
    config.get_settings.cache_clear()
    config.settings = old

@pytest.mark.parametrize("upper", [True, False])
def test_java_config_keys_reload_without_logging_secret_values(caplog, upper):
    version = config.get_config_version()
    with caplog.at_level(logging.INFO):
        result = config.reload_config({("QWEN_MODEL" if upper else "qwen_model"):"qwen-flash", ("DASHSCOPE_API_KEY" if upper else "dashscope_api_key"):"offline-new-key-sentinel"})
    assert result.qwen_model == "qwen-flash"
    assert config.get_settings().qwen_model == "qwen-flash"
    assert config.get_config_version() == version + 1
    assert "offline-old-key-sentinel" not in caplog.text
    assert "offline-new-key-sentinel" not in caplog.text

def test_invalid_reload_keeps_previous_environment_and_active_settings():
    old = config.get_settings()
    version = config.get_config_version()
    before = os.environ.get("EMBEDDING_DIMENSION")
    with pytest.raises(ValueError):
        config.reload_config({"EMBEDDING_DIMENSION":"13"})
    actual = os.environ.get("EMBEDDING_DIMENSION")
    assert actual == before
    assert config.get_settings().embedding_dimension == old.embedding_dimension
    assert config.settings is old
    assert config.get_config_version() == version

@pytest.mark.parametrize("available", [True, False])
def test_service_startup_attempts_saved_ai_sync_and_remains_available_on_failure(monkeypatch, available):
    from unittest.mock import AsyncMock
    from fastapi.testclient import TestClient
    from dataocean import main
    from dataocean.infra import config_api
    from dataocean.prompt import receipts
    sync = AsyncMock(return_value=available)
    monkeypatch.setattr(config_api, "reload_ai_config", sync)
    monkeypatch.setattr(main.settings, "langgraph_checkpoint_redis_url", "")
    monkeypatch.setattr(main, "_validate_config", lambda: None)
    monkeypatch.setattr(main, "_destroy_all_pools", lambda: None)
    monkeypatch.setattr(receipts, "start_receipt_dispatcher", lambda: None)
    monkeypatch.setattr(receipts, "close_receipt_dispatcher", AsyncMock())
    with TestClient(main.app) as client:
        assert client.get("/health").status_code == 200
        sync.assert_awaited_once()
