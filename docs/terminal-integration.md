# AndClaw Real Interactive Terminal Integration

This is a direct integration terminal stack for AndClaw using a **real PTY**.

## What is implemented

- PTY backend with `node-pty` (real shell, not simulation)
- WebSocket bridge for real-time stdin/stdout/stderr
- xterm.js frontend with resize and keyboard passthrough
- Explicit ANSI key mapping for Arrow keys, Escape, Home/End, PgUp/PgDn, Delete, Tab, Enter, and Ctrl+key combos
- Persistent multi-tab sessions
- Default working directory set to your OpenClaw folder (`OPENCLAW_DIR`, default `/workspace/OpenClaw`)
- No command blocklist (full shell access as requested)

## Key files

- Backend: `terminal/backend/src/server.js`
- Backend deps: `terminal/backend/package.json`
- Frontend app: `terminal/frontend/app.js`
- Frontend page: `terminal/frontend/index.html`
- Frontend styles: `terminal/frontend/styles.css`

## Run

```bash
cd terminal/backend
npm install
OPENCLAW_DIR=/absolute/path/to/OpenClaw npm start
```

Open in browser:

```text
http://localhost:7681
```

## Behavior

Since it is true PTY + `/bin/bash -l`, terminal behavior matches Termux-like expectations:

- Arrow-key history navigation
- Tab autocomplete
- Ctrl+C / Ctrl+Z / Ctrl+D
- Escape and navigation keys (Home/End/PageUp/PageDown/Delete)
- Full-screen apps (`vim`, `nano`, `less`)

## Remaining steps (next run)

1. Add auth guard for `/ws` in production mode.
2. Add reconnect token so tabs can recover after backend restart.
3. Add optional session persistence metadata on disk.

These can be done in the next run.
