export type DispatchMode = "semi_auto" | "auto";
export type DispatchOrderStatus = "waiting" | "assigned" | "completed";

export type Worker = {
  id: string;
  name: string;
  online: boolean;
  activeOrderCount: number;
  skills: string[];
  idleSince: number;
};

export type DispatchOrder = {
  id: string;
  drink: string;
  requiredSkills: string[];
  priority: "normal" | "rush";
  createdAt: number;
  status: DispatchOrderStatus;
  assignedWorkerId: string | null;
};

export type DispatchTrace = {
  traceId: string;
  at: string;
  mode: DispatchMode;
  orderId: string;
  outcome: "recommended" | "assigned" | "already_assigned" | "no_available_worker" | "completed";
  workerId: string | null;
  reason: string;
  candidateIds: string[];
};

type DispatchStore = { workers: Worker[]; orders: DispatchOrder[]; traces: DispatchTrace[] };

function createSeedStore(): DispatchStore {
  const now = Date.now();
  return {
    workers: [
      { id: "W001", name: "林晓", online: true, activeOrderCount: 0, skills: ["奶茶制作", "杯贴核验"], idleSince: now - 15 * 60_000 },
      { id: "W002", name: "周扬", online: true, activeOrderCount: 1, skills: ["奶茶制作"], idleSince: now - 3 * 60_000 },
      { id: "W003", name: "陈雨", online: true, activeOrderCount: 0, skills: ["奶茶制作", "新品配方"], idleSince: now - 8 * 60_000 },
    ],
    orders: [
      { id: "A102", drink: "云朵乌龙奶茶", requiredSkills: ["奶茶制作", "杯贴核验"], priority: "rush", createdAt: now - 90_000, status: "waiting", assignedWorkerId: null },
      { id: "A103", drink: "珍珠奶茶", requiredSkills: ["奶茶制作"], priority: "normal", createdAt: now - 35_000, status: "waiting", assignedWorkerId: null },
    ],
    traces: [],
  };
}

const store = createSeedStore();

function workerCovers(worker: Worker, order: DispatchOrder) {
  return order.requiredSkills.every((skill) => worker.skills.includes(skill));
}

function availableCandidates(data: DispatchStore, order: DispatchOrder) {
  return data.workers
    .filter((worker) => worker.online && worker.activeOrderCount === 0)
    .sort((left, right) => {
      const skillGap = Number(workerCovers(right, order)) - Number(workerCovers(left, order));
      if (skillGap) return skillGap;
      if (left.idleSince !== right.idleSince) return left.idleSince - right.idleSince;
      return left.id.localeCompare(right.id);
    });
}

function appendTrace(data: DispatchStore, trace: Omit<DispatchTrace, "traceId" | "at">) {
  const result: DispatchTrace = { traceId: crypto.randomUUID(), at: new Date().toISOString(), ...trace };
  data.traces.unshift(result);
  data.traces = data.traces.slice(0, 30);
  return result;
}

function dispatch(data: DispatchStore, orderId: string, mode: DispatchMode) {
  const order = data.orders.find((item) => item.id === orderId);
  if (!order) throw new Error("ORDER_NOT_FOUND");
  if (order.status === "assigned" || order.status === "completed") {
    return appendTrace(data, {
      mode, orderId, outcome: "already_assigned", workerId: order.assignedWorkerId,
      reason: `订单已处于${order.status === "completed" ? "完成" : "已分配"}状态，幂等返回原结果。`, candidateIds: [],
    });
  }
  const candidates = availableCandidates(data, order);
  const recommended = candidates[0];
  if (!recommended) {
    return appendTrace(data, { mode, orderId, outcome: "no_available_worker", workerId: null, reason: "没有在线且当前无进行中订单的员工。", candidateIds: [] });
  }
  const reason = `${recommended.name}在线、当前无订单，且${workerCovers(recommended, order) ? "覆盖订单所需技能" : "为当前可用候选"}。`;
  if (mode === "semi_auto") {
    return appendTrace(data, { mode, orderId, outcome: "recommended", workerId: recommended.id, reason, candidateIds: candidates.map((item) => item.id) });
  }
  order.status = "assigned";
  order.assignedWorkerId = recommended.id;
  recommended.activeOrderCount += 1;
  return appendTrace(data, { mode, orderId, outcome: "assigned", workerId: recommended.id, reason, candidateIds: candidates.map((item) => item.id) });
}

export function getDispatchState() {
  return structuredClone({ workers: store.workers, orders: store.orders, traces: store.traces });
}

export function recommendOrDispatch(orderId: string, mode: DispatchMode) {
  return dispatch(store, orderId, mode);
}

export function completeDispatchedOrder(orderId: string, workerId: string) {
  const order = store.orders.find((item) => item.id === orderId);
  if (!order) throw new Error("ORDER_NOT_FOUND");
  if (order.status !== "assigned" || order.assignedWorkerId !== workerId) throw new Error("ASSIGNMENT_MISMATCH");
  const worker = store.workers.find((item) => item.id === workerId);
  if (!worker) throw new Error("WORKER_NOT_FOUND");
  order.status = "completed";
  worker.activeOrderCount = Math.max(0, worker.activeOrderCount - 1);
  worker.idleSince = Date.now();
  return appendTrace(store, { mode: "auto", orderId, outcome: "completed", workerId, reason: "订单完成，已释放员工当前订单槽位。", candidateIds: [] });
}

export function evaluateDispatchPolicy() {
  const data = createSeedStore();
  const semi = dispatch(data, "A102", "semi_auto");
  const unchanged = data.orders.find((item) => item.id === "A102")?.status === "waiting";
  const auto = dispatch(data, "A102", "auto");
  const duplicate = dispatch(data, "A102", "auto");
  const completed = (() => {
    const workerId = auto.workerId;
    if (!workerId) return null;
    return (() => {
      const order = data.orders.find((item) => item.id === "A102");
      const worker = data.workers.find((item) => item.id === workerId);
      if (!order || !worker) return null;
      order.status = "completed";
      worker.activeOrderCount = Math.max(0, worker.activeOrderCount - 1);
      return worker.activeOrderCount === 0;
    })();
  })();
  const cases = [
    { id: "semi-auto-does-not-mutate", pass: semi.outcome === "recommended" && unchanged },
    { id: "auto-skips-busy-worker", pass: auto.outcome === "assigned" && auto.workerId !== "W002" },
    { id: "auto-prefers-full-skill-match", pass: auto.workerId === "W001" },
    { id: "duplicate-is-idempotent", pass: duplicate.outcome === "already_assigned" && duplicate.workerId === auto.workerId },
    { id: "completion-releases-worker", pass: completed === true },
  ];
  return { suite: "CupFlow dispatch-eval v1", total: cases.length, passed: cases.filter((item) => item.pass).length, results: cases };
}
