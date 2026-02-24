import type { Sink } from "./runtime";

export interface WebSocketClientOptions {
    url: string;
    protocols?: string | string[];
}

export type OperationId = string;

export interface ClientMessage {
    type: "connection_init" | "subscribe" | "complete" | "ping" | "connection_terminate";
    id?: OperationId;
    payload?: Record<string, unknown>;
}

export interface ServerMessage {
    type: "connection_ack" | "next" | "error" | "complete" | "pong";
    id?: OperationId;
    payload?: unknown;
}

export class WebSocketClient {
    private ws: WebSocket | null = null;
    private url: string;
    private protocols?: string | string[];
    private idCounter = 0;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    private subscriptions = new Map<OperationId, Sink<any>>();
    private pendingMessages: ClientMessage[] = [];
    private isConnected = false;
    private isConnecting = false;

    constructor(options: WebSocketClientOptions) {
        this.url = options.url;
        this.protocols = options.protocols;
    }

    public connect() {
        if (this.ws || this.isConnecting) return;
        this.isConnecting = true;

        this.ws = new WebSocket(this.url, this.protocols);

        this.ws.onopen = () => {
            this.send({ type: "connection_init" });
        };

        this.ws.onmessage = (event) => {
            try {
                const message = JSON.parse(event.data) as ServerMessage;
                this.handleMessage(message);
            } catch (e) {
                console.error("Failed to parse WebSocket message", e);
            }
        };

        this.ws.onclose = () => {
            this.cleanup();
        };

        this.ws.onerror = (error) => {
            console.error("WebSocket error", error);
            this.cleanup(error);
        };
    }

    private cleanup(error?: unknown) {
        this.isConnected = false;
        this.isConnecting = false;
        this.ws = null;

        for (const sink of this.subscriptions.values()) {
            if (error) {
                sink.error(error);
            } else {
                sink.complete();
            }
        }
        this.subscriptions.clear();
        this.pendingMessages = [];
    }

    private handleMessage(message: ServerMessage) {
        switch (message.type) {
            case "connection_ack":
                this.isConnected = true;
                this.isConnecting = false;
                this.flushPendingMessages();
                break;
            case "next":
                if (message.id && this.subscriptions.has(message.id)) {
                    this.subscriptions.get(message.id)!.next(message.payload);
                }
                break;
            case "error":
                if (message.id && this.subscriptions.has(message.id)) {
                    this.subscriptions.get(message.id)!.error(message.payload);
                } else {
                    console.error("Global WebSocket error", message.payload);
                }
                break;
            case "complete":
                if (message.id && this.subscriptions.has(message.id)) {
                    this.subscriptions.get(message.id)!.complete();
                    this.subscriptions.delete(message.id);
                }
                break;
            case "pong":
                break;
            default:
                console.warn("Unknown message type", message.type);
        }
    }

    private flushPendingMessages() {
        for (const msg of this.pendingMessages) {
            this.send(msg);
        }
        this.pendingMessages = [];
    }

    private send(message: ClientMessage) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(message));
        } else {
            this.pendingMessages.push(message);
            if (!this.ws && !this.isConnecting) {
                this.connect();
            }
        }
    }

    public subscribe<T>(payload: Record<string, unknown>, sink: Sink<T>): () => void {
        const id = (this.idCounter++).toString();
        this.subscriptions.set(id, sink);

        const message: ClientMessage = {
            type: "subscribe",
            id,
            payload
        };

        if (this.isConnected) {
            this.send(message);
        } else {
            this.pendingMessages.push(message);
            if (!this.ws && !this.isConnecting) {
                this.connect();
            }
        }

        return () => {
            this.unsubscribe(id);
        };
    }

    private unsubscribe(id: OperationId) {
        if (this.subscriptions.has(id)) {
            this.subscriptions.delete(id);
            if (this.isConnected) {
                this.send({
                    type: "complete",
                    id
                });
            } else {
                this.pendingMessages = this.pendingMessages.filter(msg => msg.id !== id);
            }
        }
    }
}
