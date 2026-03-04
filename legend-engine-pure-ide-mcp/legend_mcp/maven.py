import logging
import os
import subprocess
from pathlib import Path

from legend_mcp.config import LegendMCPSettings

logger = logging.getLogger("pure-ide-mcp")


class MavenManager:
    """Runs Maven commands with settings-driven thread count and MAVEN_OPTS."""

    def __init__(self, settings: LegendMCPSettings, repo_root: Path):
        self.settings = settings
        self.repo_root = repo_root

    def run_command(self, args: list[str]) -> str:
        """
        Runs a maven command with configured thread count and MAVEN_OPTS.

        Args:
            args: e.g. ["install", "-DskipTests", "-pl", "some-module", "-am"]
        """
        if "-T" not in args:
            args = ["-T", str(self.settings.build_threads)] + args

        cmd = ["mvn.cmd" if os.name == "nt" else "mvn"] + args

        env = os.environ.copy()
        if self.settings.maven_opts:
            env["MAVEN_OPTS"] = self.settings.maven_opts

        logger.info(f"Running Maven: {' '.join(cmd)}")

        try:
            result = subprocess.run(
                cmd,
                cwd=str(self.repo_root),
                capture_output=True,
                text=True,
                env=env,
            )

            output = f"Maven Command: {' '.join(cmd)}\nExit Code: {result.returncode}\n"

            # Save the full output to a file so agents can read it if needed
            log_file_path = self.repo_root / "maven_output.log"
            with open(log_file_path, "w", encoding="utf-8") as log_file:
                log_file.write(f"STDOUT:\n{result.stdout}\n\nSTDERR:\n{result.stderr}")

            output += f"Full build logs saved to: {log_file_path.as_posix()}\n"

            if result.returncode == 0:
                output += "Build Successful."
                output += "\nTail of stdout:\n" + "\n".join(
                    result.stdout.strip().split("\n")[-20:]
                )
            else:
                output += "Build Failed. Please read the full log file for details.\n"
                output += "\nTail of stdout:\n" + "\n".join(
                    result.stdout.strip().split("\n")[-50:]
                )

            return output
        except Exception as e:
            return f"Failed to run Maven: {e}"
