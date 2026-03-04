"""Unit tests for ClasspathManager.

These tests use a temporary directory structure with a fake cp.txt,
so they run fast and don't require the real Legend Engine build.
"""

import os
from pathlib import Path

import pytest

from legend_mcp.classpath import ClasspathManager
from legend_mcp.config import LegendMCPSettings

_LEGEND_ENV_VARS = [
    "LEGEND_MAVEN_OPTS",
    "LEGEND_BUILD_THREADS",
    "LEGEND_JVM_MAX_MEMORY",
    "LEGEND_JVM_EXTRA_ARGS",
]


@pytest.fixture(autouse=True)
def _clean_legend_env(monkeypatch):
    for var in _LEGEND_ENV_VARS:
        monkeypatch.delenv(var, raising=False)


@pytest.fixture
def fake_repo(tmp_path):
    """Create a minimal fake repo structure with cp.txt."""
    repo_root = tmp_path / "legend-engine"
    server_dir = (
        repo_root / "legend-engine-core" / "legend-engine-core-pure"
        / "legend-engine-pure-ide" / "legend-engine-pure-ide-light-http-server"
    )
    server_dir.mkdir(parents=True)

    # Create target/classes and target/test-classes for the server module
    (server_dir / "target" / "classes").mkdir(parents=True)
    (server_dir / "target" / "test-classes").mkdir(parents=True)

    # Create a fake cp.txt with semicolon-separated JAR paths
    # Paths must use artifact_id as a directory component (like real .m2 layouts)
    m2 = tmp_path / ".m2" / "repository"
    jars = [
        str(m2 / "legend-pure-m4" / "5.0" / "legend-pure-m4-5.0.jar"),
        str(m2 / "legend-engine-xt-mongodb-grammar" / "4.0" / "legend-engine-xt-mongodb-grammar-4.0.jar"),
        str(m2 / "antlr4-runtime" / "4.8" / "antlr4-runtime-4.8.jar"),
    ]
    cp_txt = server_dir / "cp.txt"
    cp_txt.write_text(";".join(jars))

    argfile_txt = server_dir / "argfile.txt"

    return {
        "repo_root": repo_root,
        "server_dir": server_dir,
        "cp_txt": cp_txt,
        "argfile_txt": argfile_txt,
        "jars": jars,
    }


def _make_manager(fake_repo, **kwargs):
    settings = LegendMCPSettings(**kwargs)
    return ClasspathManager(
        settings=settings,
        cp_txt=fake_repo["cp_txt"],
        argfile_txt=fake_repo["argfile_txt"],
        pure_ide_server_dir=fake_repo["server_dir"],
        repo_root=fake_repo["repo_root"],
    )


class TestGenerateArgfile:
    """Test baseline argfile generation from cp.txt."""

    def test_generates_argfile(self, fake_repo):
        mgr = _make_manager(fake_repo)
        summary = mgr.generate_argfile()

        assert fake_repo["argfile_txt"].exists()
        assert "No overrides active" in summary

    def test_argfile_contains_jvm_args(self, fake_repo):
        mgr = _make_manager(fake_repo)
        mgr.generate_argfile()

        content = fake_repo["argfile_txt"].read_text()
        lines = content.strip().split("\n")
        assert lines[0] == "-Dmongo_pwd=legendpass"
        assert lines[1] == "-Xmx4G"
        assert lines[2] == "-cp"

    def test_argfile_uses_custom_memory(self, fake_repo):
        mgr = _make_manager(fake_repo, jvm_max_memory="16G")
        mgr.generate_argfile()

        content = fake_repo["argfile_txt"].read_text()
        assert "-Xmx16G" in content

    def test_argfile_prepends_server_classes(self, fake_repo):
        mgr = _make_manager(fake_repo)
        mgr.generate_argfile()

        content = fake_repo["argfile_txt"].read_text()
        cp_line = content.strip().split("\n")[-1]
        entries = cp_line.split(";")

        # First two entries should be the server's own target/classes dirs
        assert entries[0].endswith("target\\classes") or entries[0].endswith("target/classes")
        assert entries[1].endswith("target\\test-classes") or entries[1].endswith("target/test-classes")

    def test_argfile_includes_all_cp_jars(self, fake_repo):
        mgr = _make_manager(fake_repo)
        mgr.generate_argfile()

        content = fake_repo["argfile_txt"].read_text()
        for jar in fake_repo["jars"]:
            assert jar in content


class TestOverrides:
    """Test classpath override (hot-swap) logic."""

    def test_override_prepends_target_classes(self, fake_repo):
        mgr = _make_manager(fake_repo)

        override_dir = fake_repo["repo_root"] / "my-module" / "target" / "classes"
        override_dir.mkdir(parents=True)
        mgr.add_override("my-module", override_dir)
        mgr.generate_argfile()

        content = fake_repo["argfile_txt"].read_text()
        cp_line = content.strip().split("\n")[-1]
        entries = cp_line.split(";")

        # Override should be the very first entry
        assert str(override_dir) == entries[0]

    def test_override_removes_matching_jar(self, fake_repo):
        mgr = _make_manager(fake_repo)

        override_dir = fake_repo["repo_root"] / "legend-engine-xt-mongodb-grammar" / "target" / "classes"
        override_dir.mkdir(parents=True)
        mgr.add_override("legend-engine-xt-mongodb-grammar", override_dir)

        summary = mgr.generate_argfile()

        content = fake_repo["argfile_txt"].read_text()
        # The matching JAR should be filtered out
        assert "legend-engine-xt-mongodb-grammar-4.0.jar" not in content
        # But other JARs remain
        assert "legend-pure-m4-5.0.jar" in content
        assert "Overrides active: 1" in summary

    def test_clear_overrides_restores_all_jars(self, fake_repo):
        mgr = _make_manager(fake_repo)

        override_dir = fake_repo["repo_root"] / "legend-engine-xt-mongodb-grammar" / "target" / "classes"
        override_dir.mkdir(parents=True)
        mgr.add_override("legend-engine-xt-mongodb-grammar", override_dir)
        mgr.generate_argfile()

        mgr.clear_overrides()
        summary = mgr.generate_argfile()

        content = fake_repo["argfile_txt"].read_text()
        assert "legend-engine-xt-mongodb-grammar-4.0.jar" in content
        assert "No overrides active" in summary


class TestMissingCpTxt:
    """Test behavior when cp.txt doesn't exist."""

    def test_raises_when_cp_txt_missing_and_maven_fails(self, fake_repo):
        fake_repo["cp_txt"].unlink()

        mgr = _make_manager(fake_repo)

        with pytest.raises(FileNotFoundError, match="cp.txt still not found"):
            mgr.generate_argfile()
