# Chat Agents

A server-side Fabric mod that puts coding-agent CLIs into Minecraft chat as helpers players can talk to.
It currently supports [Claude Code](https://docs.claude.com/en/docs/claude-code) (`claude`) and the
[Antigravity CLI](https://antigravity.google/docs/cli) (`agy`), and you can run several agents side by side. Each one
can be turned on or off in game or from the server console.

```
<catwaita> clod can you skip the night
<clod> on it~ (˶ᵔ ᵕ ᵔ˶) ...done, good morning!
<yeerboo> agy what's the weather in astana
<agy> 12°C and clear right now ✦
```

Requires Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API and Java 25. The mod is server-only; players don't need it.

## What it does

- **Chat.** A message that mentions an agent's name (its *triggers*) goes to that agent, and the reply is posted as
  `<name> ...`. After it replies, the player's next messages reach it for 90 seconds without its name, so
  conversations flow naturally. Messages that arrive close together are answered in one turn.
- **Server actions with permissions.** Agents get one tool, `console`, served by the mod over MCP on localhost. It runs
  a console command and returns the output. The mod checks every call against who started the turn:
  - Regular players' turns may only run an allowlist (by default: query the time, skip the night, list players).
  - Operators' turns may run anything except a denylist (`stop`, `op`, `deop`, `reload`, `time set`, ...). Commands
    wrapped in `execute ... run` are checked too.

  Agents have no shell, so the mod's policy is the only way they can act on the server.
- **Server life.** The agent privately greets first-time players and reacts to deaths and advancements, with
  cooldowns. Only the first enabled agent with these options on does it. Agents can also set reminders, which are
  delivered with a bell sound, even if the player was offline when one came due.
- **One conversation per agent.** Each agent resumes the same CLI session every turn, so it remembers earlier chat.
  Operators can reset it.

## Install

1. Put `chat-agents-<version>.jar` and Fabric API in `mods/`.
2. Install the CLIs you want for the user the server runs as, and log in once interactively:
   - Claude Code: `curl -fsSL https://claude.ai/install.sh | bash`, then run `claude` and log in.
   - Antigravity: `curl -fsSL https://antigravity.google/cli/install.sh | bash`, then run `agy` and sign in (or set
     `"modelProvider": "gemini"` in `~/.gemini/antigravity-cli/settings.json` and export `GEMINI_API_KEY`).
3. Start the server once. This creates `config/chatagents/config.json`, with both agents off, and a prompt per agent
   in `config/chatagents/prompts/`. Check the `command` paths, edit the prompts, then turn the agents on.

## Turning agents on and off

| Command | Who |
|---|---|
| `/clod on`, `/clod off` (any agent id) | operators (level 2) and the console |
| `/clod` or `/clod status` | everyone |
| `/clod reset` (start a fresh conversation) | operators |
| `/chatagents` (list all), `/chatagents reload` (re-read the config) | everyone / operators |

The state is saved to the config file, so it survives restarts. From SSH, send the same commands to the server
console (for example through a console FIFO, `screen`, or RCON): `clod off`.

Turning an agent off kills a turn it's in the middle of and drops its queued messages.

## Configuration

`config/chatagents/config.json`:

```jsonc
{
  "timezone": "Asia/Almaty",          // local time shown to agents
  "timezoneLabel": "Astana time",
  "gatewayHost": "127.0.0.1",         // the MCP console gateway; port 0 = pick a free one
  "gatewayPort": 0,
  "stateDir": "config/chatagents/state",
  "timing": { "cooldownSeconds": 8, "conversationSeconds": 90, "eventCooldownSeconds": 30, "...": "..." },
  "policy": {
    "playerAllow": ["time query", "time add", "list"],
    "operatorDeny": ["stop", "op", "deop", "save-off", "reload", "ban-ip", "pardon-ip", "time set", "chatagents"]
  },
  "agents": [
    {
      "id": "clod",                    // also the command: /clod
      "displayName": "clod",
      "color": "gold",                 // Minecraft color name for the name tag
      "backend": "claude",             // or "antigravity"
      "enabled": true,
      "triggers": ["claude", "clod"],  // matched case-insensitively at the start of a word, any script
      "command": "/home/mc/.local/bin/claude",
      "home": "",                      // HOME for the CLI, if its login lives elsewhere
      "workdir": "config/chatagents/work/clod",
      "model": "claude-sonnet-5-5",
      "effort": "low",
      "systemPrompt": "clod.md",       // in config/chatagents/prompts; {name} and {triggers} are filled in
      "events": true,                  // react to deaths/advancements
      "greetNewcomers": true,
      "extraArgs": ["--max-turns", "12"],
      "env": { "ENABLE_CLAUDEAI_MCP_SERVERS": "false" },
      "commandPrefix": []              // e.g. a wrapper; the CLI still needs access to workdir and home
    }
  ]
}
```

Prompts are re-read every turn, so edits apply right away. Run `/chatagents reload` after editing the config;
adding or removing agents needs a restart.

### Backends

- **claude** runs `claude -p` with `--system-prompt`, `--tools WebSearch,WebFetch` and the console tool from a
  generated `--mcp-config`, and resumes with `--resume`. Usage and cost per turn are appended to
  `<stateDir>/<id>/usage.jsonl`, and the latest plan-limit info to `limits.json`.
- **antigravity** runs `agy -p --output-format json` and resumes with `--conversation`. agy has no system prompt
  flag, so the prompt is written to `AGENTS.md` in the agent's workdir. The mod adds the console server to
  `$HOME/.gemini/config/mcp_config.json`, and the rules `mcp(minecraft/*)` (allow) and `command(*)` (deny) to
  `$HOME/.gemini/antigravity-cli/settings.json`.

### Reply tags

Agents can end a reply with hidden tags. The bundled prompt explains them to the model.

| Tag | Effect |
|---|---|
| `NO_REPLY` (whole reply) | post nothing; closes the conversation |
| `[[await:name1,name2]]` | treat these players' next messages as replies for 2 minutes |
| `[[remind:MINUTES:player:text]]` | deliver `text` privately to the player later |
| `[[unremind:id]]` | cancel a reminder |
| `[[reset]]` | start a fresh conversation (operator turns only) |

## Security notes

- The gateway listens on localhost only. Each agent has its own URL and a random bearer token that is new on every
  start. A call only works while that agent is in a turn, with the permissions of whoever started the turn.
- The prompt also tells the agent who is an operator, but enforcement doesn't depend on the model behaving: denied
  commands are refused by the mod.
- The model sees what players type and what web pages say. Keep the operator denylist tight, and only op people you
  trust.
- Commands run as the server console, so command feedback is broadcast to operators as `[Server: ...]` like any
  console command, and each one is written to the server log as `[agent] console (ROLE): command`.

## Building

```
./gradlew build    # needs JDK 25; the jar lands in build/libs
```

## License

MIT
