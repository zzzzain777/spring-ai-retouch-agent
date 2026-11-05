-- 规划调用的执行轨迹：模型名、耗时、token 用量、模型原样给出的工具调用、被拒绝的原因。
-- 不含密钥与完整提示词；评测按同一口径统计规划成本与时延。
alter table agent_turns add column trace jsonb not null default '{}';
-- 后台对账只扫仍在执行的轮次
create index agent_turns_running on agent_turns (updated_at) where status = 'running';
