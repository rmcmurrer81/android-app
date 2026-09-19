from pathlib import Path
import tempfile

import pytest

from sarah_core import SarahDatabase
from sarah_sync_server import SarahSyncServer


def test_sync_server_defaults_to_loopback_and_is_not_started() -> None:
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        server = SarahSyncServer(SarahDatabase(Path(temp)))
        assert server.host == "127.0.0.1"
        assert server.httpd is None
        assert server.thread is None
        assert server.discovery_thread is None


def test_sync_server_rejects_plain_lan_binding() -> None:
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        database = SarahDatabase(Path(temp))
        with pytest.raises(ValueError, match="authenticated"):
            SarahSyncServer(database, host="0.0.0.0")
