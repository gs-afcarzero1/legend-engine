from pathlib import Path
from typing import Tuple, Type

from pydantic import Field
from pydantic_settings import BaseSettings, PydanticBaseSettingsSource, SettingsConfigDict
from pydantic_settings.main import JsonConfigSettingsSource

_MCP_DIR = Path(__file__).parent.parent.resolve()
_JSON_CONFIG = _MCP_DIR / "legend-dev.json"


class LegendMCPSettings(BaseSettings):
    """
    Configuration for the Legend Pure IDE MCP server.

    Values are resolved in priority order:
      1. Environment variables (prefixed with LEGEND_, e.g. LEGEND_BUILD_THREADS=4)
      2. A ``legend-dev.json`` file in the MCP directory (if present)
      3. Defaults defined below
    """

    # -- Maven -----------------------------------------------------------------
    maven_opts: str = Field(
        default="",
        description="Value forwarded as the MAVEN_OPTS environment variable.",
    )
    build_threads: int = Field(
        default=1,
        description="Number of threads passed to Maven via the -T flag.",
    )

    # -- JVM (PureIDE Light process) -------------------------------------------
    jvm_max_memory: str = Field(
        default="4G",
        description="Max heap size for the PureIDE Light JVM (e.g. '8G').",
    )
    jvm_extra_args: list[str] = Field(
        default=["-Dmongo_pwd=legendpass"],
        description="Additional JVM arguments appended to argfile.txt.",
    )

    model_config = SettingsConfigDict(
        env_prefix="LEGEND_",
    )

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: Type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> Tuple[PydanticBaseSettingsSource, ...]:
        # Priority: init > env vars > JSON file > defaults
        return (
            init_settings,
            env_settings,
            JsonConfigSettingsSource(settings_cls, json_file=_JSON_CONFIG),
            file_secret_settings,
        )
