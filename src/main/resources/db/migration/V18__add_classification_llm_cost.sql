-- Story 05.02: record the cost of the LLM fallback per classification. When the router escalates
-- a decision to the LLM (route_used = 'LLM'), the call's cost in USD is recorded here so the
-- expensive lever is auditable per decision (PRD §Subsystem 5: "route_used + llm_cost_usd logged
-- per classification"). The rolling monthly + daily budget cap that consumes this cost is 05.04.
--
-- Nullable because it is meaningful only on the LLM route: a hard-rule or fast-path model decision
-- never calls the LLM and stores NULL (distinct from 0.000000, which is a real LLM call that the
-- provider billed nothing for, or that was priced at zero rates). numeric(12,6) holds sub-cent USD
-- amounts exactly — no binary-float drift when these are later summed against a budget.

alter table classifications
    add column llm_cost_usd numeric(12, 6);

alter table classifications
    add constraint classifications_llm_cost_nonneg check (llm_cost_usd is null or llm_cost_usd >= 0);
