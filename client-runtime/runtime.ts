export type Unsubscribe = () => void

export interface RpcProxy {
    call(methodName: string, args: unknown): Promise<unknown>

    subscribe<T>(methodName: string, args: unknown, sink: Sink<T>): Unsubscribe
}

/**
 * A representation of any set of values over any amount of time.
 */
export interface Sink<T = unknown> {
    /** Next value arriving. */
    next(value: T): void

    /**
     * An error that has occured. Calling this function "closes" the sink.
     */
    error(error: unknown): void

    /** The sink has completed. This function "closes" the sink. */
    complete(): void
}

export function subscribeToIterable<T>(
    subscribeFn: (sink: Sink<T>) => Unsubscribe,
    signal?: AbortSignal
): AsyncIterable<T> {
    return {
        [Symbol.asyncIterator]() {
            const queue: T[] = [];
            let resolvers: Array<(value: IteratorResult<T> | PromiseLike<IteratorResult<T>>) => void> = [];
            let error: unknown = null;
            let complete = false;

            const pushResult = (result: IteratorResult<T>) => {
                if (resolvers.length > 0) {
                    const resolve = resolvers.shift()!;
                    resolve(result);
                } else {
                    if (!result.done) {
                        queue.push(result.value);
                    }
                }
            };

            const sink: Sink<T> = {
                next(value) {
                    if (complete || error) return;
                    pushResult({ value, done: false });
                },
                error(err) {
                    if (complete || error) return;
                    error = err;
                    resolvers.forEach(resolve => resolve(Promise.reject(err)));
                    resolvers = [];
                },
                complete() {
                    if (complete || error) return;
                    complete = true;
                    resolvers.forEach(resolve => resolve({ value: undefined, done: true }));
                    resolvers = [];
                }
            };

            const unsubscribe = subscribeFn(sink);

            if (signal) {
                if (signal.aborted) {
                    unsubscribe();
                    return {
                        next: () => Promise.reject(new Error("Aborted")),
                        return: () => Promise.resolve({ value: undefined, done: true }),
                        throw: (e) => Promise.reject(e)
                    };
                }
                signal.addEventListener('abort', () => {
                    unsubscribe?.();
                    const err = new Error("Aborted");
                    error = err;
                    resolvers.forEach(resolve => resolve(Promise.reject(err)));
                    resolvers = [];
                });
            }

            return {
                next() {
                    if (error) return Promise.reject(error);
                    if (queue.length > 0) {
                        return Promise.resolve({ value: queue.shift()!, done: false });
                    }
                    if (complete) {
                        return Promise.resolve({ value: undefined, done: true });
                    }
                    return new Promise<IteratorResult<T>>(resolve => {
                        resolvers.push(resolve);
                    });
                },
                return() {
                    unsubscribe?.();
                    complete = true;
                    resolvers.forEach(resolve => resolve({ value: undefined, done: true }));
                    resolvers = [];
                    return Promise.resolve({ value: undefined, done: true });
                },
                throw(e) {
                    unsubscribe?.();
                    complete = true;
                    return Promise.reject(e);
                }
            };
        }
    };
}
