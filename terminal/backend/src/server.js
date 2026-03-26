import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import express from "express";
import { WebSocketServer } from "ws";
import pty from "node-pty";

const PORT = Number(process.env.PORT ?? 7681);
const HOST = process.env.HOST ?? "0.0.0.0";
const MAX_SESSIONS = Number(process.env.MAX_SESSIONS ?? 6);
const DEFAULT_OPENCLAW_DIR = process.env.OPENCLAW_DIR ?? "/workspace/OpenClaw";

function resolveWorkspaceDir() {
  const preferred = path.resolve(DEFAULT_OPENCLAW_DIR);
  if (fs.existsSync(preferred)) return preferred;
  return process.cwd();
}

const WORKSPACE_DIR = resolveWorkspaceDir();
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const FRONTEND_DIR = path.resolve(__dirname, "../../frontend");

const app = express();
app.use(express.static(FRONTEND_DIR));
app.get("/healthz", (_req, res) => res.json({ ok: true }));

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });

const sessions = new Map();

function shellForPlatform() {
  return process.env.SHELL || "/bin/bash";
}

function shellEnv() {
  return {
    ...process.env,
    TERM: "xterm-256color",
    COLORTERM: "truecolor",
    HOME: "/home/andclaw",
    WORKSPACE_DIR,
  };
}

function createSession(sessionId) {
  if (sessions.has(sessionId)) return sessions.get(sessionId);
  if (sessions.size >= MAX_SESSIONS) {
    throw new Error(`Max sessions reached (${MAX_SESSIONS})`);
  }

  const proc = pty.spawn(shellForPlatform(), ["-l"], {
    name: "xterm-256color",
    cols: 120,
    rows: 36,
    cwd: WORKSPACE_DIR,
    env: shellEnv(),
  });

  const session = {
    id: sessionId,
    proc,
    outputHistory: [],
    attachedSockets: new Set(),
  };

  proc.write(`cd ${WORKSPACE_DIR}\r`);

  proc.onData((chunk) => {
    session.outputHistory.push(chunk);
    if (session.outputHistory.length > 1500) session.outputHistory.shift();
    for (const ws of session.attachedSockets) {
      if (ws.readyState === ws.OPEN) {
        ws.send(JSON.stringify({ type: "output", sessionId, data: chunk }));
      }
    }
  });

  proc.onExit(({ exitCode, signal }) => {
    for (const ws of session.attachedSockets) {
      if (ws.readyState === ws.OPEN) {
        ws.send(
          JSON.stringify({
            type: "session-exit",
            sessionId,
            exitCode,
            signal,
          }),
        );
      }
    }
    sessions.delete(sessionId);
  });

  sessions.set(sessionId, session);
  return session;
}

function send(ws, payload) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(payload));
}

wss.on("connection", (ws) => {
  send(ws, {
    type: "welcome",
    workspaceDir: WORKSPACE_DIR,
    maxSessions: MAX_SESSIONS,
  });

  ws.on("message", (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      send(ws, { type: "error", message: "Invalid JSON message" });
      return;
    }

    try {
      if (msg.type === "create") {
        const sessionId = msg.sessionId || `tab-${Date.now()}`;
        const session = createSession(sessionId);
        session.attachedSockets.add(ws);
        send(ws, { type: "created", sessionId: session.id });
        if (session.outputHistory.length > 0) {
          send(ws, {
            type: "output",
            sessionId,
            data: session.outputHistory.join(""),
          });
        }
        return;
      }

      if (msg.type === "attach") {
        const session = sessions.get(msg.sessionId);
        if (!session) {
          send(ws, { type: "error", message: "Session not found" });
          return;
        }
        session.attachedSockets.add(ws);
        send(ws, {
          type: "output",
          sessionId: session.id,
          data: session.outputHistory.join(""),
        });
        return;
      }

      if (msg.type === "list") {
        send(ws, {
          type: "session-list",
          sessions: [...sessions.keys()],
        });
        return;
      }

      if (msg.type === "input") {
        const session = sessions.get(msg.sessionId);
        if (!session) {
          send(ws, { type: "error", message: "Session not found" });
          return;
        }

        const data = String(msg.data ?? "");
        session.proc.write(data);
        return;
      }

      if (msg.type === "resize") {
        const session = sessions.get(msg.sessionId);
        if (!session) return;
        const cols = Number(msg.cols || 120);
        const rows = Number(msg.rows || 36);
        session.proc.resize(cols, rows);
        return;
      }

      if (msg.type === "close") {
        const session = sessions.get(msg.sessionId);
        if (!session) return;
        session.proc.kill();
        return;
      }

      send(ws, { type: "error", message: `Unknown type: ${msg.type}` });
    } catch (err) {
      send(ws, { type: "error", message: err.message || "Unhandled error" });
    }
  });

  ws.on("close", () => {
    for (const session of sessions.values()) {
      session.attachedSockets.delete(ws);
    }
  });
});

server.listen(PORT, HOST, () => {
  console.log(`[andclaw-terminal] listening on http://${HOST}:${PORT}`);
  console.log(`[andclaw-terminal] workspace: ${WORKSPACE_DIR}`);
});
