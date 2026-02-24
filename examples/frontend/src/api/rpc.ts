import {subscribeToIterable} from "./runtime";
import type {RpcProxy} from "./runtime";

export type OffsetDateTime = string;
export type LocalDate = string;
export type Instant = string;

export interface Greeting {
    message: string;
    timestamp: string;
}

export interface RpcContextData {
    userId: number | null;
}

export interface TodoItem {
    completed: boolean;
    id: string;
    title: string;
}

export interface ChatEvent {
    type: "net.planerist.ktsrpc.example.ChatEvent.ChatMessage" | "net.planerist.ktsrpc.example.ChatEvent.UserJoined" | "net.planerist.ktsrpc.example.ChatEvent.UserLeft";
}

export interface ChatMessage extends ChatEvent {
    text: string;
    timestamp: string;
    userId: string;
    type: 'net.planerist.ktsrpc.example.ChatEvent.ChatMessage';
}

export const isChatMessage = (v: ChatEvent): v is ChatMessage => {
    return v.type === 'net.planerist.ktsrpc.example.ChatEvent.ChatMessage'
}

export interface UserJoined extends ChatEvent {
    name: string;
    userId: string;
    type: 'net.planerist.ktsrpc.example.ChatEvent.UserJoined';
}

export const isUserJoined = (v: ChatEvent): v is UserJoined => {
    return v.type === 'net.planerist.ktsrpc.example.ChatEvent.UserJoined'
}

export interface UserLeft extends ChatEvent {
    userId: string;
    type: 'net.planerist.ktsrpc.example.ChatEvent.UserLeft';
}

export const isUserLeft = (v: ChatEvent): v is UserLeft => {
    return v.type === 'net.planerist.ktsrpc.example.ChatEvent.UserLeft'
}



// RPC
export class Rpc {

    private rpcProxy : RpcProxy
    
    constructor(rpcProxy: RpcProxy) {
        this.rpcProxy = rpcProxy
    }

    async greet(name: string) : Promise<Greeting> {
        const result = await this.rpcProxy.call("greet", {
            name: name
        })
        return result as Greeting
    }

    async greetMe() : Promise<Greeting> {
        const result = await this.rpcProxy.call("greetMe", {
        })
        return result as Greeting
    }

    async addTodo(title: string) : Promise<TodoItem> {
        const result = await this.rpcProxy.call("addTodo", {
            title: title
        })
        return result as TodoItem
    }

    async deleteTodo(id: string) : Promise<void> {
        const result = await this.rpcProxy.call("deleteTodo", {
            id: id
        })
        return result as void
    }

    async listTodos() : Promise<readonly TodoItem[]> {
        const result = await this.rpcProxy.call("listTodos", {
        })
        return result as readonly TodoItem[]
    }

    async *subscribeTodos(options?: { signal?: AbortSignal }) : AsyncIterable<readonly TodoItem[]> {
        const iterable = subscribeToIterable(
            sink => this.rpcProxy.subscribe("subscribeTodos", {
            }, sink),
            options?.signal
        );

        yield* iterable as AsyncIterable<readonly TodoItem[]>;
    }

    async toggleTodo(id: string) : Promise<TodoItem> {
        const result = await this.rpcProxy.call("toggleTodo", {
            id: id
        })
        return result as TodoItem
    }

    async sendMessage(room: string, text: string) : Promise<void> {
        const result = await this.rpcProxy.call("sendMessage", {
            room: room,
            text: text
        })
        return result as void
    }

    async *subscribeChatEvents(room: string, options?: { signal?: AbortSignal }) : AsyncIterable<ChatEvent> {
        const iterable = subscribeToIterable(
            sink => this.rpcProxy.subscribe("subscribeChatEvents", {
                room: room
            }, sink),
            options?.signal
        );

        yield* iterable as AsyncIterable<ChatEvent>;
    }

}

