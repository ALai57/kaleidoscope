CREATE TABLE public.agent_definitions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id text NOT NULL,
    agent_type text NOT NULL,
    name text NOT NULL,
    avatar text NOT NULL,
    system_prompt text NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    short_name text DEFAULT ''::text NOT NULL,
    color text DEFAULT ''::text NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.albums (
    id uuid NOT NULL,
    album_name character varying,
    created_at timestamp without time zone,
    modified_at timestamp without time zone,
    description character varying,
    cover_photo_id uuid,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.photos (
    id uuid NOT NULL,
    photo_title character varying,
    created_at timestamp without time zone,
    modified_at timestamp without time zone,
    hostname character varying NOT NULL,
    description character varying
);
--;;
CREATE TABLE public.photos_in_albums (
    id uuid NOT NULL,
    photo_id uuid NOT NULL,
    created_at timestamp without time zone,
    modified_at timestamp without time zone,
    album_id uuid NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE VIEW public.album_contents AS
 SELECT pia.id AS album_content_id,
    a.id AS album_id,
    p.id AS photo_id,
    pia.modified_at AS added_to_album_at,
    p.photo_title,
    a.album_name,
    a.description AS album_description,
    a.cover_photo_id,
    a.hostname
   FROM ((public.photos_in_albums pia
     JOIN public.photos p ON ((p.id = pia.photo_id)))
     JOIN public.albums a ON ((a.id = pia.album_id)));
--;;
CREATE TABLE public.article_audiences (
    id uuid NOT NULL,
    group_id character varying(36) NOT NULL,
    article_id integer NOT NULL,
    created_at timestamp without time zone,
    modified_at timestamp without time zone,
    hostname character varying NOT NULL
);
--;;
CREATE SEQUENCE public.article_branches_id_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
--;;
CREATE TABLE public.article_branches (
    id bigint DEFAULT nextval('article_branches_id_seq') NOT NULL,
    article_id integer NOT NULL,
    published_at timestamp without time zone,
    branch_name character varying(100),
    created_at timestamp without time zone,
    modified_at timestamp without time zone,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.article_tags (
    id uuid NOT NULL,
    tag_id uuid NOT NULL,
    article_id integer NOT NULL,
    created_at timestamp without time zone,
    hostname character varying NOT NULL
);
--;;
CREATE SEQUENCE public.article_versions_id_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
--;;
CREATE TABLE public.article_versions (
    id bigint DEFAULT nextval('article_versions_id_seq') NOT NULL,
    branch_id integer NOT NULL,
    title character varying,
    content character varying,
    created_at timestamp without time zone,
    modified_at timestamp without time zone,
    hostname character varying NOT NULL
);
--;;
CREATE SEQUENCE public.articles_id_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
--;;
CREATE TABLE public.articles (
    id bigint DEFAULT nextval('articles_id_seq') NOT NULL,
    author character varying(50),
    article_url character varying(100),
    article_tags character varying(32),
    created_at timestamp without time zone,
    modified_at timestamp without time zone,
    hostname character varying NOT NULL,
    article_title character varying,
    summary text,
    public_visibility boolean DEFAULT false
);
--;;
CREATE VIEW public.enhanced_albums AS
 SELECT a.id,
    a.album_name,
    a.created_at,
    a.modified_at,
    a.description,
    a.cover_photo_id,
    a.hostname,
    p2.photo_title AS cover_photo_title
   FROM (public.albums a
     LEFT JOIN public.photos p2 ON ((p2.id = a.cover_photo_id)));
--;;
CREATE VIEW public.full_article_audiences AS
 SELECT aa.id,
    aa.group_id,
    aa.article_id,
    aa.created_at,
    aa.modified_at,
    a.hostname,
    a.public_visibility
   FROM (public.article_audiences aa
     JOIN public.articles a ON ((aa.article_id = a.id)));
--;;
CREATE VIEW public.full_branches AS
 SELECT ab.id AS branch_id,
    ab.branch_name,
    ab.published_at,
    ab.created_at,
    ab.modified_at,
    a.id AS article_id,
    a.author,
    a.article_tags,
    a.article_title,
    a.article_url,
    a.hostname,
    a.public_visibility,
    a.created_at AS article_created_at,
    a.modified_at AS article_modified_at,
    a.summary
   FROM (public.article_branches ab
     JOIN public.articles a ON ((a.id = ab.article_id)));
--;;
CREATE TABLE public.groups (
    id character varying(36) NOT NULL,
    display_name character varying(100),
    owner_id character varying(100),
    created_at timestamp without time zone,
    modified_at timestamp without time zone,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.user_group_memberships (
    id character varying(36) NOT NULL,
    email character varying,
    alias character varying,
    group_id character varying(36),
    created_at timestamp without time zone,
    modified_at timestamp without time zone,
    hostname character varying NOT NULL
);
--;;
CREATE VIEW public.full_memberships AS
 SELECT g.id AS group_id,
    g.hostname,
    g.created_at AS group_created_at,
    g.modified_at AS group_modified_at,
    g.display_name,
    g.owner_id,
    ugm.id AS membership_id,
    ugm.email,
    ugm.alias,
    ugm.created_at AS membership_created_at
   FROM (public.groups g
     LEFT JOIN public.user_group_memberships ugm ON ((g.id = ugm.group_id)));
--;;
CREATE TABLE public.photo_versions (
    id uuid NOT NULL,
    photo_id uuid NOT NULL,
    path character varying NOT NULL,
    filename character varying NOT NULL,
    storage_driver character varying NOT NULL,
    storage_root character varying NOT NULL,
    image_category character varying,
    created_at timestamp without time zone,
    modified_at timestamp without time zone,
    hostname character varying NOT NULL
);
--;;
CREATE VIEW public.full_photos AS
 SELECT p.id,
    p.photo_title,
    p.created_at,
    p.modified_at,
    p.hostname,
    p.description,
    pv.id AS photo_version_id,
    pv.path,
    pv.photo_id,
    pv.filename,
    pv.image_category,
    pv.storage_driver,
    pv.storage_root
   FROM (public.photos p
     LEFT JOIN public.photo_versions pv ON ((pv.photo_id = p.id)));
--;;
CREATE VIEW public.full_versions AS
 SELECT av.id AS version_id,
    av.content,
    av.created_at,
    av.modified_at,
    ab.id AS branch_id,
    ab.branch_name,
    ab.published_at,
    ab.created_at AS branch_created_at,
    ab.modified_at AS branch_modified_at,
    a.id AS article_id,
    a.author,
    a.article_tags,
    a.article_title,
    a.article_url,
    a.hostname,
    a.public_visibility,
    a.created_at AS article_created_at,
    a.modified_at AS article_modified_at,
    a.summary
   FROM ((public.article_versions av
     JOIN public.article_branches ab ON ((ab.id = av.branch_id)))
     JOIN public.articles a ON ((a.id = ab.article_id)));
--;;
CREATE TABLE public.interests (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id text NOT NULL,
    project_id uuid NOT NULL,
    intent text NOT NULL,
    taste_profile jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.portfolio_entries (
    id bigint NOT NULL,
    name character varying,
    type character varying,
    url character varying,
    image_url character varying,
    description character varying,
    tags character varying,
    hostname character varying NOT NULL
);
--;;
CREATE SEQUENCE public.portfolio_entries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
--;;
CREATE TABLE public.portfolio_links (
    id bigint NOT NULL,
    name_1 character varying,
    relation character varying,
    name_2 character varying,
    description character varying,
    hostname character varying NOT NULL
);
--;;
CREATE SEQUENCE public.portfolio_links_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
--;;
CREATE TABLE public.processing_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    hostname character varying NOT NULL,
    raw_scrape_id uuid NOT NULL,
    pipeline_version character varying NOT NULL,
    techniques jsonb,
    facts jsonb,
    content jsonb,
    llm_calls jsonb,
    warnings jsonb,
    outcome character varying NOT NULL,
    error_detail jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
--;;
CREATE TABLE public.project_briefs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    version integer NOT NULL,
    content text NOT NULL,
    source text NOT NULL,
    agent_type text,
    workflow_round_id uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.project_conversations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    agent_type text NOT NULL,
    role text NOT NULL,
    content text NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.project_notes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    content text NOT NULL,
    source text DEFAULT 'text'::text NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.project_score_dimensions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    score_run_id uuid NOT NULL,
    dimension_name text NOT NULL,
    "value" numeric(4,2),
    rationale text,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.project_score_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    score_definition_id uuid NOT NULL,
    version integer NOT NULL,
    overall numeric(4,2),
    scored_at timestamp without time zone DEFAULT now(),
    brief_version integer,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.project_skills (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    parent_id uuid,
    name text NOT NULL,
    description text,
    status text DEFAULT 'identified'::text NOT NULL,
    "position" integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.project_task_generation_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    user_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.project_tasks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    user_id text NOT NULL,
    title text NOT NULL,
    description text,
    task_type text DEFAULT 'action'::text NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    "position" integer DEFAULT 0 NOT NULL,
    estimated_minutes integer,
    generation_run_id uuid,
    workflow_step_run_id uuid,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now(),
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.project_workflow_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    workflow_id uuid,
    status text DEFAULT 'pending'::text NOT NULL,
    current_step integer DEFAULT 0 NOT NULL,
    mode text DEFAULT 'manual'::text NOT NULL,
    started_at timestamp without time zone,
    completed_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now(),
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.project_workflow_step_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    workflow_run_id uuid NOT NULL,
    step_id uuid,
    "position" integer NOT NULL,
    name text NOT NULL,
    description text NOT NULL,
    agent_type text DEFAULT 'coach'::text NOT NULL,
    is_custom boolean DEFAULT false NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    output text,
    started_at timestamp without time zone,
    completed_at timestamp without time zone,
    output_kind text DEFAULT 'text'::text NOT NULL,
    execution_mode text DEFAULT 'sequential'::text NOT NULL,
    loop_until text,
    round_id uuid,
    score_run_id uuid,
    requires jsonb DEFAULT '[]'::jsonb NOT NULL,
    pending_inputs jsonb,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.projects (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id text NOT NULL,
    title text NOT NULL,
    description text,
    status text DEFAULT 'idea'::text NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    local_paths jsonb DEFAULT '[]'::jsonb NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE VIEW public.published_articles AS
 SELECT version_id,
    content,
    created_at,
    modified_at,
    branch_id,
    branch_name,
    published_at,
    branch_created_at,
    branch_modified_at,
    article_id,
    author,
    article_tags,
    article_title,
    article_url,
    hostname,
    public_visibility,
    article_created_at,
    article_modified_at,
    summary,
    rank
   FROM ( SELECT full_versions.version_id,
            full_versions.content,
            full_versions.created_at,
            full_versions.modified_at,
            full_versions.branch_id,
            full_versions.branch_name,
            full_versions.published_at,
            full_versions.branch_created_at,
            full_versions.branch_modified_at,
            full_versions.article_id,
            full_versions.author,
            full_versions.article_tags,
            full_versions.article_title,
            full_versions.article_url,
            full_versions.hostname,
            full_versions.public_visibility,
            full_versions.article_created_at,
            full_versions.article_modified_at,
            full_versions.summary,
            rank() OVER (PARTITION BY full_versions.article_id ORDER BY full_versions.published_at DESC, full_versions.created_at DESC) AS rank
           FROM public.full_versions
          WHERE (full_versions.published_at IS NOT NULL)) sq
  WHERE (rank = 1);
--;;
CREATE TABLE public.raw_scrapes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    hostname character varying NOT NULL,
    request_url character varying,
    final_url character varying,
    http_status integer,
    fetch_tier character varying,
    raw_content text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    source_kind character varying NOT NULL
);
--;;
CREATE TABLE public.recipe_audiences (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    group_id character varying NOT NULL,
    recipe_id uuid NOT NULL,
    hostname character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
--;;
CREATE TABLE public.recipe_label_assignments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    recipe_id uuid NOT NULL,
    label_id uuid NOT NULL,
    group_id uuid,
    hostname character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
--;;
CREATE TABLE public.recipe_label_groups (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying NOT NULL,
    hostname character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
--;;
CREATE TABLE public.recipe_labels (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying NOT NULL,
    group_id uuid,
    hostname character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
--;;
CREATE TABLE public.recipes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    recipe_url character varying NOT NULL,
    hostname character varying NOT NULL,
    content jsonb NOT NULL,
    original_content jsonb,
    source_url character varying,
    author character varying,
    public_visibility boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    modified_at timestamp with time zone DEFAULT now() NOT NULL,
    scrape_processing_run_id uuid,
    timeline jsonb
);
--;;
CREATE TABLE public.recommendations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    interest_id uuid NOT NULL,
    kind text NOT NULL,
    title text NOT NULL,
    source text NOT NULL,
    url text,
    est_time text,
    why text NOT NULL,
    origin text DEFAULT 'novel'::text NOT NULL,
    status text DEFAULT 'shelved'::text NOT NULL,
    added_at timestamp with time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.score_definitions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id text NOT NULL,
    name text NOT NULL,
    description text NOT NULL,
    scorer_type text DEFAULT 'general'::text NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.score_dimension_definitions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    score_definition_id uuid NOT NULL,
    name text NOT NULL,
    criteria text NOT NULL,
    "position" integer DEFAULT 0 NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.tags (
    id uuid NOT NULL,
    created_at timestamp without time zone,
    modified_at timestamp without time zone,
    name text,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.task_artifacts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    task_id uuid NOT NULL,
    artifact_type text NOT NULL,
    content text,
    url text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.themes (
    id uuid NOT NULL,
    display_name character varying(100),
    config jsonb,
    hostname character varying,
    owner_id character varying(100),
    created_at timestamp without time zone,
    modified_at timestamp without time zone
);
--;;
CREATE TABLE public.user_workspace_roots (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id text NOT NULL,
    path text NOT NULL,
    label text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.workflow_judge_records (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    step_run_id uuid NOT NULL,
    round_id uuid NOT NULL,
    brief_version integer NOT NULL,
    score_snapshot jsonb NOT NULL,
    trajectory jsonb NOT NULL,
    delta_table jsonb NOT NULL,
    policy jsonb NOT NULL,
    decision jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.workflow_rounds (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    workflow_run_id uuid NOT NULL,
    round_number integer NOT NULL,
    status text DEFAULT 'in_progress'::text NOT NULL,
    started_at timestamp without time zone DEFAULT now() NOT NULL,
    completed_at timestamp without time zone,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.workflow_steps (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    workflow_id uuid NOT NULL,
    "position" integer DEFAULT 0 NOT NULL,
    name text NOT NULL,
    description text NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    agent_type text DEFAULT 'coach'::text NOT NULL,
    output_kind text DEFAULT 'text'::text NOT NULL,
    execution_mode text DEFAULT 'sequential'::text NOT NULL,
    loop_until text,
    requires jsonb DEFAULT '[]'::jsonb NOT NULL,
    hostname character varying NOT NULL
);
--;;
CREATE TABLE public.workflows (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id text NOT NULL,
    name text NOT NULL,
    description text,
    status text DEFAULT 'draft'::text NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    hostname character varying NOT NULL
);
--;;
ALTER TABLE public.portfolio_entries ALTER COLUMN id SET DEFAULT nextval('portfolio_entries_id_seq');
--;;
ALTER TABLE public.portfolio_links ALTER COLUMN id SET DEFAULT nextval('portfolio_links_id_seq');
--;;
ALTER TABLE public.agent_definitions
    ADD CONSTRAINT agent_definitions_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.agent_definitions
    ADD CONSTRAINT agent_definitions_user_id_agent_type_key UNIQUE (user_id, agent_type);
--;;
ALTER TABLE public.albums
    ADD CONSTRAINT albums_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.albums
    ADD CONSTRAINT albums_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.article_audiences
    ADD CONSTRAINT article_audiences_group_id_article_id_key UNIQUE (group_id, article_id);
--;;
ALTER TABLE public.article_audiences
    ADD CONSTRAINT article_audiences_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.article_branches
    ADD CONSTRAINT article_branches_branch_name_article_id_key UNIQUE (branch_name, article_id);
--;;
ALTER TABLE public.article_branches
    ADD CONSTRAINT article_branches_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.article_branches
    ADD CONSTRAINT article_branches_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.article_tags
    ADD CONSTRAINT article_tags_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.article_tags
    ADD CONSTRAINT article_tags_tag_id_article_id_key UNIQUE (tag_id, article_id);
--;;
ALTER TABLE public.article_versions
    ADD CONSTRAINT article_versions_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.articles
    ADD CONSTRAINT articles_article_url_key UNIQUE (article_url);
--;;
ALTER TABLE public.articles
    ADD CONSTRAINT articles_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.articles
    ADD CONSTRAINT articles_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.groups
    ADD CONSTRAINT groups_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.groups
    ADD CONSTRAINT groups_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.interests
    ADD CONSTRAINT interests_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.interests
    ADD CONSTRAINT interests_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.interests
    ADD CONSTRAINT interests_project_id_key UNIQUE (project_id);
--;;
ALTER TABLE public.photo_versions
    ADD CONSTRAINT photo_versions_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.photos
    ADD CONSTRAINT photos_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.photos_in_albums
    ADD CONSTRAINT photos_in_albums_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.photos
    ADD CONSTRAINT photos_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.portfolio_entries
    ADD CONSTRAINT portfolio_entries_name_key UNIQUE (name);
--;;
ALTER TABLE public.portfolio_entries
    ADD CONSTRAINT portfolio_entries_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.portfolio_links
    ADD CONSTRAINT portfolio_links_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.processing_runs
    ADD CONSTRAINT processing_runs_id_hostname_key UNIQUE (id, hostname);
--;;
ALTER TABLE public.processing_runs
    ADD CONSTRAINT processing_runs_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.project_briefs
    ADD CONSTRAINT project_briefs_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.project_briefs
    ADD CONSTRAINT project_briefs_project_id_version_key UNIQUE (project_id, version);
--;;
ALTER TABLE public.project_conversations
    ADD CONSTRAINT project_conversations_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.project_notes
    ADD CONSTRAINT project_notes_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.project_score_dimensions
    ADD CONSTRAINT project_score_dimensions_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.project_score_runs
    ADD CONSTRAINT project_score_runs_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.project_score_runs
    ADD CONSTRAINT project_score_runs_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.project_skills
    ADD CONSTRAINT project_skills_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.project_task_generation_runs
    ADD CONSTRAINT project_task_generation_runs_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.project_tasks
    ADD CONSTRAINT project_tasks_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.project_tasks
    ADD CONSTRAINT project_tasks_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.project_workflow_runs
    ADD CONSTRAINT project_workflow_runs_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.project_workflow_runs
    ADD CONSTRAINT project_workflow_runs_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.project_workflow_step_runs
    ADD CONSTRAINT project_workflow_step_runs_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.project_workflow_step_runs
    ADD CONSTRAINT project_workflow_step_runs_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.projects
    ADD CONSTRAINT projects_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.raw_scrapes
    ADD CONSTRAINT raw_scrapes_id_hostname_key UNIQUE (id, hostname);
--;;
ALTER TABLE public.raw_scrapes
    ADD CONSTRAINT raw_scrapes_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.recipe_audiences
    ADD CONSTRAINT recipe_audiences_group_id_recipe_id_key UNIQUE (group_id, recipe_id);
--;;
ALTER TABLE public.recipe_audiences
    ADD CONSTRAINT recipe_audiences_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.recipe_label_assignments
    ADD CONSTRAINT recipe_label_assignments_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.recipe_label_assignments
    ADD CONSTRAINT recipe_label_assignments_recipe_id_group_id_key UNIQUE (recipe_id, group_id);
--;;
ALTER TABLE public.recipe_label_assignments
    ADD CONSTRAINT recipe_label_assignments_recipe_id_label_id_key UNIQUE (recipe_id, label_id);
--;;
ALTER TABLE public.recipe_label_groups
    ADD CONSTRAINT recipe_label_groups_id_hostname_key UNIQUE (id, hostname);
--;;
ALTER TABLE public.recipe_label_groups
    ADD CONSTRAINT recipe_label_groups_name_hostname_key UNIQUE (name, hostname);
--;;
ALTER TABLE public.recipe_label_groups
    ADD CONSTRAINT recipe_label_groups_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.recipe_labels
    ADD CONSTRAINT recipe_labels_id_hostname_key UNIQUE (id, hostname);
--;;
ALTER TABLE public.recipe_labels
    ADD CONSTRAINT recipe_labels_name_group_id_hostname_key UNIQUE (name, group_id, hostname);
--;;
ALTER TABLE public.recipe_labels
    ADD CONSTRAINT recipe_labels_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.recipes
    ADD CONSTRAINT recipes_id_hostname_key UNIQUE (id, hostname);
--;;
ALTER TABLE public.recipes
    ADD CONSTRAINT recipes_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.recipes
    ADD CONSTRAINT recipes_recipe_url_hostname_key UNIQUE (recipe_url, hostname);
--;;
ALTER TABLE public.recommendations
    ADD CONSTRAINT recommendations_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.score_definitions
    ADD CONSTRAINT score_definitions_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.score_definitions
    ADD CONSTRAINT score_definitions_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.score_dimension_definitions
    ADD CONSTRAINT score_dimension_definitions_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.tags
    ADD CONSTRAINT tags_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.tags
    ADD CONSTRAINT tags_name_hostname_key UNIQUE (name, hostname);
--;;
ALTER TABLE public.tags
    ADD CONSTRAINT tags_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.task_artifacts
    ADD CONSTRAINT task_artifacts_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.themes
    ADD CONSTRAINT themes_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.user_group_memberships
    ADD CONSTRAINT user_group_memberships_group_id_email_key UNIQUE (group_id, email);
--;;
ALTER TABLE public.user_group_memberships
    ADD CONSTRAINT user_group_memberships_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.user_workspace_roots
    ADD CONSTRAINT user_workspace_roots_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.user_workspace_roots
    ADD CONSTRAINT user_workspace_roots_user_id_path_key UNIQUE (user_id, path);
--;;
ALTER TABLE public.workflow_judge_records
    ADD CONSTRAINT workflow_judge_records_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.workflow_rounds
    ADD CONSTRAINT workflow_rounds_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.workflow_rounds
    ADD CONSTRAINT workflow_rounds_workflow_run_id_round_number_key UNIQUE (workflow_run_id, round_number);
--;;
ALTER TABLE public.workflow_steps
    ADD CONSTRAINT workflow_steps_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.workflows
    ADD CONSTRAINT workflows_id_hostname_unique UNIQUE (id, hostname);
--;;
ALTER TABLE public.workflows
    ADD CONSTRAINT workflows_pkey PRIMARY KEY (id);
--;;
CREATE INDEX idx_interests_user_id ON public.interests USING btree (user_id);
--;;
CREATE INDEX idx_processing_runs_raw_scrape_id ON public.processing_runs USING btree (raw_scrape_id);
--;;
CREATE INDEX idx_recipe_audiences_recipe_id ON public.recipe_audiences USING btree (recipe_id);
--;;
CREATE INDEX idx_recipe_label_assignments_recipe_id ON public.recipe_label_assignments USING btree (recipe_id);
--;;
CREATE INDEX idx_recipes_hostname ON public.recipes USING btree (hostname);
--;;
CREATE INDEX idx_recipes_scrape_processing_run_id ON public.recipes USING btree (scrape_processing_run_id);
--;;
CREATE INDEX idx_recommendations_interest_id ON public.recommendations USING btree (interest_id);
--;;
CREATE INDEX idx_task_artifacts_task_id ON public.task_artifacts USING btree (task_id);
--;;
CREATE INDEX project_tasks_project_id_position_idx ON public.project_tasks USING btree (project_id, "position");
--;;
CREATE INDEX project_tasks_project_id_status_idx ON public.project_tasks USING btree (project_id, status);
--;;
ALTER TABLE public.article_audiences
    ADD CONSTRAINT article_audiences_article_hostname_fk FOREIGN KEY (article_id, hostname) REFERENCES public.articles(id, hostname);
--;;
ALTER TABLE public.article_branches
    ADD CONSTRAINT article_branches_article_hostname_fk FOREIGN KEY (article_id, hostname) REFERENCES public.articles(id, hostname);
--;;
ALTER TABLE public.article_tags
    ADD CONSTRAINT article_tags_article_hostname_fk FOREIGN KEY (article_id, hostname) REFERENCES public.articles(id, hostname);
--;;
ALTER TABLE public.article_tags
    ADD CONSTRAINT article_tags_tag_hostname_fk FOREIGN KEY (tag_id, hostname) REFERENCES public.tags(id, hostname);
--;;
ALTER TABLE public.article_versions
    ADD CONSTRAINT article_versions_branch_hostname_fk FOREIGN KEY (branch_id, hostname) REFERENCES public.article_branches(id, hostname);
--;;
ALTER TABLE public.photos_in_albums
    ADD CONSTRAINT fk_album FOREIGN KEY (album_id) REFERENCES public.albums(id);
--;;
ALTER TABLE public.article_audiences
    ADD CONSTRAINT fk_article_audiences__articles FOREIGN KEY (article_id) REFERENCES public.articles(id);
--;;
ALTER TABLE public.article_audiences
    ADD CONSTRAINT fk_article_audiences__groups FOREIGN KEY (group_id) REFERENCES public.groups(id);
--;;
ALTER TABLE public.article_tags
    ADD CONSTRAINT fk_article_tags_articles FOREIGN KEY (article_id) REFERENCES public.articles(id);
--;;
ALTER TABLE public.article_branches
    ADD CONSTRAINT fk_articles FOREIGN KEY (article_id) REFERENCES public.articles(id);
--;;
ALTER TABLE public.article_versions
    ADD CONSTRAINT fk_branches FOREIGN KEY (branch_id) REFERENCES public.article_branches(id);
--;;
ALTER TABLE public.photos_in_albums
    ADD CONSTRAINT fk_photo FOREIGN KEY (photo_id) REFERENCES public.photos(id);
--;;
ALTER TABLE public.photo_versions
    ADD CONSTRAINT fk_photo_versions_photo FOREIGN KEY (photo_id) REFERENCES public.photos(id);
--;;
ALTER TABLE public.recipes
    ADD CONSTRAINT fk_recipes_scrape_processing_run FOREIGN KEY (scrape_processing_run_id) REFERENCES public.processing_runs(id) ON DELETE SET NULL;
--;;
ALTER TABLE public.project_workflow_step_runs
    ADD CONSTRAINT fk_step_run_round FOREIGN KEY (round_id) REFERENCES public.workflow_rounds(id) ON DELETE SET NULL;
--;;
ALTER TABLE public.project_workflow_step_runs
    ADD CONSTRAINT fk_step_run_score_run FOREIGN KEY (score_run_id) REFERENCES public.project_score_runs(id) ON DELETE SET NULL;
--;;
ALTER TABLE public.article_tags
    ADD CONSTRAINT fk_tags FOREIGN KEY (tag_id) REFERENCES public.tags(id);
--;;
ALTER TABLE public.interests
    ADD CONSTRAINT interests_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.interests
    ADD CONSTRAINT interests_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES public.projects(id, hostname);
--;;
ALTER TABLE public.photo_versions
    ADD CONSTRAINT photo_versions_photo_hostname_fk FOREIGN KEY (photo_id, hostname) REFERENCES public.photos(id, hostname);
--;;
ALTER TABLE public.photos_in_albums
    ADD CONSTRAINT photos_in_albums_album_hostname_fk FOREIGN KEY (album_id, hostname) REFERENCES public.albums(id, hostname);
--;;
ALTER TABLE public.photos_in_albums
    ADD CONSTRAINT photos_in_albums_photo_hostname_fk FOREIGN KEY (photo_id, hostname) REFERENCES public.photos(id, hostname);
--;;
ALTER TABLE public.processing_runs
    ADD CONSTRAINT processing_runs_raw_scrape_id_hostname_fkey FOREIGN KEY (raw_scrape_id, hostname) REFERENCES public.raw_scrapes(id, hostname) ON DELETE CASCADE;
--;;
ALTER TABLE public.project_briefs
    ADD CONSTRAINT project_briefs_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.project_briefs
    ADD CONSTRAINT project_briefs_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES public.projects(id, hostname);
--;;
ALTER TABLE public.project_briefs
    ADD CONSTRAINT project_briefs_workflow_round_id_fkey FOREIGN KEY (workflow_round_id) REFERENCES public.workflow_rounds(id) ON DELETE SET NULL;
--;;
ALTER TABLE public.project_conversations
    ADD CONSTRAINT project_conversations_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.project_conversations
    ADD CONSTRAINT project_conversations_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES public.projects(id, hostname);
--;;
ALTER TABLE public.project_notes
    ADD CONSTRAINT project_notes_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.project_notes
    ADD CONSTRAINT project_notes_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES public.projects(id, hostname);
--;;
ALTER TABLE public.project_score_dimensions
    ADD CONSTRAINT project_score_dimensions_project_score_runs_hostname_fk FOREIGN KEY (score_run_id, hostname) REFERENCES public.project_score_runs(id, hostname);
--;;
ALTER TABLE public.project_score_dimensions
    ADD CONSTRAINT project_score_dimensions_score_run_id_fkey FOREIGN KEY (score_run_id) REFERENCES public.project_score_runs(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.project_score_runs
    ADD CONSTRAINT project_score_runs_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.project_score_runs
    ADD CONSTRAINT project_score_runs_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES public.projects(id, hostname);
--;;
ALTER TABLE public.project_score_runs
    ADD CONSTRAINT project_score_runs_score_definition_id_fkey FOREIGN KEY (score_definition_id) REFERENCES public.score_definitions(id);
--;;
ALTER TABLE public.project_skills
    ADD CONSTRAINT project_skills_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.project_skills(id);
--;;
ALTER TABLE public.project_skills
    ADD CONSTRAINT project_skills_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.project_skills
    ADD CONSTRAINT project_skills_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES public.projects(id, hostname);
--;;
ALTER TABLE public.project_task_generation_runs
    ADD CONSTRAINT project_task_generation_runs_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.project_task_generation_runs
    ADD CONSTRAINT project_task_generation_runs_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES public.projects(id, hostname);
--;;
ALTER TABLE public.project_tasks
    ADD CONSTRAINT project_tasks_generation_run_id_fkey FOREIGN KEY (generation_run_id) REFERENCES public.project_task_generation_runs(id);
--;;
ALTER TABLE public.project_tasks
    ADD CONSTRAINT project_tasks_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.project_tasks
    ADD CONSTRAINT project_tasks_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES public.projects(id, hostname);
--;;
ALTER TABLE public.project_tasks
    ADD CONSTRAINT project_tasks_workflow_step_run_id_fkey FOREIGN KEY (workflow_step_run_id) REFERENCES public.project_workflow_step_runs(id);
--;;
ALTER TABLE public.project_workflow_runs
    ADD CONSTRAINT project_workflow_runs_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.project_workflow_runs
    ADD CONSTRAINT project_workflow_runs_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES public.projects(id, hostname);
--;;
ALTER TABLE public.project_workflow_runs
    ADD CONSTRAINT project_workflow_runs_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES public.workflows(id);
--;;
ALTER TABLE public.project_workflow_step_runs
    ADD CONSTRAINT project_workflow_step_runs_project_workflow_runs_hostname_fk FOREIGN KEY (workflow_run_id, hostname) REFERENCES public.project_workflow_runs(id, hostname);
--;;
ALTER TABLE public.project_workflow_step_runs
    ADD CONSTRAINT project_workflow_step_runs_step_id_fkey FOREIGN KEY (step_id) REFERENCES public.workflow_steps(id) ON DELETE SET NULL;
--;;
ALTER TABLE public.project_workflow_step_runs
    ADD CONSTRAINT project_workflow_step_runs_workflow_run_id_fkey FOREIGN KEY (workflow_run_id) REFERENCES public.project_workflow_runs(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.recipe_audiences
    ADD CONSTRAINT recipe_audiences_recipe_id_hostname_fkey FOREIGN KEY (recipe_id, hostname) REFERENCES public.recipes(id, hostname) ON DELETE CASCADE;
--;;
ALTER TABLE public.recipe_label_assignments
    ADD CONSTRAINT recipe_label_assignments_label_id_hostname_fkey FOREIGN KEY (label_id, hostname) REFERENCES public.recipe_labels(id, hostname) ON DELETE CASCADE;
--;;
ALTER TABLE public.recipe_label_assignments
    ADD CONSTRAINT recipe_label_assignments_recipe_id_hostname_fkey FOREIGN KEY (recipe_id, hostname) REFERENCES public.recipes(id, hostname) ON DELETE CASCADE;
--;;
ALTER TABLE public.recipe_labels
    ADD CONSTRAINT recipe_labels_group_id_hostname_fkey FOREIGN KEY (group_id, hostname) REFERENCES public.recipe_label_groups(id, hostname) ON DELETE CASCADE;
--;;
ALTER TABLE public.recommendations
    ADD CONSTRAINT recommendations_interest_id_fkey FOREIGN KEY (interest_id) REFERENCES public.interests(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.recommendations
    ADD CONSTRAINT recommendations_interests_hostname_fk FOREIGN KEY (interest_id, hostname) REFERENCES public.interests(id, hostname);
--;;
ALTER TABLE public.score_dimension_definitions
    ADD CONSTRAINT score_dimension_definitions_score_definition_id_fkey FOREIGN KEY (score_definition_id) REFERENCES public.score_definitions(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.score_dimension_definitions
    ADD CONSTRAINT score_dimension_definitions_score_definitions_hostname_fk FOREIGN KEY (score_definition_id, hostname) REFERENCES public.score_definitions(id, hostname);
--;;
ALTER TABLE public.task_artifacts
    ADD CONSTRAINT task_artifacts_project_tasks_hostname_fk FOREIGN KEY (task_id, hostname) REFERENCES public.project_tasks(id, hostname);
--;;
ALTER TABLE public.task_artifacts
    ADD CONSTRAINT task_artifacts_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.project_tasks(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.workflow_judge_records
    ADD CONSTRAINT workflow_judge_records_project_workflow_step_runs_hostname_fk FOREIGN KEY (step_run_id, hostname) REFERENCES public.project_workflow_step_runs(id, hostname);
--;;
ALTER TABLE public.workflow_judge_records
    ADD CONSTRAINT workflow_judge_records_round_id_fkey FOREIGN KEY (round_id) REFERENCES public.workflow_rounds(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.workflow_judge_records
    ADD CONSTRAINT workflow_judge_records_step_run_id_fkey FOREIGN KEY (step_run_id) REFERENCES public.project_workflow_step_runs(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.workflow_rounds
    ADD CONSTRAINT workflow_rounds_project_workflow_runs_hostname_fk FOREIGN KEY (workflow_run_id, hostname) REFERENCES public.project_workflow_runs(id, hostname);
--;;
ALTER TABLE public.workflow_rounds
    ADD CONSTRAINT workflow_rounds_workflow_run_id_fkey FOREIGN KEY (workflow_run_id) REFERENCES public.project_workflow_runs(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.workflow_steps
    ADD CONSTRAINT workflow_steps_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES public.workflows(id) ON DELETE CASCADE;
--;;
ALTER TABLE public.workflow_steps
    ADD CONSTRAINT workflow_steps_workflows_hostname_fk FOREIGN KEY (workflow_id, hostname) REFERENCES public.workflows(id, hostname);