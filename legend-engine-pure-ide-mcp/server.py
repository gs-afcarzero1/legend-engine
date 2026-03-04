import logging
from pathlib import Path

import httpx
from mcp.server.fastmcp import FastMCP

from legend_mcp.classpath import ClasspathManager
from legend_mcp.config import LegendMCPSettings
from legend_mcp.ide_client import PureIDEClient
from legend_mcp.maven import MavenManager
from legend_mcp.process import PureIDEProcessManager

# ---------------------------------------------------------------------------
# Setup
# ---------------------------------------------------------------------------

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("pure-ide-mcp")

MCP_DIR = Path(__file__).parent.resolve()
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
HTTP_TIMEOUT = 60.0

# ---------------------------------------------------------------------------
# Instances
# ---------------------------------------------------------------------------

mcp = FastMCP("Legend Pure IDE Light")
settings = LegendMCPSettings()

classpath_manager = ClasspathManager(
    settings=settings,
    cp_txt=CP_TXT,
    argfile_txt=ARGFILE_TXT,
    pure_ide_server_dir=PURE_IDE_SERVER_DIR,
    repo_root=REPO_ROOT,
)

maven_manager = MavenManager(settings=settings, repo_root=REPO_ROOT)

process_manager = PureIDEProcessManager(
    classpath_manager=classpath_manager,
    repo_root=REPO_ROOT,
    argfile_txt=ARGFILE_TXT,
    config_file=CONFIG_FILE,
    server_url=SERVER_URL,
    http_timeout=HTTP_TIMEOUT,
)

ide_client = PureIDEClient(
    process_manager=process_manager,
    server_url=SERVER_URL,
    http_timeout=HTTP_TIMEOUT,
)

# ---------------------------------------------------------------------------
# MCP Tools
# ---------------------------------------------------------------------------


@mcp.tool()
async def write_pure_code(code: str, execute: bool = True) -> str:
    """
    Write PURE code to ``welcome.pure``.
    If execute=True, compiles the whole workspace and executes ``go()``.
    If execute=False, only compiles (to check for syntax/compilation errors).

    The code must define a ``function go(): Any[*]`` entry point to be executable.
    The server is **stateful** — each call overwrites ``welcome.pure`` and recompiles.
    Variables use ``$`` prefix (e.g., ``$myVar``), statements end with ``;``.

    Example::

        function go(): Any[*]
        {
            let x = 1;
            let y = 2;
            print($x + $y);
        }
    """
    await process_manager.ensure_running()
    return await ide_client.execute_code(code, execute=execute)


@mcp.tool()
async def run_maven_command(args: list[str]) -> str:
    """
    Run a flexible Maven command. The tool automatically injects
    the configured thread count (``-T``) and ``MAVEN_OPTS`` from settings.

    **Always scope builds with** ``-pl`` to avoid rebuilding the entire repository
    (which takes 15+ minutes). Use ``-am`` (also-make) to include upstream dependencies.

    Args:
        args: Maven CLI arguments, e.g. ``["install", "-DskipTests", "-pl", "path/to/module", "-am"]``

    Full build logs are saved to ``maven_output.log`` in the repo root.
    """
    return maven_manager.run_command(args)


@mcp.tool()
async def patch_classpath(artifact_id: str, module_path: str) -> str:
    """
    Hot-swap a module into the running PureIDE Light server without a full rebuild.

    Replaces a cached ``.m2`` JAR in the classpath with a local ``target/classes`` directory,
    regenerates ``argfile.txt``, and restarts the server. Use after ``run_maven_command``
    to make your compiled changes visible to the Pure compiler.

    **Warning:** The server restarts during patching, which wipes any in-memory state
    (e.g., code written via ``write_pure_code``). Re-submit temporary code after calling this.

    Args:
        artifact_id: The exact Maven artifactId of the module
                     (e.g., ``"legend-engine-xt-nonrelationalStore-mongodb-grammar"``).
        module_path: Relative path from repo root to the module directory
                     (e.g., ``"legend-engine-xts-mongodb/legend-engine-xt-nonrelationalStore-mongodb-grammar"``).
    """
    module_dir = REPO_ROOT / module_path
    target_classes = module_dir / "target" / "classes"

    if not module_dir.is_dir():
        return f"Error: Module directory {module_dir} does not exist."

    classpath_manager.add_override(artifact_id, target_classes)

    try:
        summary = classpath_manager.generate_argfile()
    except Exception as e:
        return f"Failed to generate patched argfile: {str(e)}"

    try:
        process_manager.stop_server()
        await process_manager.start_server()
        await process_manager.wait_for_initialization()
    except Exception as e:
        return (
            f"Argfile patched, but failed to restart PureIDE Light: {str(e)}\n\n"
            f"Argfile generation summary:\n{summary}"
        )

    return (
        f"Success! Classpath overridden for '{artifact_id}' -> '{target_classes}'.\n\n"
        f"Argfile Summary:\n{summary}\n\n"
        f"PureIDE Light Server restarted successfully."
    )


@mcp.tool()
async def reset_classpath() -> str:
    """
    Undo all ``patch_classpath`` overrides and restore the original classpath.

    Regenerates ``argfile.txt`` from the pristine ``cp.txt`` (no overrides)
    and restarts the PureIDE Light server. Use when you want a clean baseline
    or if patched modules cause issues.
    """
    classpath_manager.clear_overrides()

    try:
        summary = classpath_manager.generate_argfile()
    except Exception as e:
        return f"Failed to generate clean argfile: {str(e)}"

    try:
        process_manager.stop_server()
        await process_manager.start_server()
        await process_manager.wait_for_initialization()
    except Exception as e:
        return f"Failed to restart PureIDE Light after resetting classpath: {str(e)}"

    return (
        f"Classpath patches cleared.\n\nArgfile Summary:\n{summary}\n\n"
        f"PureIDE Light restarted with original argfile."
    )


@mcp.tool()
async def list_directory(path: str = "/") -> str:
    """
    Browse the Pure source file tree lazily.

    Start with ``/`` to list top-level repositories (e.g., ``core``, ``core_relational``).
    Then drill down by appending package names (e.g., ``/core/meta/pure/mapping``).
    Each entry is labelled ``[FILE]`` or ``[DIR ]`` with its full path.
    """
    await process_manager.ensure_running()
    return await ide_client.list_directory(path)


@mcp.tool()
async def get_file(file_path: str) -> str:
    """
    Read the full contents of a ``.pure`` file.

    Args:
        file_path: Path within the Pure source tree (e.g., ``/core/meta/pure/metamodel/type.pure``).
                   Obtain paths via ``list_directory`` or ``find_pure_file``.
    """
    await process_manager.ensure_running()
    return await ide_client.get_file(file_path)


@mcp.tool()
async def find_in_sources(
    search_string: str,
    is_regex: bool = False,
    case_sensitive: bool = True,
    limit: int = 50,
) -> str:
    """
    Perform a full-text or regex search across all ``.pure`` sources in the workspace.

    Returns matching files with line numbers, column ranges, and a preview of the matched text.
    Use this to find classes, functions, examples, or any pattern in the Pure codebase.
    """
    await process_manager.ensure_running()
    return await ide_client.find_in_sources(
        search_string, is_regex=is_regex,
        case_sensitive=case_sensitive, limit=limit,
    )


@mcp.tool()
async def find_pure_file(file_name_or_regex: str, is_regex: bool = False) -> str:
    """
    Find ``.pure`` file paths by name or regex pattern.

    Returns newline-separated full paths. Use this before ``get_file`` when you
    know the filename but not its location in the directory tree.
    """
    await process_manager.ensure_running()
    return await ide_client.find_pure_file(file_name_or_regex, is_regex=is_regex)


@mcp.tool()
async def execute_tests(module_path: str = "") -> str:
    """
    Run Maven tests (``mvn test``) for a specific module.

    Args:
        module_path: Relative path from repo root to the module
                     (e.g., ``"legend-engine-xts-mongodb/legend-engine-xt-nonrelationalStore-mongodb-grammar"``).
                     Leave empty to run tests from the repo root (**extremely slow — avoid this**).
    """
    args = ["test"]
    if module_path:
        args.extend(["-pl", module_path])

    return await run_maven_command(args)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main():
    logger.info("Initializing Pure IDE MCP server...")
    mcp.run()


if __name__ == "__main__":
    main()
