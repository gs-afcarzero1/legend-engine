import asyncio
import logging
import subprocess
import time
from pathlib import Path

import httpx

from legend_mcp.classpath import ClasspathManager

logger = logging.getLogger("pure-ide-mcp")


class PureIDEProcessManager:
    """Manages the lifecycle of the PureIDE Light Java process."""

    def __init__(
        self,
        classpath_manager: ClasspathManager,
        repo_root: Path,
        argfile_txt: Path,
        config_file: Path,
        server_url: str = "http://localhost:9010",
        http_timeout: float = 60.0,
    ):
        self.classpath_manager = classpath_manager
        self.repo_root = repo_root
        self.argfile_txt = argfile_txt
        self.config_file = config_file
        self.server_url = server_url
        self.http_timeout = http_timeout

        self.process = None
        self.client = httpx.AsyncClient(timeout=http_timeout)
        self._lock = asyncio.Lock()

    async def ensure_running(self) -> None:
        """Ensure the PureIDE Light server is running and initialized."""
        async with self._lock:
            if await self.is_healthy():
                return

            logger.info("Starting PureIDE Light server...")
            await self.start_server()
            await self.wait_for_initialization()

    async def is_healthy(self) -> bool:
        """Check if the HTTP server is responsive."""
        if self.process and self.process.poll() is not None:
            self.process = None
            return False

        try:
            resp = await self.client.get(
                f"{self.server_url}/conceptsActivity", timeout=2.0
            )
            return resp.status_code == 200
        except httpx.RequestError:
            return False

    async def start_server(self) -> None:
        """Start the PureIDE Light Java process."""
        self.stop_server()

        if not self.argfile_txt.exists():
            logger.info("argfile.txt missing, generating baseline...")
            self.classpath_manager.generate_argfile()

        try:
            cmd = [
                "java",
                f"@{self.argfile_txt.relative_to(self.repo_root)}",
                "org.finos.legend.engine.ide.PureIDELight",
                "server",
                str(self.config_file.relative_to(self.repo_root)),
            ]

            logger.info(f"Running command: {' '.join(cmd)}")
            self.process = subprocess.Popen(
                cmd,
                cwd=str(self.repo_root),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )

            asyncio.create_task(self._consume_output(self.process))

        except Exception as e:
            logger.error(f"Failed to start PureIDE Light: {e}")
            raise

    async def _consume_output(self, proc) -> None:
        """Pipe output to avoid blocking buffer."""
        loop = asyncio.get_running_loop()
        while True:
            line = await loop.run_in_executor(None, proc.stdout.readline)
            if not line:
                break

    def stop_server(self) -> None:
        """Kill the PureIDE Light Java process if running."""
        if self.process:
            logger.info("Stopping PureIDE Light server...")
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.process.kill()
            self.process = None

    async def wait_for_initialization(self, timeout_sec: int = 300) -> None:
        """Poll the server until it finishes interpreting the pure runtime."""
        logger.info("Waiting for PureIDE Light to fully initialize...")
        start_time = time.time()

        while time.time() - start_time < timeout_sec:
            if self.process and self.process.poll() is not None:
                raise RuntimeError(
                    f"PureIDE Light process died during initialization. "
                    f"Exit code: {self.process.returncode}"
                )

            try:
                resp = await self.client.get(
                    f"{self.server_url}/conceptsActivity", timeout=2.0
                )
                if resp.status_code == 200:
                    data = resp.json()
                    if not data.get("initializing", True):
                        logger.info("Server is initialized.")
                        return
                    else:
                        logger.info(f"Still initializing: {data.get('text', '')}")
            except httpx.RequestError:
                pass

            await asyncio.sleep(2)

        raise TimeoutError("PureIDE Light failed to initialize in time (300s).")
