#!/usr/bin/env python3
"""
MCP Server for executing local shell commands in the autho project.

This server allows Claude Code to execute commands in your local environment,
particularly useful for running `lein test` and other build commands.
"""

import asyncio
import subprocess
import sys
import os
from typing import Any

# MCP SDK imports
try:
    from mcp.server import Server
    from mcp.server.stdio import stdio_server
    from mcp.types import Tool, TextContent
except ImportError:
    print("ERROR: mcp package not found. Please install it with:", file=sys.stderr)
    print("  pip install mcp", file=sys.stderr)
    sys.exit(1)

# Get the project root directory
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Create server instance
server = Server("autho-local-shell")


@server.list_tools()
async def list_tools() -> list[Tool]:
    """List available tools."""
    return [
        Tool(
            name="run_lein_test",
            description="Run Leiningen tests in the autho project. Returns test output including failures and successes.",
            inputSchema={
                "type": "object",
                "properties": {
                    "test_namespace": {
                        "type": "string",
                        "description": "Optional: specific test namespace to run (e.g., 'autho.kafka-pip-test'). If not provided, runs all tests.",
                    }
                },
                "required": [],
            },
        ),
        Tool(
            name="run_shell_command",
            description="Execute a shell command in the autho project directory. Use this for any command that's not covered by other tools.",
            inputSchema={
                "type": "object",
                "properties": {
                    "command": {
                        "type": "string",
                        "description": "The shell command to execute (e.g., './lein deps', 'git status')",
                    },
                    "timeout": {
                        "type": "number",
                        "description": "Timeout in seconds (default: 300)",
                        "default": 300,
                    }
                },
                "required": ["command"],
            },
        ),
    ]


@server.call_tool()
async def call_tool(name: str, arguments: Any) -> list[TextContent]:
    """Handle tool calls."""

    if name == "run_lein_test":
        test_namespace = arguments.get("test_namespace")

        if test_namespace:
            cmd = ["./lein", "test", test_namespace]
            description = f"Running tests for namespace: {test_namespace}"
        else:
            cmd = ["./lein", "test"]
            description = "Running all tests"

        try:
            result = subprocess.run(
                cmd,
                cwd=PROJECT_ROOT,
                capture_output=True,
                text=True,
                timeout=300,
            )

            output = f"{description}\n\n"
            output += f"Exit code: {result.returncode}\n\n"
            output += "=== STDOUT ===\n"
            output += result.stdout
            output += "\n\n=== STDERR ===\n"
            output += result.stderr

            return [TextContent(type="text", text=output)]

        except subprocess.TimeoutExpired:
            return [TextContent(type="text", text=f"ERROR: Command timed out after 300 seconds")]
        except Exception as e:
            return [TextContent(type="text", text=f"ERROR: {str(e)}")]

    elif name == "run_shell_command":
        command = arguments.get("command")
        timeout = arguments.get("timeout", 300)

        try:
            result = subprocess.run(
                command,
                cwd=PROJECT_ROOT,
                capture_output=True,
                text=True,
                timeout=timeout,
                shell=True,
            )

            output = f"Command: {command}\n"
            output += f"Exit code: {result.returncode}\n\n"
            output += "=== STDOUT ===\n"
            output += result.stdout
            output += "\n\n=== STDERR ===\n"
            output += result.stderr

            return [TextContent(type="text", text=output)]

        except subprocess.TimeoutExpired:
            return [TextContent(type="text", text=f"ERROR: Command timed out after {timeout} seconds")]
        except Exception as e:
            return [TextContent(type="text", text=f"ERROR: {str(e)}")]

    else:
        return [TextContent(type="text", text=f"ERROR: Unknown tool: {name}")]


async def main():
    """Run the MCP server."""
    async with stdio_server() as (read_stream, write_stream):
        await server.run(
            read_stream,
            write_stream,
            server.create_initialization_options(),
        )


if __name__ == "__main__":
    asyncio.run(main())
