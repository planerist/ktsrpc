import type { RpcProxy, Sink, Unsubscribe } from "./runtime";
import { WebSocketClient } from "./websocket-client";

export class WebSocketsRpcProxy implements RpcProxy {
    private baseUrl: string;
    private wsUrl: string;
    private client: WebSocketClient;

    constructor(baseUrl: string = "/rpc", wsUrl: string = "/rpc/ws") {
        this.baseUrl = baseUrl;
        if (wsUrl.startsWith("/") && typeof window !== 'undefined') {
            const loc = window.location;
            const proto = loc.protocol === "https:" ? "wss:" : "ws:";
            this.wsUrl = `${proto}//${loc.host}${wsUrl}`;
        } else {
            this.wsUrl = wsUrl;
        }

        this.client = new WebSocketClient({ url: this.wsUrl });
    }

    async call(methodName: string, args: unknown): Promise<unknown> {
        const response = await fetch(this.baseUrl, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
            },
            body: JSON.stringify({
                operationName: methodName,
                query: args
            }),
        });

        if (!response.ok) {
            throw new Error(`RPC call failed: ${response.status} ${response.statusText}`);
        }

        const json = await response.json();
        if (json.error) {
            console.error("RPC Error:", json.error);
            throw new Error(typeof json.error === 'string' ? json.error : (json.error.message || "Unknown RPC error"));
        }
        return json.data;
    }

    subscribe<T>(methodName: string, args: unknown, sink: Sink<T>): Unsubscribe {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const wrappedSink: Sink<any> = {
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            next(payload: any) {
                if (payload?.error) {
                    sink.error(payload.error);
                } else if (payload?.data) {
                    sink.next(payload.data);
                } else {
                    if (Object.keys(payload).length === 0) return;
                    sink.next(payload);
                }
            },
            error(err) {
                sink.error(err);
            },
            complete() {
                sink.complete();
            }
        };

        return this.client.subscribe({
            operationName: methodName,
            query: args
        }, wrappedSink);
    }
}
