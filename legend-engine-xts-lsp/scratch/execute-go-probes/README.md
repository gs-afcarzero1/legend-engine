# Execute Go Probes

Each `.pure` file has one top-level probe function with a unique name. To run one through the VS Code command, rename that function to:

```pure
function go():Any[*]
```

Keep only one `go()` function active in the workspace at a time.
