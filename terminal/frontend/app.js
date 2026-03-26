const wsProtocol = location.protocol === "https:" ? "wss" : "ws";
const ws = new WebSocket(`${wsProtocol}://${location.host}/ws`);

const tabsEl = document.querySelector("#tabs");
const terminalsEl = document.querySelector("#terminals");
const newTabBtn = document.querySelector("#new-tab");

const sessions = new Map();
let activeSessionId = null;

function sendInput(sessionId, data) {
  ws.send(JSON.stringify({ type: "input", sessionId, data }));
}

function encodeCtrlKey(letter) {
  return String.fromCharCode(letter.toUpperCase().charCodeAt(0) - 64);
}

function keyToEscapeSequence(ev) {
  switch (ev.key) {
    case "ArrowUp":
      return "\u001b[A";
    case "ArrowDown":
      return "\u001b[B";
    case "ArrowRight":
      return "\u001b[C";
    case "ArrowLeft":
      return "\u001b[D";
    case "Escape":
      return "\u001b";
    case "Tab":
      return "\t";
    case "Enter":
      return "\r";
    case "Backspace":
      return "\u007f";
    case "Delete":
      return "\u001b[3~";
    case "Home":
      return "\u001b[H";
    case "End":
      return "\u001b[F";
    case "PageUp":
      return "\u001b[5~";
    case "PageDown":
      return "\u001b[6~";
    default:
      return null;
  }
}

function createTabUi(sessionId) {
  const tabBtn = document.createElement("button");
  tabBtn.className = "tab";
  tabBtn.textContent = sessionId;
  tabBtn.onclick = () => activate(sessionId);

  const pane = document.createElement("section");
  pane.className = "terminal-pane";

  const term = new Terminal({
    cursorBlink: true,
    cursorStyle: "block",
    fontSize: 14,
    convertEol: true,
    scrollback: 2000,
    allowProposedApi: true
  });
  const fitAddon = new FitAddon.FitAddon();
  term.loadAddon(fitAddon);
  term.open(pane);

  term.onData((data) => {
    sendInput(sessionId, data);
  });

  term.attachCustomKeyEventHandler((ev) => {
    if (ev.type !== "keydown") return true;

    if (ev.ctrlKey && !ev.altKey && /^[a-z]$/i.test(ev.key)) {
      sendInput(sessionId, encodeCtrlKey(ev.key));
      return false;
    }

    const seq = keyToEscapeSequence(ev);
    if (seq) {
      sendInput(sessionId, seq);
      return false;
    }

    return true;
  });

  term.onBinary((data) => {
    sendInput(sessionId, data);
  });

  terminalsEl.appendChild(pane);
  tabsEl.appendChild(tabBtn);

  sessions.set(sessionId, {
    id: sessionId,
    tabBtn,
    pane,
    term,
    fitAddon
  });

  requestAnimationFrame(() => {
    fitAddon.fit();
    ws.send(
      JSON.stringify({
        type: "resize",
        sessionId,
        cols: term.cols,
        rows: term.rows,
      }),
    );
  });
}

function activate(sessionId) {
  activeSessionId = sessionId;
  for (const [id, session] of sessions) {
    const on = id === sessionId;
    session.tabBtn.classList.toggle("active", on);
    session.pane.classList.toggle("active", on);
  }
  const active = sessions.get(sessionId);
  if (active) {
    active.term.focus();
    active.fitAddon.fit();
    ws.send(
      JSON.stringify({
        type: "resize",
        sessionId,
        cols: active.term.cols,
        rows: active.term.rows,
      }),
    );
  }
}

function createTab() {
  const id = `tab-${Math.random().toString(36).slice(2, 8)}`;
  ws.send(JSON.stringify({ type: "create", sessionId: id }));
}

newTabBtn.onclick = createTab;

window.addEventListener("resize", () => {
  const active = sessions.get(activeSessionId);
  if (!active) return;
  active.fitAddon.fit();
  ws.send(
    JSON.stringify({
      type: "resize",
      sessionId: activeSessionId,
      cols: active.term.cols,
      rows: active.term.rows,
    }),
  );
});

ws.onopen = () => {
  ws.send(JSON.stringify({ type: "list" }));
};

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);

  if (msg.type === "session-list") {
    if (!msg.sessions.length) {
      createTab();
      return;
    }
    msg.sessions.forEach((sessionId) => {
      if (!sessions.has(sessionId)) {
        createTabUi(sessionId);
        ws.send(JSON.stringify({ type: "attach", sessionId }));
      }
    });
    if (!activeSessionId && msg.sessions[0]) activate(msg.sessions[0]);
    return;
  }

  if (msg.type === "created") {
    if (!sessions.has(msg.sessionId)) createTabUi(msg.sessionId);
    activate(msg.sessionId);
    return;
  }

  if (msg.type === "output") {
    const session = sessions.get(msg.sessionId);
    if (!session) return;
    session.term.write(msg.data);
    return;
  }

  if (msg.type === "session-exit") {
    const session = sessions.get(msg.sessionId);
    if (!session) return;
    session.term.writeln(`\r\n[session exited: ${msg.exitCode}]`);
    return;
  }

  if (msg.type === "error") {
    console.error(msg.message);
  }
};
