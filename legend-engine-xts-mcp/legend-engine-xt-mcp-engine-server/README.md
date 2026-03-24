# Legend Engine MCP Bootstrap Server

This module implements an [MCP](https://modelcontextprotocol.io/) (Model Context Protocol) server that exposes Legend Engine capabilities — compilation, plan generation, Pure execution, and grammar conversion — as tools that AI assistants can call over stdio.

## Architecture Overview

The server is split into two classloader layers running inside a single JVM process:

```
┌─────────────────────────────────────────────────────────────────┐
│  Outer Bootstrap JVM                                            │
│                                                                 │
│  LegendEngineMcpStdioServer                                     │
│  ─────────────────────────────────────────────────────────────  │
│  • Loaded from: outer JVM classpath (target/classes only)       │
│  • Reads JSON-RPC from stdin, writes JSON-RPC to stdout         │
│  • Owns all classpath management, rebuild, and reload logic     │
│  • Loaded once, cannot be reloaded                              │
│                                                                 │
│         ┌───────────────────────────────────────────────┐       │
│         │  Child URLClassLoader                         │       │
│         │                                               │       │
│         │  LegendEngineMcpToolDefinitions               │       │
│         │  ───────────────────────────────────────────  │       │
│         │  • Loaded from: in-memory classpath entries    │       │
│         │  • Full Legend Engine dependency graph          │       │
│         │  • Compile, plan generation, Pure execution    │       │
│         │  • DISPOSABLE — discarded and recreated on     │       │
│         │    each reload with updated URLs               │       │
│         └───────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────┘
```

### Why two layers?

The JVM does not allow classes to be reloaded once they are loaded by the system classloader. To support hot-reloading of Legend Engine modules during development, the engine classes are loaded into a separate `URLClassLoader` that can be thrown away and rebuilt:

1. Developer edits a module and calls `rebuild` to compile it
2. The `target/classes` directory is tracked as an overlay
3. On `reload`, the old child classloader is discarded
4. A new `URLClassLoader` is created with the same jar entries, but with the rebuilt module's `target/classes` substituted for its `.m2` jar
5. `LegendEngineMcpToolDefinitions` is loaded fresh into the new classloader

This means engine changes take effect without restarting the JVM or the MCP session.

A useful side effect is that the bootstrap tools (rebuild, reload, status, etc.) remain operational even when the engine itself fails to load — the outer layer catches failures and preserves the last working runtime.

## How It Works

### Startup sequence

```
java -cp <bootstrap-cp> org.finos.legend.engine.mcp.engine.LegendEngineMcpStdioServer [projectRoot] [profile]
```

1. **stdout/stderr redirect**: `System.out` is redirected to stderr. The original stdout is reserved exclusively for the MCP JSON-RPC protocol, preventing library code from contaminating the protocol channel.

2. **Project root discovery**: If not provided as an argument, the server walks up from the current working directory to find the nearest `pom.xml`.

3. **Classpath generation**: The server runs Maven (`dependency:build-classpath`) against two modules — the donor profile module and the bootstrap support module — using temporary files in the system temp directory. The resolved classpath entries are merged, deduplicated, and held in memory. No persistent files are written to the project directory.

4. **Child classloader creation**: A `URLClassLoader` is built from the in-memory classpath entries. The bootstrap support module's `target/classes` is appended so the child loader can see `LegendEngineMcpToolDefinitions` without requiring a JAR install.

5. **Engine initialization**: `LegendEngineMcpToolDefinitions.initialize()` is invoked reflectively through the child classloader. This sets up the `ModelManager`, `ObjectMapper`, and MCP protocol orchestrator.

6. **stdio loop**: The server reads JSON-RPC lines from stdin, dispatches to either bootstrap handlers or the child engine, and writes responses to stdout.

### Request routing

When a JSON-RPC tool call arrives:

```
stdin  →  handleLine()  →  is it a bootstrap tool?
                                ├── yes → handle directly (rebuild, reload, etc.)
                                └── no  → invokeEngine() → child classloader
                                             └── LegendEngineMcpToolDefinitions.handleJsonRpc()
                                                     └── MCP orchestrator dispatch
```

Bootstrap tools are always handled by the outer server, even if the child engine is unavailable.

Engine tools (`compile`, `generate_plan`, `execute_pure`, `grammar_to_json`, `json_to_grammar`) are forwarded to the child classloader via reflection.

### In-memory classpath

The child classpath is held entirely in memory as a `List<String>` of jar paths. Nothing is written to the project directory. The classpath is regenerated when:

- The server starts with an empty cache (always on first boot)
- The active profile changes (e.g., `base` → `server`)
- The donor module changes

Maven's `dependency:build-classpath` plugin still requires a file to write to, so temporary files in the system temp directory (`/tmp` on Linux) are used during generation and deleted immediately after reading.

## Tools

### Engine tools (child classloader)

| Tool | Description |
|------|-------------|
| `compile` | Parse and compile Pure grammar text into a `PureModel`. The compiled model is stored in session for use by `generate_plan`. |
| `generate_plan` | Generate an execution plan from a lambda, mapping, and runtime. Requires a prior `compile` call. Returns plan JSON including SQL. |
| `execute_pure` | Execute arbitrary Pure code via the interpreted engine. First call initializes the interpreter (~10-30s). Subsequent calls are fast. Independent of `compile`/`generate_plan`. |
| `grammar_to_json` | Parse Pure grammar text into PureModelContextData JSON. |
| `json_to_grammar` | Convert PureModelContextData JSON back to Pure grammar text. |

### Bootstrap tools (outer server)

| Tool | Description |
|------|-------------|
| `rebuild` | Compile a Maven module and track its `target/classes` as an overlay for the next reload. Accepts a `scope` argument. |
| `reload` | Replace the child classloader with a new one, applying any configured overlays. Atomic — preserves the previous runtime on failure. |
| `classpath_status` | Report full bootstrap state: profile, donor module, overlay status, engine availability. |
| `affected_modules` | Resolve the local reactor modules affected by a given module and scope. Shows direction and child-classpath membership. |
| `module_status` | Report whether a module is on the child classpath, its base jar entry, and overlay state. |
| `set_classpath_profile` | Switch the donor profile, regenerate the classpath, and reload atomically. |
| `restore_space` | Reset to default `base` profile with no overlays. Atomic. |

## Child Classpath Profiles

The child classpath is generated from a named donor module's Maven dependency tree.

| Profile | Donor module | Use case |
|---------|-------------|----------|
| `base` (default) | `legend-engine-config/legend-engine-extensions-collection-generation` | Smaller extension set, covers generation-focused workflows |
| `server` | `legend-engine-config/legend-engine-server/legend-engine-server-http-server` | Full server classpath including HTTP server dependencies |

The child classpath is the deduplicated union of:
- The donor module's runtime-scoped dependencies
- The bootstrap support module's runtime-scoped dependencies
- The bootstrap module's `target/classes` (appended at classloader creation time)

## Overlay Model

The default runtime state uses installed `.m2` artifacts. Overlays selectively replace jar entries with local `target/classes` directories from rebuilt modules.

The overlay lifecycle:

1. **Rebuild**: `rebuild` compiles a module (and optionally its upstream/downstream dependencies). Every resolved module that produced `target/classes` is tracked as a *configured* overlay.
2. **Reload**: `reload` creates a new child classloader. For each classpath entry, if a configured overlay exists for that artifact, the `target/classes` directory is used instead of the `.m2` jar.
3. **Activation**: On successful reload, configured overlays become *active* overlays.

This is intentional — the server does not automatically switch every workspace module to `target/classes`. The default behavior prioritizes stability (installed jars) with explicit opt-in for local changes.

## Dependency-Aware Rebuilds

`rebuild` accepts an optional `scope` argument that maps to Maven reactor flags:

| Scope | Maven flags | When to use |
|-------|------------|-------------|
| `self` (default) | `-pl <module>` | Leaf Java changes |
| `upstream` | `-pl <module> -am` | Module needs local prerequisites rebuilt |
| `downstream` | `-pl <module> -amd` | Change invalidates dependent modules or generated outputs |
| `closure` | `-pl <module> -am -amd` | Safest coherent reactor slice without a full install |

### Developer cookbook

1. Start with `module_status` for the module you changed.
2. Use `affected_modules` if you are not sure whether `self` is enough.
3. Use `self` for leaf Java changes.
4. Use `upstream` when the module needs local prerequisites rebuilt.
5. Use `downstream` when the change can invalidate dependent modules or generated outputs.
6. Use `closure` for the safest coherent reactor slice without a full install.
7. After a successful rebuild, call `reload`, then validate with MCP tools or targeted tests.

## Configuration

### Startup arguments

```
java -cp <bootstrap-cp> org.finos.legend.engine.mcp.engine.LegendEngineMcpStdioServer [projectRoot] [profile]
```

| Argument | Default | Description |
|----------|---------|-------------|
| `projectRoot` | Auto-detected from cwd | Path to the repository root (must contain `pom.xml`) |
| `profile` | `base` | Classpath donor profile (`base` or `server`) |

In local development, `<bootstrap-cp>` should be `legend-engine-xts-mcp/legend-engine-xt-mcp-engine-server/target/classes`.

### MCP client configuration (`.mcp.json`)

```json
{
  "mcpServers": {
    "legend-engine": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-cp",
        "legend-engine-xts-mcp/legend-engine-xt-mcp-engine-server/target/classes",
        "org.finos.legend.engine.mcp.engine.LegendEngineMcpStdioServer"
      ],
      "cwd": "/path/to/legend-engine"
    }
  }
}
```

### Maven flags

Extra Maven flags can be passed through the environment variable `LEGEND_ENGINE_MCP_MAVEN_ARGS`. This supports:

- Custom local repository: `LEGEND_ENGINE_MCP_MAVEN_ARGS="-Dmaven.repo.local=.m2/repository"`
- Offline mode: `LEGEND_ENGINE_MCP_MAVEN_ARGS="-o"`
- Additional settings: `LEGEND_ENGINE_MCP_MAVEN_ARGS="-s custom-settings.xml"`

### Logging

The server writes diagnostic logs to `mcp_server.log` in the project root. Bootstrap diagnostics are also written to stderr (visible in MCP client debug output, prefixed with `[mcp-bootstrap]`).

## Hot Reload Boundaries

Hot reload applies to classes loaded by the child classloader:
- Engine/tool implementation from the generated child classpath
- Modules explicitly rebuilt and overlaid via `target/classes`
- Generated Pure artifacts (PAR files, metadata, compiled Java) in overlaid `target/classes`

Hot reload does **not** apply to:
- `LegendEngineMcpStdioServer` itself (requires MCP process restart)
- The outer bootstrap launch classpath

## Pure Build Artifacts

Some modules write important Pure build outputs into `target/classes` during `compile`:

- PAR-producing modules generate `pure-*.par`, `*.definition.json`, `.pure` resources, and service descriptors
- Compiled Pure-Java modules generate metadata bins/indexes and Java classes
- The runtime discovers these through the classloader, metadata loading, and `CodeRepositoryProvider` discovery

This matters for hot reload: overlaying `target/classes` is not just about handwritten Java classes. A targeted rebuild may need to include upstream or downstream modules whose generated Pure artifacts changed.

## Example: Compile and Generate Plan

```
1. Call classpath_status → confirm "Engine availability: available"
2. Call compile with Pure model (Class, Database, Mapping, Runtime)
3. Call generate_plan with lambda, mapping path, and runtime path
```

Using this Pure model:

```pure
Class my::Person
{
  name: String[1];
  age: Integer[1];
}

###Relational
Database my::PersonDB
(
  Table personTable
  (
    name VARCHAR(200) PRIMARY KEY,
    age INTEGER
  )
)

###Mapping
Mapping my::PersonMapping
(
  my::Person: Relational
  {
    ~primaryKey
    (
      [my::PersonDB]personTable.name
    )
    ~mainTable [my::PersonDB]personTable
    name: [my::PersonDB]personTable.name,
    age: [my::PersonDB]personTable.age
  }
)

###Runtime
Runtime my::PersonRuntime
{
  mappings:
  [
    my::PersonMapping
  ];
  connections:
  [
    my::PersonDB:
    [
      connection_1:
      #{
        RelationalDatabaseConnection
        {
          store: my::PersonDB;
          type: H2;
          specification: LocalH2{};
          auth: DefaultH2{};
        }
      }#
    ]
  ];
}
```

Then:

```
compile(code: <above>)       → "Compilation successful."
generate_plan(
  lambda:  "|my::Person.all()",
  mapping: "my::PersonMapping",
  runtime: "my::PersonRuntime"
)                             → Plan JSON with SQL selecting from personTable
```

This generates a plan only. It does not execute the SQL against H2.
