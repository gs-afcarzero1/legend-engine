"""PureIDE Light HTTP client.

Encapsulates all HTTP communication with the PureIDE Light server.
Follows an optimistic execution pattern: try the operation first,
and only diagnose server health on failure.
"""

import json
import logging

import httpx

from legend_mcp.process import PureIDEProcessManager

logger = logging.getLogger("pure-ide-mcp")


class PureIDEClient:
    """High-level async client for the PureIDE Light HTTP API.

    Uses ``PureIDEProcessManager`` only for health diagnostics — it never
    proactively restarts the server.
    """

    def __init__(
        self,
        process_manager: PureIDEProcessManager,
        server_url: str = "http://localhost:9010",
        http_timeout: float = 60.0,
    ):
        self._pm = process_manager
        self._url = server_url
        self._timeout = http_timeout
        self._client = httpx.AsyncClient(timeout=http_timeout)

    async def _diagnose_failure(self, exc: Exception) -> str:
        """Build a descriptive error message after an HTTP failure."""
        healthy = await self._pm.is_healthy()
        if not healthy:
            return (
                f"PureIDE Light server is not reachable. "
                f"Original error: {exc}. "
                f"The server may need to be restarted via patch_classpath or reset_classpath."
            )
        return f"Unexpected error while communicating with PureIDE Light: {exc}"

    async def execute_code(self, code: str, execute: bool = True) -> str:
        """Write Pure code and optionally execute it. Returns the raw response."""
        payload = {
            "openFiles": [{"path": "/welcome.pure", "code": code}],
            "extraParams": {},
        }
        endpoint = "/executeGo" if execute else "/executeSaveAndReset"

        try:
            resp = await self._client.post(
                f"{self._url}{endpoint}", json=payload, timeout=self._timeout
            )
        except httpx.RequestError as exc:
            return await self._diagnose_failure(exc)

        content_type = resp.headers.get("content-type", "")
        if "application/json" in content_type:
            try:
                data = resp.json()
                if isinstance(data, dict) and data.get("error") is True:
                    err_text = data.get("text", "Unknown error")
                    src_info = data.get("sourceInformation", {})
                    if src_info:
                        err_text += (
                            f"\nAt Line {src_info.get('line')}, "
                            f"Column {src_info.get('column')}"
                        )
                    return f"Compilation/Execution Error:\n{err_text}"
                return json.dumps(data, indent=2)
            except Exception:
                return resp.text
        return resp.text

    async def list_directory(self, path: str = "/") -> str:
        """List entries in a Pure source directory. Returns formatted text."""
        try:
            resp = await self._client.get(
                f"{self._url}/dir", params={"parameters": path}
            )
        except httpx.RequestError as exc:
            return await self._diagnose_failure(exc)

        if resp.status_code != 200:
            return f"Error: HTTP {resp.status_code} - {resp.text}"

        nodes = resp.json()
        lines = [f"Contents of {path}:"]
        for node in nodes:
            attrs = node.get("li_attr", {})
            is_file = attrs.get("file", False)
            item_path = attrs.get("path", node.get("text"))
            type_str = "FILE" if is_file else "DIR "
            lines.append(f"[{type_str}] {node.get('text')}  (path: {item_path})")
        return "\n".join(lines)

    async def get_file(self, file_path: str) -> str:
        """Read the content of a ``.pure`` file."""
        clean_path = file_path.lstrip("/")
        try:
            resp = await self._client.get(f"{self._url}/fileAsJson/{clean_path}")
        except httpx.RequestError as exc:
            return await self._diagnose_failure(exc)

        if resp.status_code != 200:
            return f"Error: HTTP {resp.status_code} - {resp.text}"
        return resp.json().get("content", "")

    async def find_in_sources(
        self,
        search_string: str,
        is_regex: bool = False,
        case_sensitive: bool = True,
        limit: int = 50,
    ) -> str:
        """Full-text search across all Pure sources. Returns formatted text."""
        params = {
            "string": search_string,
            "regex": str(is_regex).lower(),
            "caseSensitive": str(case_sensitive).lower(),
            "limit": limit,
        }
        try:
            resp = await self._client.get(
                f"{self._url}/findInSources", params=params
            )
        except httpx.RequestError as exc:
            return await self._diagnose_failure(exc)

        if resp.status_code != 200:
            return f"Error: HTTP {resp.status_code} - {resp.text}"

        results = resp.json()
        if not results:
            return "No matches found."

        out: list[str] = []
        for file_match in results:
            source_id = file_match.get("sourceId")
            coords = file_match.get("coordinates", [])
            out.append(f"File: {source_id} ({len(coords)} matches)")
            for idx, c in enumerate(coords):
                preview = c.get("preview", {})
                out.append(
                    f"  [{idx+1}] Line {c.get('startLine')}:{c.get('startColumn')} "
                    f"to {c.get('endLine')}:{c.get('endColumn')}"
                )
                if preview:
                    found_text = preview.get("found", "")
                    out.append(f"      Match content: `{found_text.strip()}`")
        return "\n".join(out)

    async def find_pure_file(
        self, file_name_or_regex: str, is_regex: bool = False
    ) -> str:
        """Find ``.pure`` file paths by name or regex. Returns newline-separated paths."""
        params = {"file": file_name_or_regex, "regex": str(is_regex).lower()}
        try:
            resp = await self._client.get(
                f"{self._url}/findPureFiles", params=params
            )
        except httpx.RequestError as exc:
            return await self._diagnose_failure(exc)

        if resp.status_code != 200:
            return f"Error: HTTP {resp.status_code} - {resp.text}"

        files = resp.json()
        if not files:
            return "No files matched."
        return "\n".join(files)
