import { Annotation, END, START, StateGraph } from "@langchain/langgraph";

export type AgentAction = "cup" | "tea" | "pearls" | "wrong" | "measure" | "seal" | "label" | "wrongLabel" | "overfill" | "unknown";
export type WorkflowStep = "cup" | "tea" | "pearls" | "measure" | "seal" | "label";

export type CupFlowOrder = {
  id: string;
  drink: string;
  options: string[];
};

export type VisionObservation = {
  event: AgentAction;
  confidence: number;
  reason: string;
  ticket?: {
    orderId?: string | null;
    drink?: string | null;
    sugar?: string | null;
    ice?: string | null;
    topping?: string | null;
    matchesCurrentOrder?: boolean | null;
  } | null;
};

export type KnowledgeChunk = {
  id: string;
  title: string;
  content: string;
  source: string;
  score: number;
};

export type AgentDecision = {
  outcome: "advance" | "hold" | "review";
  action: AgentAction;
  reason: string;
  requiresHumanApproval: boolean;
  anomaly: "wrongIngredient" | "extraPearls" | "overfill" | "sequence" | "wrongLabel" | null;
  confidenceThreshold: number;
};

export type AgentTrace = {
  traceId: string;
  sessionId: string;
  createdAt: string;
  steps: string[];
  tools: string[];
  sources: Array<Pick<KnowledgeChunk, "id" | "title" | "source" | "score">>;
  decision: AgentDecision;
};

export type WorkflowInput = {
  sessionId: string;
  order: CupFlowOrder;
  expectedStep: { id: WorkflowStep; title: string };
  stepIndex: number;
  observation: VisionObservation;
  recentEvents?: string[];
};

type Recipe = {
  drink: string;
  version: string;
  steps: WorkflowStep[];
  forbiddenToppings: string[];
  source: string;
};

const recipes: Recipe[] = [{
  drink: "云朵乌龙奶茶",
  version: "demo-v1",
  steps: ["cup", "pearls", "tea", "measure", "seal", "label"],
  forbiddenToppings: ["椰果"],
  source: "CupFlow 模拟配方库 demo-v1（仅用于赛事演示，不替代门店 SOP）",
}];

function findRecipe(drink: string) {
  return recipes.find((recipe) => recipe.drink === drink) ?? recipes[0];
}

/**
 * Retrieval layer: query -> ranked recipe snippets -> traceable sources.
 * The first release uses an approved, versioned local recipe corpus. The same
 * interface can later be backed by a vector database without changing policy.
 */
export function retrieveRecipeKnowledge(order: CupFlowOrder, expectedStep: WorkflowStep): KnowledgeChunk[] {
  const recipe = findRecipe(order.drink);
  const docs: Omit<KnowledgeChunk, "score">[] = [
    {
      id: `recipe:${recipe.drink}:steps`,
      title: `${recipe.drink}制作步骤`,
      content: `标准步骤：${recipe.steps.join(" → ")}。当前步骤：${expectedStep}。`,
      source: recipe.source,
    },
    {
      id: `recipe:${recipe.drink}:constraints`,
      title: `${recipe.drink}定制约束`,
      content: `订单定制：${order.options.join("、")}。禁止小料：${recipe.forbiddenToppings.join("、")}。`,
      source: recipe.source,
    },
    {
      id: "safety:human-review",
      title: "高风险复核规则",
      content: "超量、杯贴不匹配、信息缺失或低置信度不得自动放行；系统只提供辅助，不控制任何设备。",
      source: "CupFlow 安全策略 v1",
    },
  ];
  return docs.map((doc) => ({ ...doc, score: doc.content.includes(expectedStep) ? 1 : 0.82 }));
}

function evaluateObservation(input: WorkflowInput, recipe: Recipe): AgentDecision {
  const { observation, expectedStep, stepIndex } = input;
  const threshold = expectedStep.id === "label" ? 0.9 : 0.88;
  const base = {
    action: observation.event,
    confidenceThreshold: threshold,
  };

  if (observation.event === "unknown" || observation.confidence < threshold) {
    return { ...base, outcome: "hold", reason: "视觉证据不足，保持当前步骤并请求更清晰画面或人工确认。", requiresHumanApproval: false, anomaly: null };
  }
  if (observation.event === "wrong") {
    return { ...base, outcome: "review", reason: "检测到与本单约束冲突的小料，暂停并要求纠正。", requiresHumanApproval: false, anomaly: "wrongIngredient" };
  }
  if (observation.event === "overfill") {
    return { ...base, outcome: "review", reason: "液位超标属于质量风险，纠正后需店员明确复核。", requiresHumanApproval: true, anomaly: "overfill" };
  }
  if (expectedStep.id === "label" && observation.ticket?.matchesCurrentOrder !== true) {
    return { ...base, outcome: "review", reason: "杯贴字段未完整匹配当前订单，禁止自动完成订单。", requiresHumanApproval: true, anomaly: "wrongLabel" };
  }
  if (observation.event === "wrongLabel") {
    return { ...base, outcome: "review", reason: "检测到杯贴与订单不匹配，需更换并人工复核。", requiresHumanApproval: true, anomaly: "wrongLabel" };
  }
  if (observation.event === "pearls" && stepIndex > recipe.steps.indexOf("pearls")) {
    return { ...base, outcome: "review", reason: "珍珠步骤已完成，疑似重复加料。", requiresHumanApproval: false, anomaly: "extraPearls" };
  }
  if (observation.event !== expectedStep.id) {
    return { ...base, outcome: "review", reason: `当前应执行${expectedStep.title}，检测到步骤不一致。`, requiresHumanApproval: false, anomaly: "sequence" };
  }
  return { ...base, outcome: "advance", reason: "已通过配方约束、步骤顺序与置信度校验。", requiresHumanApproval: false, anomaly: null };
}

const WorkflowState = Annotation.Root({
  input: Annotation<WorkflowInput>,
  recipe: Annotation<Recipe | null>,
  knowledge: Annotation<KnowledgeChunk[]>({ reducer: (_left, right) => right, default: () => [] }),
  decision: Annotation<AgentDecision | null>,
  tools: Annotation<string[]>({ reducer: (left, right) => left.concat(right), default: () => [] }),
});

const graph = new StateGraph(WorkflowState)
  .addNode("load_order_context", (state) => ({
    recipe: findRecipe(state.input.order.drink),
    tools: ["load_order_context"],
  }))
  .addNode("retrieve_recipe_knowledge", (state) => ({
    knowledge: retrieveRecipeKnowledge(state.input.order, state.input.expectedStep.id),
    tools: ["retrieve_recipe_knowledge"],
  }))
  .addNode("evaluate_workflow_policy", (state) => ({
    decision: evaluateObservation(state.input, state.recipe ?? findRecipe(state.input.order.drink)),
    tools: ["evaluate_workflow_policy"],
  }))
  .addEdge(START, "load_order_context")
  .addEdge("load_order_context", "retrieve_recipe_knowledge")
  .addEdge("retrieve_recipe_knowledge", "evaluate_workflow_policy")
  .addEdge("evaluate_workflow_policy", END)
  .compile();

export async function runCupFlowWorkflow(input: WorkflowInput): Promise<{ decision: AgentDecision; trace: AgentTrace }> {
  const result = await graph.invoke({ input, recipe: null, knowledge: [], decision: null, tools: [] });
  const decision = result.decision;
  if (!decision) throw new Error("Agent workflow completed without a decision");
  const traceId = crypto.randomUUID();
  return {
    decision,
    trace: {
      traceId,
      sessionId: input.sessionId,
      createdAt: new Date().toISOString(),
      steps: ["load_order_context", "retrieve_recipe_knowledge", "evaluate_workflow_policy"],
      tools: result.tools,
      sources: result.knowledge.map(({ id, title, source, score }) => ({ id, title, source, score })),
      decision,
    },
  };
}

export async function runPolicyEvaluation() {
  const base = {
    sessionId: "evaluation-session",
    order: { id: "A102", drink: "云朵乌龙奶茶", options: ["少糖", "去冰", "加珍珠"] },
    recentEvents: [],
  };
  const cases: Array<{ id: string; expected: AgentDecision["outcome"]; input: WorkflowInput }> = [
    { id: "correct-cup", expected: "advance", input: { ...base, expectedStep: { id: "cup", title: "取杯" }, stepIndex: 0, observation: { event: "cup", confidence: 0.95, reason: "识别到制作杯" } } },
    { id: "low-confidence", expected: "hold", input: { ...base, expectedStep: { id: "pearls", title: "加入珍珠" }, stepIndex: 1, observation: { event: "pearls", confidence: 0.63, reason: "画面模糊" } } },
    { id: "wrong-topping", expected: "review", input: { ...base, expectedStep: { id: "pearls", title: "加入珍珠" }, stepIndex: 1, observation: { event: "wrong", confidence: 0.94, reason: "识别到椰果" } } },
    { id: "repeat-topping", expected: "review", input: { ...base, expectedStep: { id: "tea", title: "加入茶底" }, stepIndex: 2, observation: { event: "pearls", confidence: 0.95, reason: "重复出现珍珠" } } },
    { id: "wrong-label", expected: "review", input: { ...base, expectedStep: { id: "label", title: "杯贴核验" }, stepIndex: 5, observation: { event: "wrongLabel", confidence: 0.96, reason: "定制项不匹配", ticket: { matchesCurrentOrder: false } } } },
  ];
  const results = await Promise.all(cases.map(async (item) => {
    const { decision } = await runCupFlowWorkflow(item.input);
    return { id: item.id, expected: item.expected, actual: decision.outcome, pass: decision.outcome === item.expected, requiresHumanApproval: decision.requiresHumanApproval };
  }));
  return { suite: "CupFlow policy-eval v1", total: results.length, passed: results.filter((result) => result.pass).length, results };
}
