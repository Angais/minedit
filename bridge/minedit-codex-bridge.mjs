#!/usr/bin/env node

import http from "node:http";
import { spawn } from "node:child_process";
import readline from "node:readline";
import { EventEmitter } from "node:events";

const HOST = process.env.MINEDIT_BRIDGE_HOST || "127.0.0.1";
const PORT = Number(process.env.MINEDIT_BRIDGE_PORT || 8765);
const CODEX_BIN = process.env.CODEX_BIN || "codex";
const REQUEST_TIMEOUT_MS = Number(process.env.MINEDIT_CODEX_TIMEOUT_MS || 10 * 60 * 1000);
const RPC_TIMEOUT_MS = Number(process.env.MINEDIT_CODEX_RPC_TIMEOUT_MS || 30 * 1000);
const MAX_BODY_BYTES = Number(process.env.MINEDIT_BRIDGE_MAX_BODY_BYTES || 5 * 1024 * 1024);

class BridgeError extends Error {
  constructor(status, message, details = null) {
    super(message);
    this.status = status;
    this.details = details;
  }
}

class CodexAppServerSession {
  constructor() {
    this.nextId = 1;
    this.pending = new Map();
    this.events = new EventEmitter();
    this.stderr = "";
    this.closed = false;

    this.proc = spawn(CODEX_BIN, ["app-server"], {
      stdio: ["pipe", "pipe", "pipe"],
    });

    this.proc.once("error", (error) => {
      this.failAll(new BridgeError(502, `Could not start Codex app-server with '${CODEX_BIN}': ${error.message}`));
    });

    this.proc.once("exit", (code, signal) => {
      this.closed = true;
      this.failAll(new BridgeError(502, `Codex app-server exited unexpectedly (${signal || code}). ${this.stderr}`.trim()));
    });

    this.proc.stderr.on("data", (chunk) => {
      this.stderr = (this.stderr + chunk.toString()).slice(-4000);
    });

    this.rl = readline.createInterface({ input: this.proc.stdout });
    this.rl.on("line", (line) => this.handleLine(line));
  }

  async initialize() {
    await this.request("initialize", {
      clientInfo: {
        name: "minedit_bridge",
        title: "Minedit Codex Bridge",
        version: "0.1.0",
      },
    }, RPC_TIMEOUT_MS);
    this.notify("initialized", {});
  }

  handleLine(line) {
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      this.stderr = (this.stderr + "\nNon-JSON stdout: " + line).slice(-4000);
      return;
    }

    if (Object.prototype.hasOwnProperty.call(msg, "id") && Object.prototype.hasOwnProperty.call(msg, "method")) {
      this.handleServerRequest(msg);
      return;
    }

    if (Object.prototype.hasOwnProperty.call(msg, "id")) {
      const pending = this.pending.get(msg.id);
      if (!pending) {
        return;
      }
      this.pending.delete(msg.id);
      clearTimeout(pending.timeout);
      if (msg.error) {
        pending.reject(new BridgeError(502, `${pending.method} failed: ${msg.error.message || "Codex app-server error"}`, msg.error));
      } else {
        pending.resolve(msg.result);
      }
      return;
    }

    if (msg.method) {
      this.events.emit("notification", msg);
    }
  }

  handleServerRequest(msg) {
    if (msg.method === "item/commandExecution/requestApproval" || msg.method === "item/fileChange/requestApproval") {
      this.send({ id: msg.id, result: { decision: "cancel" } });
      return;
    }
    if (msg.method === "tool/requestUserInput") {
      this.send({ id: msg.id, result: { answers: {} } });
      return;
    }
    this.send({
      id: msg.id,
      error: {
        code: -32601,
        message: `Minedit bridge does not handle Codex server request '${msg.method}'.`,
      },
    });
  }

  request(method, params = {}, timeoutMs = RPC_TIMEOUT_MS) {
    if (this.closed) {
      return Promise.reject(new BridgeError(502, "Codex app-server is not running."));
    }
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new BridgeError(504, `${method} timed out after ${Math.round(timeoutMs / 1000)}s.`));
      }, timeoutMs);
      this.pending.set(id, { method, resolve, reject, timeout });
      this.send({ method, id, params });
    });
  }

  notify(method, params = {}) {
    this.send({ method, params });
  }

  send(message) {
    this.proc.stdin.write(`${JSON.stringify(message)}\n`);
  }

  waitForNotification(predicate, timeoutMs) {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.events.off("notification", listener);
        reject(new BridgeError(504, `Codex turn timed out after ${Math.round(timeoutMs / 1000)}s.`));
      }, timeoutMs);
      const listener = (msg) => {
        if (!predicate(msg)) {
          return;
        }
        clearTimeout(timeout);
        this.events.off("notification", listener);
        resolve(msg);
      };
      this.events.on("notification", listener);
    });
  }

  failAll(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timeout);
      pending.reject(error);
    }
    this.pending.clear();
    this.events.emit("notification", { method: "bridge/error", params: { error: error.message } });
  }

  close() {
    this.closed = true;
    this.rl.close();
    if (!this.proc.killed) {
      this.proc.kill("SIGTERM");
    }
  }
}

function normalizeCodexModel(model) {
  const trimmed = String(model || "").trim();
  return trimmed.startsWith("openai/") ? trimmed.slice("openai/".length) : trimmed;
}

function effortList(model) {
  return (model?.supportedReasoningEfforts || [])
    .map((entry) => entry?.reasoningEffort)
    .filter(Boolean);
}

function findModel(models, requested) {
  const normalized = normalizeCodexModel(requested);
  return models.find((model) => model.id === normalized || model.model === normalized || model.displayName === requested);
}

function extractAgentText(turn, fallbackByItemId) {
  const items = turn?.items || [];
  const agentMessages = items.filter((item) => item?.type === "agentMessage" && typeof item.text === "string");
  const finalMessage = [...agentMessages].reverse().find((item) => item.phase === "final_answer");
  if (finalMessage) {
    return finalMessage.text;
  }
  if (agentMessages.length > 0) {
    return agentMessages[agentMessages.length - 1].text;
  }
  const fallback = [...fallbackByItemId.values()].join("").trim();
  return fallback || "";
}

async function withSession(fn) {
  const session = new CodexAppServerSession();
  try {
    await session.initialize();
    return await fn(session);
  } finally {
    session.close();
  }
}

async function readAccount(session) {
  return await session.request("account/read", { refreshToken: false }, RPC_TIMEOUT_MS);
}

async function listModels(session) {
  const result = await session.request("model/list", { limit: 200, includeHidden: true }, RPC_TIMEOUT_MS);
  return result?.data || [];
}

async function statusPayload() {
  return await withSession(async (session) => {
    const account = await readAccount(session);
    const models = await listModels(session);
    return {
      ok: true,
      requiresOpenaiAuth: Boolean(account?.requiresOpenaiAuth),
      needsLogin: Boolean(account?.requiresOpenaiAuth && !account?.account),
      account: account?.account || null,
      models: models.map((model) => ({
        id: model.id,
        model: model.model,
        displayName: model.displayName,
        defaultReasoningEffort: model.defaultReasoningEffort,
        supportedReasoningEfforts: effortList(model),
        hidden: Boolean(model.hidden),
        isDefault: Boolean(model.isDefault),
      })),
    };
  });
}

async function completeWithCodex({ prompt, model, effort }) {
  if (!prompt || typeof prompt !== "string") {
    throw new BridgeError(400, "Missing string field: prompt");
  }
  if (!model || typeof model !== "string") {
    throw new BridgeError(400, "Missing string field: model");
  }
  if (!effort || typeof effort !== "string") {
    throw new BridgeError(400, "Missing string field: effort");
  }

  return await withSession(async (session) => {
    const account = await readAccount(session);
    if (account?.requiresOpenaiAuth && !account?.account) {
      throw new BridgeError(401, "Codex is not logged in. Run 'codex login' in a terminal, then restart the Minedit bridge.");
    }

    const models = await listModels(session);
    const modelEntry = findModel(models, model);
    if (!modelEntry) {
      const visible = models.filter((entry) => !entry.hidden).slice(0, 12).map((entry) => entry.model || entry.id).join(", ");
      throw new BridgeError(400, `Codex model '${normalizeCodexModel(model)}' was not found. Try /model gpt-5.5 or use /codex status. Available examples: ${visible || "none returned"}`);
    }

    const supportedEfforts = effortList(modelEntry);
    if (supportedEfforts.length > 0 && !supportedEfforts.includes(effort)) {
      throw new BridgeError(400, `Codex model '${modelEntry.model}' does not support reasoning effort '${effort}'. Supported: ${supportedEfforts.join(", ")}`);
    }

    const modelToUse = modelEntry.model || modelEntry.id;
    const threadResult = await session.request("thread/start", {
      model: modelToUse,
      cwd: process.cwd(),
      ephemeral: true,
      serviceName: "minedit",
      approvalPolicy: "never",
      sandbox: "read-only",
      personality: "pragmatic",
      developerInstructions: "You are a text-only Minecraft builder-code generator for Minedit. Do not inspect files, run commands, or use tools. Return only the requested JavaScript build(api) function or a fenced JavaScript block containing it.",
    }, RPC_TIMEOUT_MS);

    const threadId = threadResult?.thread?.id;
    if (!threadId) {
      throw new BridgeError(502, "Codex app-server did not return a thread id.");
    }

    const deltasByItemId = new Map();
    const completedAgentMessages = new Map();
    session.events.on("notification", (msg) => {
      if (msg.method === "item/agentMessage/delta" && msg.params?.threadId === threadId) {
        const itemId = msg.params.itemId;
        deltasByItemId.set(itemId, (deltasByItemId.get(itemId) || "") + (msg.params.delta || ""));
      }
      if (msg.method === "item/completed" && msg.params?.threadId === threadId) {
        const item = msg.params.item;
        if (item?.type === "agentMessage" && typeof item.text === "string") {
          completedAgentMessages.set(item.id || String(completedAgentMessages.size), item.text);
        }
      }
    });

    const turnResult = await session.request("turn/start", {
      threadId,
      input: [{ type: "text", text: prompt }],
      model: modelToUse,
      effort,
      approvalPolicy: "never",
    }, RPC_TIMEOUT_MS);

    const turnId = turnResult?.turn?.id;
    if (!turnId) {
      throw new BridgeError(502, "Codex app-server did not return a turn id.");
    }

    let completed = null;
    if (turnResult.turn.status === "completed") {
      completed = { params: { turn: turnResult.turn } };
    } else {
      completed = await session.waitForNotification(
        (msg) => msg.method === "turn/completed" && msg.params?.threadId === threadId && msg.params?.turn?.id === turnId,
        REQUEST_TIMEOUT_MS,
      );
    }

    const turn = completed.params.turn;
    if (turn.status === "failed") {
      throw new BridgeError(502, turn.error?.message || "Codex turn failed.", turn.error || null);
    }
    if (turn.status !== "completed") {
      throw new BridgeError(502, `Codex turn ended with status '${turn.status}'.`);
    }

    const text = extractAgentText(turn, completedAgentMessages.size > 0 ? completedAgentMessages : deltasByItemId).trim();
    if (!text) {
      throw new BridgeError(502, "Codex completed but returned no agent message text.");
    }

    return {
      ok: true,
      provider: "codex-app-server",
      model: modelToUse,
      effort,
      text,
    };
  });
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
      if (Buffer.byteLength(body) > MAX_BODY_BYTES) {
        reject(new BridgeError(413, "Request body is too large."));
        req.destroy();
      }
    });
    req.on("end", () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (error) {
        reject(new BridgeError(400, `Invalid JSON body: ${error.message}`));
      }
    });
    req.on("error", reject);
  });
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}

async function handle(req, res) {
  const url = new URL(req.url || "/", `http://${HOST}:${PORT}`);
  if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
    sendJson(res, 200, { ok: true, service: "minedit-codex-bridge" });
    return;
  }
  if (req.method === "GET" && url.pathname === "/status") {
    sendJson(res, 200, await statusPayload());
    return;
  }
  if (req.method === "POST" && url.pathname === "/complete") {
    const body = await readJsonBody(req);
    sendJson(res, 200, await completeWithCodex(body));
    return;
  }
  sendJson(res, 404, { ok: false, error: "Not found. Use GET /status or POST /complete." });
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((error) => {
    const status = error instanceof BridgeError ? error.status : 500;
    sendJson(res, status, {
      ok: false,
      error: error.message || "Bridge error.",
      details: error.details || null,
    });
  });
});

server.listen(PORT, HOST, () => {
  console.log(`Minedit Codex bridge listening on http://${HOST}:${PORT}`);
  console.log(`Using Codex binary: ${CODEX_BIN}`);
});
