# Chat Agents

A server-side Fabric mod that puts coding-agent CLIs into Minecraft chat as helpers players can talk to.
It currently supports [Claude Code](https://docs.claude.com/en/docs/claude-code) (`claude`), the
[Antigravity CLI](https://antigravity.google/docs/cli) (`agy`), the [Codex CLI](https://developers.openai.com/codex/cli)
(`codex`) and [opencode](https://opencode.ai) (`opencode`), and you can run several agents side by side. Each one can be
turned on or off in game or from the server console.

```
<catwaita> clod can u skip the night, creepers everywhere
<clod> done, it's morning ☀
<catwaita> ty. also remind me in 20 min to check the furnace
<clod> ok, i'll ping you at 18:40
<steve> agy is it raining in lisbon rn? deciding whether to go out lol
<agy> nope, 17°C and partly cloudy there right now
<steve> codex how do i make a smithing table
<codex> 2 iron ingots on top, 4 planks under them (2 wide, 3 tall)
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
- **Any harness, any model, with failover.** An agent can run on several CLIs and models in order of preference.
  When one runs out of usage, the turn moves to the next, and the agent goes back once the limit resets. Operators can
  also switch by hand: `/clod use claude` keeps the model and changes the CLI.

## Install

1. Put `chat-agents-<version>.jar` and Fabric API in `mods/`.
2. Install the CLIs you want for the user the server runs as, and log in once interactively:
   - Claude Code: `curl -fsSL https://claude.ai/install.sh | bash`, then run `claude` and log in.
   - Antigravity: `curl -fsSL https://antigravity.google/cli/install.sh | bash`, then run `agy` and sign in (or set
     `"modelProvider": "gemini"` in `~/.gemini/antigravity-cli/settings.json` and export `GEMINI_API_KEY`).
   - Codex: `npm install -g @openai/codex`, then run `codex` and sign in with ChatGPT (or export `CODEX_API_KEY`).
   - opencode: `curl -fsSL https://opencode.ai/install | bash`, then run `opencode auth login` for your provider.
3. Start the server once. This creates `config/chatagents/config.json`, with every agent off, and a prompt per agent
   in `config/chatagents/prompts/`. Check the `command` paths under `harnesses`, edit the prompts, then turn the
   agents on.

## Turning agents on and off

| Command | Who |
|---|---|
| `/clod on`, `/clod off` (any agent id) | operators (level 2) and the console |
| `/clod` or `/clod status` | everyone |
| `/clod reset` (start a fresh conversation) | operators |
| `/clod routes` (harness + model list, what's benched) | everyone |
| `/clod use <harness> [model]`, `/clod model <model>` (switch, see below) | operators |
| `/chatagents` (list all), `/chatagents reload` (re-read the config) | everyone / operators |

The state is saved to the config file, so it survives restarts. From SSH, send the same commands to the server
console (for example through a console FIFO, `screen`, or RCON): `clod off`.

Turning an agent off kills a turn it's in the middle of and drops its queued messages.

## Configuration

`config/chatagents/config.json` has three parts:

- **harnesses**: the CLIs (Claude Code, agy, Codex, opencode), each with its own binary and login.
- **models**: optional aliases, so one name means the right model ID in every harness.
- **agents**: the characters in chat. Each one has a list of **routes** (harness + model) in order of preference.

```jsonc
{
  "timezone": "Europe/Lisbon",        // local time shown to agents
  "timezoneLabel": "Lisbon time",
  "gatewayHost": "127.0.0.1",         // the MCP console gateway; port 0 = pick a free one
  "gatewayPort": 0,
  "stateDir": "config/chatagents/state",
  "timing": {
    "cooldownSeconds": 8, "conversationSeconds": 90, "...": "...",
    "limitCooldownMinutes": 60,       // bench a route that ran out of usage (if the CLI doesn't say until when)
    "failuresBeforeBench": 3,         // bench a route after this many other failures in a row...
    "errorBenchMinutes": 5            // ...for this long
  },
  "policy": {
    "playerAllow": ["time query", "time add", "list"],
    "operatorDeny": ["stop", "op", "deop", "save-off", "reload", "ban-ip", "pardon-ip", "time set", "chatagents"]
  },
  "harnesses": {
    "claude": {
      "backend": "claude",             // "claude", "antigravity", "codex" or "opencode"
      "command": "/home/mc/.local/bin/claude",
      "home": "",                      // HOME for the CLI, if its login lives elsewhere
      "extraArgs": ["--max-turns", "12"],
      "env": { "ENABLE_CLAUDEAI_MCP_SERVERS": "false" },
      "commandPrefix": []              // e.g. a wrapper; the CLI still needs access to workdir and home
    },
    "agy": { "backend": "antigravity", "command": "/home/mc/.local/bin/agy" },
    "opencode": { "backend": "opencode", "command": "/home/mc/.opencode/bin/opencode" }
  },
  "models": {
    "sonnet": {                        // alias -> harness -> that harness's id for the model
      "claude": "claude-sonnet-5-5",
      "agy": "claude-sonnet-5-5",      // agy adds the effort: claude-sonnet-5-5-low
      "opencode": "anthropic/claude-sonnet-5-5"
    }
  },
  "limitPatterns": [],                // extra regexes for "out of usage" errors, on top of the built-in ones
  "agents": [
    {
      "id": "clod",                    // also the command: /clod
      "displayName": "clod",
      "color": "gold",                 // Minecraft color name for the name tag
      "enabled": true,
      "triggers": ["claude", "clod"],  // matched case-insensitively at the start of a word, any script
      "routes": [                      // tried in order; see "Harnesses, models and failover"
        { "harness": "agy", "model": "sonnet", "effort": "low" },
        { "harness": "claude", "model": "sonnet", "effort": "low" },
        { "harness": "agy", "model": "gemini-3.8-flash", "effort": "low" }
      ],
      "failover": true,                // on an error, retry the turn on the next route
      "announceSwitches": "ops",       // who's told when a route is benched: "ops", "everyone" or "off"
      "workdir": "config/chatagents/work/clod",
      "systemPrompt": "clod.md",       // in config/chatagents/prompts; {name} and {triggers} are filled in
      "events": true,                  // react to deaths/advancements
      "greetNewcomers": true
    }
  ]
}
```

A route's `model` is an alias from `models` or a model ID written as is; empty uses the harness's default.
`effort` is passed to the CLI (Claude Code's `--effort`, Codex's `model_reasoning_effort`, opencode's `--variant`);
empty leaves it out. agy's model IDs include the effort (`claude-sonnet-5-5-low`, `gemini-3.8-flash-high`, see
`/model` in agy), so for agy it's appended to the model unless the model already ends in one. opencode model IDs are
`provider/model`, and its variant names depend on the provider.

Prompts are re-read every turn, so edits apply right away. Run `/chatagents reload` after editing the config;
adding or removing agents needs a restart. Configs from before harnesses existed (`backend`, `command`, `model` on
the agent) are converted automatically the first time they're loaded.

### Harnesses, models and failover

Claude Code, agy, Codex and opencode are harnesses: they can run some of the same models, but each has its own
login and usage limits. So an agent isn't tied to one of them:

- **Automatic failover.** A turn runs on the agent's first route. If that fails, it's retried on the next one, and
  the failed route may be benched so later turns skip it until it's likely to work again:
  - a usage, quota or rate limit benches it until the limit resets (Claude Code reports the time; for the others
    it's `limitCooldownMinutes`);
  - any other error (an expired login, a crashed CLI) benches it for `errorBenchMinutes` once it has failed
    `failuresBeforeBench` turns in a row, so a broken harness doesn't slow down every turn.

  When a route is benched, `announceSwitches` decides who sees a gray note (operators by default):
  `[clod] agy · sonnet (claude-sonnet-5-5-low) is out of usage, skipping it until 10-06 19:00 Lisbon time; using
  claude · sonnet (claude-sonnet-5-5) for now`. It's in the server log either way. If every route is benched,
  the one that frees up first gets a try anyway.
- **Recognizing "out of usage".** Limit errors are recognized from the CLI's error text (`usage limit`, `quota`,
  `RESOURCE_EXHAUSTED`, `429`, ...). If a CLI words it differently, the log shows the message as
  `[clod] agy · sonnet (...) failed: ...`. Add a regex for it to `limitPatterns` and run `/chatagents reload`.
- **Switching by hand.** `/clod use claude` moves clod to Claude Code and keeps the current model (the alias
  `sonnet` turns into Claude Code's ID for it). `/clod use opencode openai/gpt-5.5` picks a harness and a model.
  `/clod model opus` keeps the harness and changes the model. Each of these makes the chosen route the first one
  (adding it if it's new), un-benches it, and saves the config. `/clod routes` lists the routes with the model ID
  each harness is really given, and which ones are benched.
- **Several logins for one CLI.** Two harnesses can use the same backend with different `home`s, for example
  `claude-main` and `claude-alt`, so one account can take over when the other runs out.

Each harness keeps its own conversation with the agent (resuming it when the agent comes back to that harness) and
runs in its own `<workdir>/<harness>` directory. After a switch, the agent still sees recent chat in the prompt, but
not the rest of what it said in the other harness.

### Backends

- **claude** runs `claude -p` with `--system-prompt`, `--tools WebSearch,WebFetch` and the console tool from a
  generated `--mcp-config`, and resumes with `--resume`. The latest plan-limit info goes to
  `<stateDir>/<id>/limits-<harness>.json`, and a rejected limit benches the route until it resets.
- **antigravity** runs `agy -p --output-format json` and resumes with `--conversation`. agy has no system prompt
  flag, so the prompt is written to `AGENTS.md` in the harness's workdir. The mod adds the console server to
  `$HOME/.gemini/config/mcp_config.json`, and the rules `mcp(minecraft/*)` (allow) and `command(*)` (deny) to
  `$HOME/.gemini/antigravity-cli/settings.json`. That server entry holds the agent's token, so agents that share an
  agy `home` take turns instead of running at the same time.
- **codex** runs `codex exec --json` and resumes with `codex exec resume <thread>`. Everything is passed as `-c`
  overrides, so `~/.codex/config.toml` isn't touched: the prompt goes in as `developer_instructions`, the console tool
  as an HTTP MCP server whose token comes from an environment variable, the shell tool is turned off
  (`features.shell_tool=false`), the sandbox is read-only, and web search is live. The workdir doesn't need to be a
  git repo (`--skip-git-repo-check`).
- **opencode** runs `opencode run --format json` and resumes with `--session`. The mod passes an inline config in
  `OPENCODE_CONFIG_CONTENT` that adds the console server and a `chatagents` agent with the prompt and the permissions
  `*` deny, `minecraft_*`, `webfetch` and `websearch` allow; your own opencode config is merged in as usual.

Every turn is appended to `<stateDir>/<id>/usage.jsonl` with the harness, model, tokens and, where the CLI reports
it, cost.

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
