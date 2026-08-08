CREATE TABLE public.curation_run_candidates (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    run_id uuid NOT NULL,
    ref jsonb DEFAULT '{}'::jsonb NOT NULL,
    kind text,
    source text,
    fetcher text,
    final_url text,
    matches boolean,
    scores jsonb,
    composite double precision,
    outcome text,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
ALTER TABLE public.curation_run_candidates
    ADD CONSTRAINT curation_run_candidates_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.curation_run_candidates
    ADD CONSTRAINT curation_run_candidates_id_hostname_unique UNIQUE (id, hostname);
--;;
CREATE INDEX idx_curation_run_candidates_run_id ON public.curation_run_candidates USING btree (run_id);
--;;
CREATE TABLE public.curation_stage_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    run_id uuid NOT NULL,
    candidate_id uuid,
    stage text NOT NULL,
    attempt integer DEFAULT 1 NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    input jsonb,
    output jsonb,
    provenance jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
ALTER TABLE public.curation_stage_runs
    ADD CONSTRAINT curation_stage_runs_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.curation_stage_runs
    ADD CONSTRAINT curation_stage_runs_id_hostname_unique UNIQUE (id, hostname);
--;;
CREATE INDEX idx_curation_stage_runs_run_candidate ON public.curation_stage_runs USING btree (run_id, candidate_id, stage);
--;;
CREATE TABLE public.curation_feedback (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    interest_id uuid NOT NULL,
    run_id uuid,
    candidate_id uuid,
    kind text NOT NULL,
    directive text,
    status text DEFAULT 'active'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
ALTER TABLE public.curation_feedback
    ADD CONSTRAINT curation_feedback_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.curation_feedback
    ADD CONSTRAINT curation_feedback_id_hostname_unique UNIQUE (id, hostname);
--;;
CREATE INDEX idx_curation_feedback_interest_id ON public.curation_feedback USING btree (interest_id);
--;;
ALTER TABLE public.recommendations ADD COLUMN IF NOT EXISTS vetting jsonb;
