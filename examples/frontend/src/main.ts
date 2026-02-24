import { WebSocketsRpcProxy } from "./api/rpc-proxy";
import { Rpc, isChatMessage, isUserJoined, isUserLeft } from "./api/rpc";
import type { TodoItem, ChatEvent } from "./api/rpc";

// ─── Setup ───────────────────────────────────────────────────────
const proxy = new WebSocketsRpcProxy("/rpc", "/rpc/ws");
const rpc = new Rpc(proxy);
let todoAbort: AbortController | null = null;

// ─── Helpers ─────────────────────────────────────────────────────
const $ = (id: string) => document.getElementById(id)!;

// ─── Auth ────────────────────────────────────────────────────────
async function login(username: string, password: string) {
    const res = await fetch("/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password }),
    });

    if (!res.ok) {
        $("auth-status").textContent = "Login failed!";
        $("auth-status").className = "status";
        return;
    }

    const { token } = await res.json();
    // Store token — in a real app you'd use httpOnly cookies
    (window as any).__rpcToken = token;
    $("auth-status").textContent = `Logged in as ${username}`;
    $("auth-status").className = "status ok";
}

$("login-alice").addEventListener("click", () => login("alice", "password1"));
$("login-bob").addEventListener("click", () => login("bob", "password2"));

// ─── Greeter ─────────────────────────────────────────────────────
$("greet-btn").addEventListener("click", async () => {
    const name = (document.getElementById("greet-input") as HTMLInputElement).value;
    const result = await rpc.greet(name);
    $("greet-result").textContent = JSON.stringify(result, null, 2);
});

$("greet-me-btn").addEventListener("click", async () => {
    try {
        const result = await rpc.greetMe();
        $("greet-result").textContent = JSON.stringify(result, null, 2);
    } catch (e: any) {
        $("greet-result").textContent = "Error: " + e.message;
    }
});

// ─── Todos ───────────────────────────────────────────────────────
function renderTodos(todos: readonly TodoItem[]) {
    const container = $("todo-list");
    container.innerHTML = todos.map(t => `
        <div class="todo-item ${t.completed ? 'done' : ''}">
            <span class="todo-title">${t.title}</span>
            <button data-toggle="${t.id}">${t.completed ? '↩' : '✓'}</button>
            <button data-delete="${t.id}" style="background: var(--danger)">✕</button>
        </div>
    `).join("");

    container.querySelectorAll("[data-toggle]").forEach(btn => {
        btn.addEventListener("click", () => rpc.toggleTodo(btn.getAttribute("data-toggle")!));
    });

    container.querySelectorAll("[data-delete]").forEach(btn => {
        btn.addEventListener("click", () => rpc.deleteTodo(btn.getAttribute("data-delete")!));
    });
}

$("todo-add-btn").addEventListener("click", async () => {
    const input = document.getElementById("todo-input") as HTMLInputElement;
    if (!input.value.trim()) return;
    await rpc.addTodo(input.value.trim());
    input.value = "";
});

$("todo-subscribe-btn").addEventListener("click", async () => {
    todoAbort = new AbortController();
    ($("todo-subscribe-btn") as HTMLButtonElement).disabled = true;
    ($("todo-unsubscribe-btn") as HTMLButtonElement).disabled = false;

    try {
        for await (const todos of rpc.subscribeTodos({ signal: todoAbort.signal })) {
            renderTodos(todos);
        }
    } catch (e: any) {
        if (e.message !== "Aborted") console.error("Subscription error:", e);
    }

    ($("todo-subscribe-btn") as HTMLButtonElement).disabled = false;
    ($("todo-unsubscribe-btn") as HTMLButtonElement).disabled = true;
});

$("todo-unsubscribe-btn").addEventListener("click", () => {
    todoAbort?.abort();
    todoAbort = null;
});

// ─── Chat ────────────────────────────────────────────────────────
function appendChatEvent(event: ChatEvent) {
    const div = document.createElement("div");
    div.className = "chat-event";

    if (isChatMessage(event)) {
        div.innerHTML = `<span class="user">${event.userId}</span>: ${event.text}`;
    } else if (isUserJoined(event)) {
        div.className += " system";
        div.textContent = `→ ${event.name} joined`;
    } else if (isUserLeft(event)) {
        div.className += " system";
        div.textContent = `← User ${event.userId} left`;
    }

    $("chat-log").appendChild(div);
    $("chat-log").scrollTop = $("chat-log").scrollHeight;
}

$("chat-subscribe-btn").addEventListener("click", async () => {
    ($("chat-subscribe-btn") as HTMLButtonElement).disabled = true;

    try {
        for await (const event of rpc.subscribeChatEvents("lobby")) {
            appendChatEvent(event);
        }
    } catch (e: any) {
        console.error("Chat error:", e);
    }
});

$("chat-send-btn").addEventListener("click", async () => {
    const input = document.getElementById("chat-input") as HTMLInputElement;
    if (!input.value.trim()) return;
    await rpc.sendMessage("lobby", input.value.trim());
    input.value = "";
});
