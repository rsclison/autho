# Autho Local Shell MCP Server

This MCP (Model Context Protocol) server allows Claude Code to execute commands in your local environment, particularly useful for running Leiningen tests and other build commands.

## Installation

### 1. Install Python Dependencies

```bash
# From the project root
cd .claude/mcp
pip install -r requirements.txt
```

Or install globally:
```bash
pip install mcp
```

### 2. Configure Claude Desktop

Add this server to your Claude Desktop configuration file:

**macOS/Linux:** `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

Add the following to the `mcpServers` section:

```json
{
  "mcpServers": {
    "autho-local-shell": {
      "command": "python3",
      "args": [
        "/ABSOLUTE/PATH/TO/autho/.claude/mcp/local-shell-server.py"
      ]
    }
  }
}
```

**Important:** Replace `/ABSOLUTE/PATH/TO/autho` with the actual absolute path to your autho project directory.

### Example Complete Configuration

```json
{
  "mcpServers": {
    "autho-local-shell": {
      "command": "python3",
      "args": [
        "/Users/yourname/projects/autho/.claude/mcp/local-shell-server.py"
      ]
    }
  }
}
```

### 3. Restart Claude Desktop

After adding the configuration, restart Claude Desktop for the changes to take effect.

## Available Tools

### 1. `run_lein_test`

Run Leiningen tests in the autho project.

**Parameters:**
- `test_namespace` (optional): Specific test namespace to run (e.g., `autho.kafka-pip-test`)

**Examples:**
- Run all tests: `run_lein_test()`
- Run specific namespace: `run_lein_test(test_namespace="autho.kafka-pip-test")`

### 2. `run_shell_command`

Execute any shell command in the autho project directory.

**Parameters:**
- `command` (required): The shell command to execute
- `timeout` (optional): Timeout in seconds (default: 300)

**Examples:**
- `run_shell_command(command="./lein deps")`
- `run_shell_command(command="git status")`
- `run_shell_command(command="./lein uberjar", timeout=600)`

## Troubleshooting

### MCP package not found

If you get an error about the `mcp` package:
```bash
pip install mcp
```

### Server not showing up in Claude Desktop

1. Check that the path in `claude_desktop_config.json` is absolute (not relative)
2. Ensure the Python script is executable: `chmod +x local-shell-server.py`
3. Verify Python 3 is available: `which python3`
4. Restart Claude Desktop completely

### Permission denied

Make sure the script is executable:
```bash
chmod +x .claude/mcp/local-shell-server.py
```

## Testing the Server

You can test the server manually:

```bash
cd .claude/mcp
python3 local-shell-server.py
```

The server should start without errors and wait for input (it communicates via stdio).

## How It Works

This MCP server:
1. Runs as a subprocess of Claude Desktop
2. Communicates with Claude via stdio (standard input/output)
3. Executes commands in your local autho project directory
4. Returns the output back to Claude

This allows Claude to run tests, check build status, and interact with your local development environment seamlessly.

## Security Note

This server can execute arbitrary shell commands in your project directory. Only use it with trusted Claude Desktop installations and be aware of what commands are being executed.
