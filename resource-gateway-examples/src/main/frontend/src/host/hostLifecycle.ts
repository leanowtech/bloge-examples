export const HOST_WILL_DISPOSE_EVENT = 'bloge:host-will-dispose';
export const HOST_WORKSPACE_READY_EVENT = 'bloge:host-workspace-ready';

export interface HostDisposalPreparation {
  ready: boolean;
  handlerCount: number;
  failureCount: number;
  timedOut: boolean;
}

interface HostWillDisposeDetail {
  waitUntil(work: Promise<unknown>): void;
}

export interface HostWorkspaceReadyDetail {
  route: string;
  measuredAt: number;
}

/** Announces that the lazy route has committed, so a host can measure cold-start readiness. */
export function signalHostWorkspaceReady(
  route: string,
  target: Window = window,
): void {
  target.dispatchEvent(new CustomEvent<HostWorkspaceReadyDetail>(HOST_WORKSPACE_READY_EVENT, {
    detail: {
      route,
      measuredAt: target.performance.now(),
    },
  }));
}

/** Lets every mounted authoring surface join one host-controlled disposal barrier. */
export async function prepareHostDisposal(
  target: Window = window,
  timeoutMs = 10_000,
): Promise<HostDisposalPreparation> {
  const pending: Promise<unknown>[] = [];
  const event = new CustomEvent<HostWillDisposeDetail>(HOST_WILL_DISPOSE_EVENT, {
    detail: {
      waitUntil(work) {
        pending.push(Promise.resolve(work));
      },
    },
  });
  target.dispatchEvent(event);
  let timeout: number | undefined;
  const settled = await Promise.race([
    Promise.allSettled(pending),
    new Promise<'TIMED_OUT'>((resolve) => {
      timeout = target.setTimeout(() => resolve('TIMED_OUT'), timeoutMs);
    }),
  ]);
  if (timeout !== undefined) target.clearTimeout(timeout);
  if (settled === 'TIMED_OUT') {
    return {
      ready: false,
      handlerCount: pending.length,
      failureCount: Math.max(1, pending.length),
      timedOut: true,
    };
  }
  const failureCount = settled.filter((result) => (
    result.status === 'rejected' || result.value === false
  )).length;
  return {
    ready: failureCount === 0,
    handlerCount: settled.length,
    failureCount,
    timedOut: false,
  };
}

export function joinHostDisposal(
  event: Event,
  work: Promise<unknown>,
): void {
  const detail = (event as CustomEvent<HostWillDisposeDetail>).detail;
  detail?.waitUntil(work);
}
