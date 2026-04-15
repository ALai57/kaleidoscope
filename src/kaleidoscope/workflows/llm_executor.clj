(ns kaleidoscope.workflows.llm-executor
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kaleidoscope.api.agents :as agents-api]
            [kaleidoscope.api.tasks :as tasks-api]
            [kaleidoscope.persistence.briefs :as briefs-persistence]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.workspace-roots :as workspace-roots-persistence]
            [kaleidoscope.persistence.workflows :as persistence]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.scoring.llm-scorer :as llm-scorer]
            [kaleidoscope.tasks.planner :as task-planner]
            [kaleidoscope.utils.core :as utils]
            [kaleidoscope.utils.local-files :as local-files]
            [kaleidoscope.utils.path-matching :as path-matching]
            [kaleidoscope.workflows.protocol :as protocol]
            [taoensso.timbre :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def ^:private anthropic-messages-url
  "https://api.anthropic.com/v1/messages")

(def ^:private default-model
  "claude-opus-4-6")

(def ^:private anthropic-version
  "2023-06-01")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Low-level HTTP helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- post-anthropic-sync
  "Synchronous POST to Anthropic messages API. Returns parsed JSON body."
  [api-key body-map]
  (let [body-str (json/encode body-map)
        request  (-> (HttpRequest/newBuilder)
                     (.uri (URI/create anthropic-messages-url))
                     (.header "Content-Type" "application/json")
                     (.header "x-api-key" api-key)
                     (.header "anthropic-version" anthropic-version)
                     (.POST (HttpRequest$BodyPublishers/ofString body-str))
                     (.build))
        client   (HttpClient/newHttpClient)
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        status   (.statusCode response)
        parsed   (json/decode (.body response) true)]
    (when-not (= 200 status)
      (log/errorf "Anthropic API error %d: %s" status (.body response))
      (throw (ex-info "Anthropic API error" {:status status :body parsed})))
    parsed))

(defn- extract-text [response]
  (-> response :content first :text))

(defn- strip-fences [text]
  (-> text
      str/trim
      (str/replace #"(?s)^```(?:json)?\s*" "")
      (str/replace #"\s*```$" "")
      str/trim))

(defn- write-sse-event!
  "Write a single SSE data event to the output-stream."
  [^java.io.OutputStream output-stream data]
  (let [writer (java.io.OutputStreamWriter. output-stream "UTF-8")]
    (.write writer (str "data: " (json/encode data) "\n\n"))
    (.flush writer)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step execution streaming
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- build-step-system-prompt
  "Return the system prompt for a step's agent-type, augmented with project context.
   Uses custom-prompt if provided, otherwise falls back to the built-in agent prompt."
  [step-run project custom-prompt]
  (let [base-prompt (or custom-prompt
                        (agents/get-system-prompt (:agent-type step-run)))
        project-ctx (format "\n\n---\nProject context:\nTitle: %s\nDescription: %s"
                            (:title project)
                            (or (:description project) "No description provided"))]
    (str base-prompt project-ctx)))

(defn- stream-step-to-output!
  "Stream a Claude response to output-stream using workflow SSE event format.
   Returns the full assistant text."
  [api-key system-prompt messages output-stream]
  (let [body-map {:model      default-model
                  :max_tokens 4096
                  :system     system-prompt
                  :stream     true
                  :messages   messages}
        body-str (json/encode body-map)
        request  (-> (HttpRequest/newBuilder)
                     (.uri (URI/create anthropic-messages-url))
                     (.header "Content-Type" "application/json")
                     (.header "x-api-key" api-key)
                     (.header "anthropic-version" anthropic-version)
                     (.POST (HttpRequest$BodyPublishers/ofString body-str))
                     (.build))
        client   (HttpClient/newHttpClient)
        response (.send client request (HttpResponse$BodyHandlers/ofLines))
        sb       (StringBuilder.)]
    (let [^java.util.stream.Stream lines (.body response)]
      (.forEach lines
                (reify java.util.function.Consumer
                  (accept [_ line]
                    (when (str/starts-with? line "data: ")
                      (let [data (subs line 6)]
                        (when (not= data "[DONE]")
                          (try
                            (let [parsed (json/decode data true)
                                  delta  (get-in parsed [:delta :text])]
                              (when delta
                                (.append sb delta)
                                (write-sse-event! output-stream {:event "token" :data delta})))
                            (catch Exception _)))))))))
    (str sb)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow recommendation (classification)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private classifier-system-prompt
  "You are a workflow classifier. Given a project description and a list of
available workflows, rank the workflows by how well they match the project.

Return a JSON array of objects, sorted by confidence (highest first):
[{\"workflow_id\": \"<uuid>\", \"confidence\": <0.0-1.0>, \"rationale\": \"<1-2 sentence explanation>\"}]

Return ONLY the JSON array, no additional text.")

(defn- build-classification-prompt
  [project workflows]
  (let [wf-text (str/join "\n\n"
                  (map-indexed
                    (fn [i {:keys [id name description]}]
                      (format "%d. ID: %s\n   Name: %s\n   Description: %s"
                              (inc i) id name (or description "No description")))
                    workflows))]
    (format
     "Project Title: %s
Project Description: %s

Available workflows:
%s

Rank these workflows by how well they match the project."
     (:title project)
     (or (:description project) "No description provided")
     wf-text)))

(defn- parse-recommendations
  [text workflows]
  (let [wf-by-id (into {} (map (fn [wf] [(str (:id wf)) wf]) workflows))]
    (try
      (->> (json/decode (strip-fences text) true)
           (keep (fn [{:keys [workflow_id confidence rationale]}]
                   (when-let [wf (get wf-by-id workflow_id)]
                     {:workflow-id (parse-uuid workflow_id)
                      :name        (:name wf)
                      :confidence  (double (or confidence 0.0))
                      :rationale   (str rationale)})))
           (sort-by :confidence >)
           vec)
      (catch Exception e
        (log/errorf "Failed to parse workflow classification: %s\nText: %s" e text)
        []))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Score step helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- build-effective-project
  "Return a project map with description replaced by the current brief content, if available."
  [db project]
  (if-let [brief (briefs-persistence/get-latest-brief db (:id project))]
    (assoc project :description (:content brief) :brief-version (:version brief))
    project))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Requirement resolution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn resolve-code-context-path
  "Pure function. Returns {:path \"...\"} if path can be resolved automatically,
   or {:needs-input true :question \"...\" :candidates [...]} if user input is needed."
  [project workspace-roots]
  (cond
    (seq (:local-paths project))
    {:path (first (:local-paths project))}

    (seq workspace-roots)
    (let [candidates (path-matching/scan-workspace-roots (map :path workspace-roots))
          {:keys [best ranked]} (path-matching/find-best-match candidates project)]
      (if best
        {:path (:path best)}
        {:needs-input true
         :question    "Which codebase should I review?"
         :candidates  ranked}))

    :else
    {:needs-input true
     :question    "Which codebase should I review?"
     :candidates  []}))

(def ^:private requirement-resolvers
  {"code_context_path"
   (fn [db _step-run project]
     (let [user-id         (:user-id project)
           workspace-roots (workspace-roots-persistence/get-workspace-roots db user-id)]
       (resolve-code-context-path project workspace-roots)))})

(defn- resolve-requirements!
  "For each requirement declared on the step run, call the registered resolver.
   If any resolver needs user input: writes pending_inputs onto the step run,
   sets status to awaiting_input, emits a step_complete SSE event, and returns
   :needs-user-input. Otherwise returns a map of resolved inputs."
  [db step-run project output-stream]
  (let [requires (get step-run :requires [])]
    (if (empty? requires)
      {}
      (loop [remaining requires resolved {}]
        (if (empty? remaining)
          resolved
          (let [req      (first remaining)
                kind     (:kind req)
                resolver (get requirement-resolvers kind)]
            (if-not resolver
              (do (log/warnf "No resolver for requirement kind '%s' — skipping" kind)
                  (recur (rest remaining) resolved))
              (let [result (resolver db step-run project)]
                (if (:needs-input result)
                  (do (persistence/update-step-run! db (:id step-run)
                                                    {:status         "awaiting_input"
                                                     :pending-inputs {:kind       kind
                                                                       :question   (:question result)
                                                                       :candidates (:candidates result)}})
                      (write-sse-event! output-stream
                                        {:event "step_complete"
                                         :data  (persistence/get-step-run db (:id step-run))})
                      :needs-user-input)
                  (recur (rest remaining)
                         (assoc resolved (keyword kind) result)))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Decision step helpers (trajectory + delta computation)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- format-score-run-for-judge
  "Convert a score run with dimensions into the judge's expected shape."
  [score-run]
  {:overall    (double (or (:overall score-run) 0.0))
   :dimensions (mapv (fn [d]
                       {:name      (or (:dimension-name d) "unknown")
                        :value     (double (or (:value d) 0.0))
                        :rationale (or (:rationale d) "")})
                     (:dimensions score-run))})

(defn- compute-trajectory-and-deltas
  "Derive trajectory (per-dimension score history) and delta table (last round vs previous).
   Queries all completed rounds for the run, pulling score data from their step runs."
  [db workflow-run-id current-round-id]
  (let [;; Get all completed rounds in order (the current round is in_progress)
        all-rounds     (persistence/get-all-rounds db workflow-run-id)
        completed-rounds (filter #(= "completed" (:status %)) all-rounds)

        ;; For each completed round, collect per-advisor dimension scores
        round-scores   (mapv (fn [round]
                               (let [score-step-runs (persistence/get-step-runs-by-round-and-mode
                                                       db workflow-run-id (:id round) "parallel" nil)]
                                 {:round-number (:round-number round)
                                  :scores       (reduce (fn [acc sr]
                                                          (if-let [score-run-id (:score-run-id sr)]
                                                            (let [sr-data (projects-persistence/get-score-run db score-run-id)]
                                                              (assoc acc (:agent-type sr)
                                                                     (format-score-run-for-judge sr-data)))
                                                            acc))
                                                        {} score-step-runs)}))
                             completed-rounds)

        ;; Also get scores for the current round (just run in parallel — they're now completed)
        current-score-step-runs (persistence/get-step-runs-by-round-and-mode
                                  db workflow-run-id current-round-id "parallel" nil)
        current-scores (reduce (fn [acc sr]
                                 (if-let [score-run-id (:score-run-id sr)]
                                   (let [sr-data (projects-persistence/get-score-run db score-run-id)]
                                     (assoc acc (:agent-type sr)
                                            (format-score-run-for-judge sr-data)))
                                   acc))
                               {} current-score-step-runs)

        all-scores-by-round (conj round-scores {:round-number (count round-scores) :scores current-scores})

        ;; Build trajectory: {"agent / dimension" -> [v1, v2, ...]}
        trajectory     (reduce (fn [traj {:keys [scores]}]
                                 (reduce (fn [t [agent-type score-data]]
                                           (let [agent-str (name agent-type)]
                                             (reduce (fn [t2 dim]
                                                       (let [key (str agent-str " / " (:name dim))]
                                                         (update t2 key (fnil conj []) (:value dim))))
                                                     t (:dimensions score-data))))
                                         traj scores))
                               {} all-scores-by-round)

        ;; Compute deltas: compare last two rounds
        ;; Find the previous-round scores (second to last in all-scores-by-round)
        prev-round     (when (>= (count all-scores-by-round) 2)
                         (nth all-scores-by-round (- (count all-scores-by-round) 2)))
        prev-by-key    (when prev-round
                         (reduce (fn [acc [agent-type score-data]]
                                   (let [agent-str (name agent-type)]
                                     (reduce (fn [a2 dim]
                                               (assoc a2 (str agent-str " / " (:name dim)) (:value dim)))
                                             acc (:dimensions score-data))))
                                 {} (:scores prev-round)))

        deltas         (when prev-by-key
                         (reduce (fn [acc [dim-key values]]
                                   (let [curr-val (last values)
                                         prev-val (get prev-by-key dim-key)
                                         delta    (when prev-val (- curr-val prev-val))]
                                     (if delta
                                       (assoc acc dim-key {:delta     delta
                                                           :regressed (< delta 0)})
                                       acc)))
                                 {} trajectory))]
    {:trajectory trajectory
     :deltas     (or deltas {})}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step execution dispatch (by output-kind)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti execute-step-by-kind!
  "Dispatch step execution based on :output-kind in the step-run.
   Each method receives [executor db project step-run resolved-inputs output-stream]."
  (fn [_executor _db _project step-run _resolved-inputs _output-stream]
    (keyword (or (:output-kind step-run) "text"))))

(defmethod execute-step-by-kind! :text
  [executor db project step-run _resolved-inputs output-stream]
  (let [user-id       (:user-id project)
        custom-prompt (agents-api/get-custom-system-prompt
                       db user-id (:agent-type step-run))
        system-prompt (build-step-system-prompt step-run project custom-prompt)
        user-message  (:description step-run)
        full-output   (stream-step-to-output! (:api-key executor)
                                              system-prompt
                                              [{:role "user" :content user-message}]
                                              output-stream)
        completed     (persistence/update-step-run! db (:id step-run)
                                                    {:status       "completed"
                                                     :output       full-output
                                                     :completed-at (utils/now)})]
    (write-sse-event! output-stream {:event "step_complete" :data completed})
    full-output))

(defmethod execute-step-by-kind! :clarify
  [executor db project step-run _resolved-inputs output-stream]
  (let [planner (task-planner/make-llm-planner (:api-key executor))]
    (tasks-api/clarify-description-step! db planner project step-run output-stream)))

(defmethod execute-step-by-kind! :tasks
  [executor db project step-run _resolved-inputs output-stream]
  (let [planner       (task-planner/make-llm-planner (:api-key executor))
        user-id       (:user-id project)
        ;; Use latest brief content if available
        latest-brief  (briefs-persistence/get-latest-brief db (:id project))
        brief-content (:content latest-brief)
        ;; Fetch unresolved dimensions from the latest judge record
        judge-record  (persistence/get-latest-judge-record db (:workflow-run-id step-run))
        unresolved    (when judge-record
                        (get-in judge-record [:decision :unresolved] []))
        ;; Build enriched project: substitute brief content and append unresolved gaps
        enriched-desc (cond-> (or brief-content (:description project) "")
                        (seq unresolved)
                        (str "\n\n## Investigation needed\n"
                             "The following gaps were identified during the review and should become investigation tasks:\n"
                             (str/join "\n" (map #(str "- " %) unresolved))))
        enriched-proj (assoc project :description enriched-desc)
        tasks         (tasks-api/run-task-generation! db planner enriched-proj user-id output-stream
                                                      {:step-run-id (:id step-run)})
        summary       (format "Generated %d tasks." (count tasks))
        completed     (persistence/update-step-run! db (:id step-run)
                                                    {:status       "completed"
                                                     :output       summary
                                                     :completed-at (utils/now)})]
    (write-sse-event! output-stream {:event "step_complete" :data completed})
    summary))

(defmethod execute-step-by-kind! :score
  [executor db project step-run resolved-inputs output-stream]
  (let [user-id       (:user-id project)
        agent-type    (:agent-type step-run)
        definition    (projects-persistence/get-default-score-definition-by-scorer-type db user-id agent-type)
        _             (when-not definition
                        (throw (ex-info "No score definition found for agent type"
                                        {:agent-type agent-type :user-id user-id})))
        ;; Score using the current brief if available, else fall back to project description
        eff-project   (build-effective-project db project)
        brief-version (:brief-version eff-project)
        ;; Check if a code context path was resolved for this step
        code-path     (get-in resolved-inputs [:code_context_path :path])
        ;; Read and format code context when a path is available
        code-context  (when code-path
                        (let [result (local-files/read-local-paths [code-path])]
                          (local-files/format-code-context
                            {:root     code-path
                             :files    (:files result)
                             :not-read (:not-read result)
                             :skipped  (:skipped result)
                             :strategy (if (.isDirectory (java.io.File. code-path))
                                         "recursive"
                                         "direct")})))
        ;; Build scoring prompt — with or without code context
        system-prompt (agents/get-system-prompt (:scorer-type definition))
        user-prompt   (if code-context
                        (agents/build-scoring-user-prompt-with-code
                          (select-keys eff-project [:title :description])
                          definition
                          code-context)
                        (agents/build-scoring-user-prompt
                          (select-keys eff-project [:title :description])
                          definition))
        score-result  (llm-scorer/score-with-user-prompt
                        (:api-key executor) system-prompt user-prompt definition)
        score-run     (projects-persistence/insert-score-run! db (:id project) (:id definition)
                                                              (assoc score-result :brief-version brief-version))
        output-data   (cond-> {:score-run-id (str (:id score-run))}
                        code-path (assoc :context-path code-path))
        completed     (persistence/update-step-run! db (:id step-run)
                                                    {:status       "completed"
                                                     :output       (json/encode output-data)
                                                     :score-run-id (:id score-run)
                                                     :completed-at (utils/now)})]
    (log/infof "Score step completed for agent=%s run=%s overall=%.2f%s"
               agent-type (:workflow-run-id step-run) (double (:overall score-result))
               (if code-path (str " context-path=" code-path) ""))
    (write-sse-event! output-stream {:event "step_complete" :data completed})
    score-result))

(defmethod execute-step-by-kind! :decision
  [executor db project step-run _resolved-inputs output-stream]
  (let [run-id       (:workflow-run-id step-run)
        round-id     (:round-id step-run)
        run          (persistence/get-workflow-run db run-id)
        config       (or (:config run) {})
        max-rounds   (get config :max-rounds 2)
        thresholds   (get config :thresholds {:pm 6.5 :engineering_lead 6.0 :default 6.0})
        deadband     (get config :deadband 0.5)

        ;; Determine current round number
        round             (persistence/get-round db round-id)
        current-round-num (:round-number round)

        ;; Get score step runs for this round (all statuses — parallel steps are now completed/failed)
        score-step-runs (persistence/get-step-runs-by-round-and-mode db run-id round-id "parallel" nil)
        scores-map      (reduce (fn [acc sr]
                                  (if-let [score-run-id (:score-run-id sr)]
                                    (let [sr-data (projects-persistence/get-score-run db score-run-id)]
                                      (assoc acc (:agent-type sr)
                                             (format-score-run-for-judge sr-data)))
                                    ;; Score step failed — insert sentinel
                                    (assoc acc (:agent-type sr)
                                           {:failed true :agent (:agent-type sr) :reason "missing"}))
                                  ) {} score-step-runs)

        ;; Compute trajectory and deltas
        {:keys [trajectory deltas]} (compute-trajectory-and-deltas db run-id round-id)

        ;; Get current brief
        latest-brief  (briefs-persistence/get-latest-brief db (:id project))
        brief-content (or (:content latest-brief) (:description project) "")
        brief-version (or (:version latest-brief) 0)

        ;; Build judge input
        judge-input  {:brief         brief-content
                      :current_round current-round-num
                      :max_rounds    max-rounds
                      :thresholds    thresholds
                      :deadband      deadband
                      :scores        scores-map
                      :trajectory    trajectory
                      :deltas        deltas}

        ;; Call judge LLM
        response     (post-anthropic-sync (:api-key executor)
                                          {:model      default-model
                                           :max_tokens 2048
                                           :system     agents/team-lead-system-prompt
                                           :messages   [{:role "user"
                                                         :content (json/encode judge-input)}]})
        raw-text     (extract-text response)
        decision     (try
                       (json/decode (strip-fences raw-text) true)
                       (catch Exception e
                         (log/errorf "Failed to parse judge decision: %s\nText: %s" e raw-text)
                         {:action "proceed" :unresolved [] :summary "Parse error — proceeding."
                          :rationale "Judge output could not be parsed."}))

        ;; Persist the audit record
        _            (persistence/create-judge-record! db
                                                       {:step-run-id    (:id step-run)
                                                        :round-id       round-id
                                                        :brief-version  brief-version
                                                        :score-snapshot scores-map
                                                        :trajectory     trajectory
                                                        :delta-table    deltas
                                                        :policy         (assoc config
                                                                               :current-round current-round-num
                                                                               :max-rounds    max-rounds)
                                                        :decision       decision})

        output-str   (json/encode decision)
        new-status   (if (= "clarify" (:action decision)) "awaiting_input" "completed")
        completed    (persistence/update-step-run! db (:id step-run)
                                                   {:status       new-status
                                                    :output       output-str
                                                    :completed-at (utils/now)})]

    (log/infof "Judge decision for run=%s round=%d action=%s"
               run-id current-round-num (:action decision))
    (write-sse-event! output-stream {:event "step_complete" :data completed})
    decision))

(defmethod execute-step-by-kind! :refine
  [executor db project step-run _resolved-inputs output-stream]
  (let [round-id          (:round-id step-run)
        refinement-prompt (:description step-run)
        ;; Get current brief
        latest-brief      (briefs-persistence/get-latest-brief db (:id project))
        brief-content     (or (:content latest-brief) (:description project) "")
        ;; Build advisor refinement prompt
        user-message      (format "Project Brief:\n\n%s\n\n---\n\nGap to address:\n%s"
                                  brief-content refinement-prompt)
        response          (post-anthropic-sync (:api-key executor)
                                               {:model      default-model
                                                :max_tokens 1024
                                                :system     agents/advisor-refinement-system-prompt
                                                :messages   [{:role "user" :content user-message}]})
        additional        (extract-text response)
        ;; Write new brief version
        new-brief         (briefs-persistence/create-brief! db
                                                            {:project-id        (:id project)
                                                             :content           (str brief-content
                                                                                    "\n\n---\n"
                                                                                    additional)
                                                             :source            "advisor_refinement"
                                                             :agent-type        (:agent-type step-run)
                                                             :workflow-round-id round-id})
        completed         (persistence/update-step-run! db (:id step-run)
                                                        {:status       "completed"
                                                         :output       (format "Brief updated (version %d)"
                                                                               (:version new-brief))
                                                         :completed-at (utils/now)})]
    (log/infof "Refinement step completed for agent=%s new-brief-version=%d"
               (:agent-type step-run) (:version new-brief))
    (write-sse-event! output-stream {:event "brief_updated"
                                     :data  {:version    (:version new-brief)
                                             :source     "advisor_refinement"
                                             :agent-type (:agent-type step-run)}})
    (write-sse-event! output-stream {:event "step_complete" :data completed})
    new-brief))

(defmethod execute-step-by-kind! :default
  [executor db project step-run resolved-inputs output-stream]
  (log/warnf "Unknown output-kind '%s' for step '%s'; falling back to :text"
             (:output-kind step-run) (:name step-run))
  (execute-step-by-kind! executor db project (assoc step-run :output-kind "text") resolved-inputs output-stream))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LLMExecutor record
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord LLMExecutor [api-key]
  protocol/IWorkflowExecutor

  (execute-step! [this db project step-run output-stream]
    (log/infof "LLMExecutor: executing step '%s' (agent=%s output-kind=%s)"
               (:name step-run) (:agent-type step-run) (:output-kind step-run))
    (persistence/update-step-run! db (:id step-run)
                                  {:status     "running"
                                   :started-at (utils/now)})
    (try
      (let [resolved (resolve-requirements! db step-run project output-stream)]
        (when-not (= :needs-user-input resolved)
          (execute-step-by-kind! this db project step-run resolved output-stream)))
      (catch Exception e
        (log/errorf "Step execution failed for step-run %s: %s" (:id step-run) e)
        (persistence/update-step-run! db (:id step-run)
                                      {:status       "failed"
                                       :completed-at (utils/now)})
        (throw e))))

  (recommend-workflows [_this project live-workflows]
    (if (empty? live-workflows)
      []
      (try
        (let [prompt   (build-classification-prompt project live-workflows)
              response (post-anthropic-sync api-key
                                            {:model      default-model
                                             :max_tokens 1024
                                             :system     classifier-system-prompt
                                             :messages   [{:role "user" :content prompt}]})
              text     (extract-text response)]
          (parse-recommendations text live-workflows))
        (catch Exception e
          (log/errorf "Workflow recommendation failed: %s" e)
          [])))))

(defn make-llm-executor [{:keys [api-key]}]
  (->LLMExecutor api-key))
