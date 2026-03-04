# Python MCP Server for PureIDE Light

A robust Model Context Protocol (MCP) server for the Legend Engine Pure IDE Light. This server wraps the Pure IDE HTTP endpoints, exposing them to AI agents to read, write, and execute PURE code directly in the workspace. It also provides a powerful module installation and hot-swapping mechanism.

## Prerequisites

- **Python 3.10+** (managed via `uv`)
- **Java 11+**
- **Maven**
- **Legend Engine cloned and built**

## Installation

We use `uv` for lightning-fast project management.

1.  Navigate to this project directory:
    ```bash
    cd legend-engine-pure-ide-mcp
    ```

2.  Sync the project dependencies and create the virtual environment:
    ```bash
    uv sync
    ```

## Configuration

All configuration is managed through a validated [Pydantic Settings](https://docs.pydantic.dev/latest/concepts/pydantic_settings/) model defined in `legend_mcp/config.py`.

Values are resolved in priority order (highest wins):

1. **Environment variables** — prefixed with `LEGEND_` (e.g., `LEGEND_BUILD_THREADS=4`)
2. **`legend-dev.json`** — a JSON file placed in this directory (optional)
3. **Defaults** — sensible values built into the model

| Setting | Env Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `maven_opts` | `LEGEND_MAVEN_OPTS` | `""` | Forwarded as the `MAVEN_OPTS` env variable to Maven. |
| `build_threads` | `LEGEND_BUILD_THREADS` | `1` | Thread count passed to Maven via `-T`. |
| `jvm_max_memory` | `LEGEND_JVM_MAX_MEMORY` | `4G` | Max heap size for the PureIDE Light JVM. |
| `jvm_extra_args` | `LEGEND_JVM_EXTRA_ARGS` | `["-Dmongo_pwd=legendpass"]` | Additional JVM arguments appended to `argfile.txt`. |

**Example `legend-dev.json`:**
```json
{
  "maven_opts": "-Xmx16g",
  "build_threads": 4,
  "jvm_max_memory": "8G"
}
```

## Running the Server

The server is started via `uv`:

```bash
uv --directory /path/to/legend-engine/legend-engine-pure-ide-mcp run server.py
```

Most MCP-compatible IDEs and agents expect a JSON configuration block that describes how to launch the server. The exact location and format of this config file depends on the IDE or agent you are using — refer to its documentation. The server entry will generally look like this:

```json
{
  "legend-pure-ide": {
    "command": "uv",
    "args": [
      "--directory",
      "/path/to/legend-engine/legend-engine-pure-ide-mcp",
      "run",
      "server.py"
    ]
  }
}
```

## Features

### PureIDE Integration
The server automatically starts the PureIDE Light Java process in the background on the first tool call. It manages the process lifecycle, ensuring the IDE is healthy and ready.

### Tools Available via MCP

| Tool Name | Description |
| :--- | :--- |
| **`write_pure_code(code, execute)`** | Writes PURE code to `welcome.pure`. If `execute=True`, compiles and executes the `go()` block. If `False`, it compiles and checks for errors. |
| **`list_directory(path)`** | Navigates the Legend workspace file structure lazily. Start with `/`. |
| **`get_file(file_path)`** | Retrieves the content of a specific `.pure` file. |
| **`find_in_sources(...)`** | Performs full-text or regex searches across the entire PURE codebase, returning lines and coordinates. |
| **`find_pure_file(...)`** | Finds paths for specific `.pure` files using exact names or regex. |
| **`run_maven_command(args)`** | Runs a flexible `mvn` command. Automatically injects the configured thread count (`-T`) and `MAVEN_OPTS` from settings. |
| **`patch_classpath(artifact_id, module_path)`** | **Core Feature:** Registers a JAR to be overridden by a local `target/classes` directory, regenerates a pristine `argfile.txt` from the baseline `cp.txt`, and restarts the server. Build using `run_maven_command` first. |
| **`reset_classpath()`** | Clears all overrides, restores the original `argfile.txt` from `cp.txt`, and restarts the server. |

## Why Classpath Patching?
Rebuilding the entire Legend Engine takes 10+ minutes. If an agent is developing an integration grammar or store module (e.g., MongoDB), you only want to compile that module and have the IDE recognize it.

Classpath patching was introduced to provide a robust, automated workflow for AI agents. This capability enables agents to efficiently iterate on specific modules without incurring the overhead of a full repository rebuild:

1. `run_maven_command(["install", "-DskipTests", "-pl", "legend-engine-...", "-am"])`
2. `patch_classpath("legend-engine-...", "legend-engine-...")`

This dynamically regenerates `argfile.txt` from the baseline `cp.txt`, prioritizing your custom built module so changes take effect instantly.

## Project Structure

```
legend-engine-pure-ide-mcp/
├── server.py               # Entrypoint — wires up managers and registers MCP tools
├── pyproject.toml           # Project config, dependencies, pytest settings
├── README.md
├── SKILL.md
├── legend_mcp/              # Core package
│   ├── __init__.py
│   ├── config.py            # LegendMCPSettings (Pydantic)
│   ├── classpath.py         # ClasspathManager
│   ├── maven.py             # MavenManager
│   └── process.py           # PureIDEProcessManager
└── tests/                   # Pytest test suite
    ├── __init__.py
    └── test_config.py       # Config loading, env override, JSON merge tests
```

## Running Tests

```bash
uv run pytest tests/ -v
```
