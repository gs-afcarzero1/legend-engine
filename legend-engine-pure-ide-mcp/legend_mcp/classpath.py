import logging
from pathlib import Path

from legend_mcp.config import LegendMCPSettings

logger = logging.getLogger("pure-ide-mcp")


class ClasspathManager:
    """Manages classpath overrides for the PureIDE Light server."""

    def __init__(self, settings: LegendMCPSettings, cp_txt: Path, argfile_txt: Path,
                 pure_ide_server_dir: Path, repo_root: Path):
        self.settings = settings
        self.cp_txt = cp_txt
        self.argfile_txt = argfile_txt
        self.pure_ide_server_dir = pure_ide_server_dir
        self.repo_root = repo_root
        self.override_map: dict[str, str] = {}  # artifact_id -> abs path to target/classes

    def add_override(self, artifact_id: str, target_classes_path: Path) -> None:
        self.override_map[artifact_id] = str(target_classes_path)

    def clear_overrides(self) -> None:
        self.override_map.clear()

    def generate_argfile(self) -> str:
        """
        Reads cp.txt, filters out any JARs matching overridden artifacts,
        prepends the overridden target/classes to the classpath,
        and writes the new argfile.txt.

        Returns a summary string of the actions taken.
        """
        if not self.cp_txt.exists():
            logger.warning(
                f"cp.txt not found at {self.cp_txt}. "
                "Generating it via mvn dependency:build-classpath..."
            )
            from legend_mcp.maven import MavenManager

            maven = MavenManager(self.settings, self.repo_root)
            rel_module = str(self.pure_ide_server_dir.relative_to(self.repo_root))
            gen_cmd = [
                "dependency:build-classpath",
                f"-Dmdep.outputFile={self.cp_txt}",
                "-pl", rel_module,
            ]
            maven.run_command(gen_cmd)

            if not self.cp_txt.exists():
                raise FileNotFoundError(
                    f"cp.txt still not found at {self.cp_txt}. "
                    "You may need to build the PureIDE server module first: "
                    f"mvn install -DskipTests -pl {rel_module} -am"
                )

        with open(self.cp_txt, "r", encoding="utf-8") as f:
            cp_content = f.read().strip()

        cp_entries = [e for e in cp_content.split(";") if e]
        new_cp_entries = []

        # 1. Add overridden local target/classes first
        for override_path in self.override_map.values():
            new_cp_entries.append(override_path)

        # 2. Add the PureIDE server module's own compiled classes.
        #    cp.txt only lists external dependencies, not the module itself.
        server_classes = self.pure_ide_server_dir / "target" / "classes"
        server_test_classes = self.pure_ide_server_dir / "target" / "test-classes"
        for classes_dir in (server_classes, server_test_classes):
            if classes_dir.exists():
                new_cp_entries.append(str(classes_dir))

        # 3. Add original cp.txt entries, skipping overridden ones
        skipped: list[str] = []
        for entry in cp_entries:
            skip = False
            for artifact_id in self.override_map:
                if f"\\{artifact_id}\\" in entry or f"/{artifact_id}/" in entry:
                    skip = True
                    skipped.append(artifact_id)
                    break
            if not skip:
                new_cp_entries.append(entry)

        # 3. Construct argfile lines
        argfile_lines: list[str] = []
        argfile_lines.extend(self.settings.jvm_extra_args)
        argfile_lines.append(f"-Xmx{self.settings.jvm_max_memory}")
        argfile_lines.append("-cp")
        argfile_lines.append(";".join(new_cp_entries))

        # 4. Write back to argfile.txt
        with open(self.argfile_txt, "w", encoding="utf-8") as f:
            f.write("\n".join(argfile_lines) + "\n")

        summary = "Generated argfile.txt from cp.txt.\n"
        if self.override_map:
            summary += f"Overrides active: {len(self.override_map)}.\n"
            summary += f"Skipped cached JARs for: {', '.join(set(skipped))}."
        else:
            summary += "No overrides active (baseline classpath)."

        return summary
