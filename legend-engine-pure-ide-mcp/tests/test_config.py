"""Tests for legend_mcp.config – LegendMCPSettings."""

import json
import os
from pathlib import Path

import pytest

from legend_mcp.config import LegendMCPSettings

# All LEGEND_ env vars that could leak from the host environment
_LEGEND_ENV_VARS = [
    "LEGEND_MAVEN_OPTS",
    "LEGEND_BUILD_THREADS",
    "LEGEND_JVM_MAX_MEMORY",
    "LEGEND_JVM_EXTRA_ARGS",
]


@pytest.fixture(autouse=True)
def _clean_legend_env(monkeypatch):
    """Remove any LEGEND_* env vars so tests start from a clean slate."""
    for var in _LEGEND_ENV_VARS:
        monkeypatch.delenv(var, raising=False)


class TestDefaults:
    """Settings should work with no JSON file and no env vars."""

    def test_default_build_threads(self):
        s = LegendMCPSettings()
        assert s.build_threads == 1

    def test_default_maven_opts(self):
        s = LegendMCPSettings()
        assert s.maven_opts == ""

    def test_default_jvm_max_memory(self):
        s = LegendMCPSettings()
        assert s.jvm_max_memory == "4G"

    def test_default_jvm_extra_args(self):
        s = LegendMCPSettings()
        assert s.jvm_extra_args == ["-Dmongo_pwd=legendpass"]


class TestEnvOverride:
    """Environment variables prefixed with LEGEND_ should override defaults."""

    def test_build_threads_from_env(self, monkeypatch):
        monkeypatch.setenv("LEGEND_BUILD_THREADS", "8")
        s = LegendMCPSettings()
        assert s.build_threads == 8

    def test_maven_opts_from_env(self, monkeypatch):
        monkeypatch.setenv("LEGEND_MAVEN_OPTS", "-Xmx32g")
        s = LegendMCPSettings()
        assert s.maven_opts == "-Xmx32g"

    def test_jvm_max_memory_from_env(self, monkeypatch):
        monkeypatch.setenv("LEGEND_JVM_MAX_MEMORY", "16G")
        s = LegendMCPSettings()
        assert s.jvm_max_memory == "16G"

    def test_jvm_extra_args_from_env(self, monkeypatch):
        monkeypatch.setenv("LEGEND_JVM_EXTRA_ARGS", '["--add-opens=java.base/java.lang=ALL-UNNAMED"]')
        s = LegendMCPSettings()
        assert s.jvm_extra_args == ["--add-opens=java.base/java.lang=ALL-UNNAMED"]


class TestJsonFile:
    """Settings from a legend-dev.json file should be read."""

    def test_json_file_loads(self, tmp_path, monkeypatch):
        config_file = tmp_path / "legend-dev.json"
        config_file.write_text(json.dumps({"build_threads": 4, "jvm_max_memory": "12G"}))

        # Patch the module-level _JSON_CONFIG to point to our tmp file
        import legend_mcp.config as config_mod
        monkeypatch.setattr(config_mod, "_JSON_CONFIG", config_file)

        s = LegendMCPSettings()
        assert s.build_threads == 4
        assert s.jvm_max_memory == "12G"

    def test_partial_json_preserves_defaults(self, tmp_path, monkeypatch):
        config_file = tmp_path / "legend-dev.json"
        config_file.write_text(json.dumps({"build_threads": 6}))

        import legend_mcp.config as config_mod
        monkeypatch.setattr(config_mod, "_JSON_CONFIG", config_file)

        s = LegendMCPSettings()
        assert s.build_threads == 6
        assert s.maven_opts == ""  # still default
        assert s.jvm_max_memory == "4G"  # still default


class TestEnvWinsOverJson:
    """Env vars should take priority over JSON file values."""

    def test_env_overrides_json(self, tmp_path, monkeypatch):
        config_file = tmp_path / "legend-dev.json"
        config_file.write_text(json.dumps({"build_threads": 4}))

        import legend_mcp.config as config_mod
        monkeypatch.setattr(config_mod, "_JSON_CONFIG", config_file)

        monkeypatch.setenv("LEGEND_BUILD_THREADS", "16")
        s = LegendMCPSettings()
        assert s.build_threads == 16  # env wins
