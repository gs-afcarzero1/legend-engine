"""Integration tests for ClasspathManager against the real repo.

Verifies that generate_argfile() produces a classpath that would
actually allow PureIDELight to start. Skipped by default.

Run with:
    uv run pytest tests/test_classpath_integration.py -v -m integration
"""

from pathlib import Path

import pytest

from legend_mcp.classpath import ClasspathManager
from legend_mcp.config import LegendMCPSettings

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

PURE_IDE_LIGHT_CLASS = (
    PURE_IDE_SERVER_DIR / "target" / "classes"
    / "org" / "finos" / "legend" / "engine" / "ide" / "PureIDELight.class"
)

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def classpath_mgr():
    if not CP_TXT.exists():
        pytest.skip("cp.txt not found — PureIDE server module not built")

    settings = LegendMCPSettings()
    mgr = ClasspathManager(
        settings=settings,
        cp_txt=CP_TXT,
        argfile_txt=ARGFILE_TXT,
        pure_ide_server_dir=PURE_IDE_SERVER_DIR,
        repo_root=REPO_ROOT,
    )
    mgr.generate_argfile()
    return mgr


def test_argfile_is_generated(classpath_mgr):
    """argfile.txt must exist after generate_argfile()."""
    assert ARGFILE_TXT.exists()


def test_argfile_has_correct_structure(classpath_mgr):
    """argfile.txt must have JVM args, -cp, then the classpath."""
    content = ARGFILE_TXT.read_text().strip()
    lines = content.split("\n")
    assert len(lines) >= 3, f"Expected at least 3 lines, got {len(lines)}"
    assert lines[-2] == "-cp", f"Second-to-last line should be '-cp', got '{lines[-2]}'"


def test_server_classes_on_classpath(classpath_mgr):
    """The server module's target/classes must be on the classpath."""
    content = ARGFILE_TXT.read_text()
    cp_line = content.strip().split("\n")[-1]

    server_classes = str(PURE_IDE_SERVER_DIR / "target" / "classes")
    assert server_classes in cp_line, (
        f"Server target/classes not found in classpath.\n"
        f"Expected: {server_classes}"
    )


def test_pure_ide_light_class_exists(classpath_mgr):
    """PureIDELight.class must exist in the server module's target/classes."""
    assert PURE_IDE_LIGHT_CLASS.exists(), (
        f"PureIDELight.class not found at {PURE_IDE_LIGHT_CLASS}.\n"
        f"The server module may need to be compiled: "
        f"mvn install -DskipTests -pl {PURE_IDE_SERVER_DIR.relative_to(REPO_ROOT)} -am"
    )


def test_classpath_entries_exist_on_disk(classpath_mgr):
    """All entries in the generated classpath should exist on disk."""
    content = ARGFILE_TXT.read_text()
    cp_line = content.strip().split("\n")[-1]
    entries = cp_line.split(";")

    missing = [e for e in entries if e and not Path(e).exists()]
    assert len(missing) == 0, (
        f"{len(missing)} classpath entries do not exist on disk. "
        f"First 5: {missing[:5]}"
    )


def test_config_file_exists(classpath_mgr):
    """The ideLightConfig.json must exist for the server to start."""
    assert CONFIG_FILE.exists(), (
        f"Server config file not found at {CONFIG_FILE}"
    )
