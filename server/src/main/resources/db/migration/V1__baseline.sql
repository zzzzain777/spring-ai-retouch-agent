-- 业务数据与任务队列的权威存储。枚举一律存为 varchar，增删取值不需要改类型。

create table users (
    id            uuid primary key,
    username      varchar(32)  not null unique,
    password_hash varchar(100) not null,
    created_at    timestamptz  not null default now()
);

create table assets (
    id           uuid primary key,
    user_id      uuid         not null references users (id) on delete cascade,
    kind         varchar(16)  not null,
    source       varchar(16)  not null,
    storage_key  varchar(255) not null unique,
    image_format varchar(8)   not null,
    width        int          not null,
    height       int          not null,
    size_bytes   bigint       not null,
    has_alpha    boolean      not null,
    sha256       varchar(64)  not null check (length(sha256) = 64),
    created_at   timestamptz  not null default now()
);
create index assets_user_created on assets (user_id, created_at desc);

create table edit_sessions (
    id                uuid primary key,
    user_id           uuid        not null references users (id) on delete cascade,
    title             varchar(80) not null,
    original_asset_id uuid        not null references assets (id) on delete restrict,
    current_asset_id  uuid        not null references assets (id) on delete restrict,
    -- 只由 Java 递增；选区与计划都绑定修订号
    revision          int         not null default 1,
    -- 当前撤销位置；新操作截断之后的重做分支
    history_seq       int         not null default 1,
    document          jsonb       not null,
    -- 乐观锁版本，防止并发编辑相互覆盖
    version           bigint      not null default 0,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);
create index edit_sessions_user_updated on edit_sessions (user_id, updated_at desc);

-- 会话图片墙：未采用的候选也留存，position 保证顺序稳定
create table session_assets (
    session_id uuid not null references edit_sessions (id) on delete cascade,
    asset_id   uuid not null references assets (id) on delete cascade,
    position   int  not null,
    primary key (session_id, asset_id)
);

create table edit_history (
    id         uuid primary key,
    user_id    uuid        not null references users (id) on delete cascade,
    session_id uuid        not null references edit_sessions (id) on delete cascade,
    seq        int         not null,
    action     varchar(48) not null,
    params     jsonb       not null default '{}',
    result     jsonb       not null default '{}',
    created_at timestamptz not null default now(),
    unique (session_id, seq)
);

-- 一轮自然语言指令：plan 即断点，重启后按库里的步骤状态继续
create table agent_turns (
    id              uuid primary key,
    user_id         uuid        not null references users (id) on delete cascade,
    session_id      uuid        not null references edit_sessions (id) on delete cascade,
    revision        int         not null,
    goal            text        not null,
    reply           text        not null default '',
    plan            jsonb       not null default '[]',
    status          varchar(16) not null,
    error           text,
    idempotency_key varchar(64),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    unique (session_id, idempotency_key)
);
create index agent_turns_session_created on agent_turns (session_id, created_at);

-- 单次工具调用。id 贯穿 SSE、Redis 频道、调用图像服务的 X-Run-Id 与对象存储输出前缀。
-- 队列就是这张表：Worker 用 FOR UPDATE SKIP LOCKED 领取，租约过期即视为执行中断。
create table tool_runs (
    id               uuid primary key,
    user_id          uuid        not null references users (id) on delete cascade,
    session_id       uuid references edit_sessions (id) on delete cascade,
    turn_id          uuid references agent_turns (id) on delete set null,
    step_id          varchar(16),
    -- 同一计划步骤每次单步重试都新建一行，step_attempt 递增
    step_attempt     int,
    tool             varchar(48) not null,
    status           varchar(16) not null,
    progress         int         not null default 0,
    stage            varchar(64) not null default '',
    params           jsonb       not null default '{}',
    result           jsonb       not null default '{}',
    error            text,
    -- 同一行内因租约过期重排的执行次数
    attempt          int         not null default 0,
    max_attempts     int         not null default 1,
    cancel_requested boolean     not null default false,
    lease_until      timestamptz,
    worker_id        varchar(64),
    created_at       timestamptz not null default now(),
    started_at       timestamptz,
    finished_at      timestamptz,
    updated_at       timestamptz not null default now(),
    unique (turn_id, step_id, step_attempt)
);
create index tool_runs_queued on tool_runs (created_at) where status = 'queued';
create index tool_runs_running_lease on tool_runs (lease_until) where status = 'running';
create index tool_runs_user on tool_runs (user_id, created_at desc);
