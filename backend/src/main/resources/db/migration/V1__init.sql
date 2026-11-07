create table conversation (
                              id text primary key,
                              group_id text not null,
                              channel text not null,
                              language text not null,
                              audio_url text,
                              transcript jsonb not null,
                              imported_at timestamptz not null default now()
);

create table checklist_version (
                                   id bigserial primary key,
                                   name text not null,
                                   version int not null,
                                   criteria jsonb not null,
                                   created_at timestamptz not null default now(),
                                   unique (name, version)
);

create table evaluation (
                            id bigserial primary key,
                            conversation_id text not null references conversation(id),
                            checklist_version_id bigint not null references checklist_version(id),
                            status text not null,
                            result jsonb,
                            total_score numeric(5,2),
                            gross_error boolean,
                            model text,
                            input_tokens int,
                            output_tokens int,
                            cost_usd numeric(10,6),
                            error text,
                            created_at timestamptz not null default now()
);

create table appeal (
                        id bigserial primary key,
                        evaluation_id bigint not null references evaluation(id),
                        criterion_id text not null,
                        comment text not null,
                        evidence_ids jsonb,
                        status text not null default 'OPEN',
                        decision text,
                        new_answer text,
                        new_is_na boolean,
                        reviewer_reason text,
                        final_result jsonb,
                        final_total numeric(5,2),
                        created_at timestamptz not null default now(),
                        decided_at timestamptz
);

create index idx_conversation_group on conversation(group_id);
create index idx_evaluation_conversation on evaluation(conversation_id);
create index idx_appeal_evaluation on appeal(evaluation_id);