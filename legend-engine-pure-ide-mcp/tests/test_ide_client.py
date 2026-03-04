"""Integration tests for PureIDEClient.

These tests start the real PureIDE Light Java process and execute Pure code
through the PureIDEClient.

Skipped by default. Run explicitly with:
    uv run pytest tests/test_ide_client.py -v -s -m integration
"""

from pathlib import Path

import pytest
import pytest_asyncio

from legend_mcp.classpath import ClasspathManager
from legend_mcp.config import LegendMCPSettings
from legend_mcp.ide_client import PureIDEClient
from legend_mcp.process import PureIDEProcessManager

MCP_DIR = Path(__file__).parent.parent.resolve()
REPO_ROOT = MCP_DIR.parent
PURE_IDE_SERVER_DIR = (
    REPO_ROOT
    / "legend-engine-core"
    / "legend-engine-core-pure"
    / "legend-engine-pure-ide"
    / "legend-engine-pure-ide-light-http-server"
)
CP_TXT = PURE_IDE_SERVER_DIR / "cp.txt"
ARGFILE_TXT = PURE_IDE_SERVER_DIR / "argfile.txt"
CONFIG_FILE = PURE_IDE_SERVER_DIR / "src" / "main" / "resources" / "ideLightConfig.json"
SERVER_URL = "http://localhost:9010"

pytestmark = [
    pytest.mark.integration,
    pytest.mark.asyncio(loop_scope="module"),
]


@pytest.fixture(scope="module")
def settings():
    return LegendMCPSettings()


@pytest.fixture(scope="module")
def classpath_mgr(settings):
    return ClasspathManager(
        settings=settings,
        cp_txt=CP_TXT,
        argfile_txt=ARGFILE_TXT,
        pure_ide_server_dir=PURE_IDE_SERVER_DIR,
        repo_root=REPO_ROOT,
    )


@pytest_asyncio.fixture(scope="module")
async def client(classpath_mgr):
    """Start the PureIDE server and return a PureIDEClient."""
    if not ARGFILE_TXT.exists():
        pytest.skip("argfile.txt not found — PureIDE server not set up")

    pm = PureIDEProcessManager(
        classpath_manager=classpath_mgr,
        repo_root=REPO_ROOT,
        argfile_txt=ARGFILE_TXT,
        config_file=CONFIG_FILE,
        server_url=SERVER_URL,
    )
    await pm.ensure_running()

    ide = PureIDEClient(process_manager=pm, server_url=SERVER_URL)
    yield ide
    pm.stop_server()


async def test_simple_addition(client):
    """Execute a trivial Pure program that prints 1 + 2 = 3."""
    code = """
function go(): Any[*]
{
    let y = 1;
    let x = 2;
    print($x + $y);
}
"""
    result = await client.execute_code(code, execute=True)
    assert "3" in result
    assert "Error" not in result


async def test_compilation_error(client):
    """Verify that a compilation error returns a descriptive message."""
    code = """
function go(): Any[*]
{
    this is not valid Pure code at all!!!
}
"""
    result = await client.execute_code(code, execute=True)
    assert "error" in result.lower()
