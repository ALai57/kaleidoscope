(ns kaleidoscope.http-api.projects
  (:require [clojure.string :as str]
            [kaleidoscope.api.projects :as projects-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.persistence.projects :as persistence]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.scoring.llm-scorer :as llm-scorer]
            [kaleidoscope.utils.local-files :as local-files]
            [ring.util.http-response :refer [bad-request not-found ok]]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Projects routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def reitit-projects-routes
  ["/projects"
   {:tags     ["projects"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]}

   ;; --- Collection endpoints ---
   ["" {:get {:summary   "List projects for the authenticated user (with latest scores)"
              :responses (merge hu/openapi-401
                                {200 {:description "A collection of projects"
                                      :content     {"application/json"
                                                    {:schema [:any]}}}})
              :handler   (fn [{:keys [components] :as request}]
                           (ok (projects-api/get-projects (tenant/scope (:database components) (hu/tenant-hostname request))
                                                          (:user-id (:identity request)))))}

        :post {:summary    "Create a project (triggers scoring against default definitions)"
               ;; Creation itself triggers scoring against every default
               ;; definition plus starts the default workflow — several
               ;; Claude calls per request — so this is rate limited like
               ;; the other LLM-triggering routes, not just size-capped.
               :rate-limit {:max-requests 5 :window-ms 60000}
               :responses  (merge hu/openapi-401
                                  {200 {:description "The created project with scores"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :parameters {:body [:map
                                   [:title [:string {:min 1 :max 200}]]
                                   [:description {:optional true} [:string {:max 20000}]]]}
               :handler    (fn [{:keys [components parameters] :as request}]
                            (try
                              (ok (projects-api/create-project!
                                   (tenant/scope (:database components) (hu/tenant-hostname request))
                                   (:scorer components)
                                   (:workflow-executor components)
                                   (:user-id (:identity request))
                                   (:body parameters)))
                              (catch Exception e
                                (log/errorf "Error creating project: %s" e)
                                (bad-request {:error (.getMessage e)}))))}}]

   ;; --- Individual project endpoints ---
   ["/:project-id"
    {:parameters {:path {:project-id :uuid}}}

    ["" {:get {:summary   "Get a project with all latest score runs"
               :responses (merge hu/openapi-401
                                  hu/openapi-404
                                  {200 {:description "The project"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :handler   (fn [{:keys [components parameters] :as request}]
                            (let [project-id (:project-id (:path parameters))]
                              (if-let [project (projects-api/get-project
                                                (tenant/scope (:database components) (hu/tenant-hostname request)) project-id
                                                (:user-id (:identity request)))]
                                (ok project)
                                (not-found {:reason "Project not found"}))))}

         :put {:summary    "Update a project"
               ;; Mirrors the caps on POST /projects above - update-project!
               ;; accepts title/description raw with no length check of its
               ;; own, and that description is spliced into every scoring/
               ;; workflow prompt. Without this, PUT was a second, uncapped
               ;; path to the exact field POST already caps.
               :responses  (merge hu/openapi-401
                                  hu/openapi-404
                                  {200 {:description "The updated project"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :parameters {:body [:map
                                   [:title {:optional true} [:string {:min 1 :max 200}]]
                                   [:description {:optional true} [:string {:max 20000}]]
                                   [:status {:optional true} [:string {:max 50}]]]}
               :handler   (fn [{:keys [components parameters] :as request}]
                            (let [project-id (:project-id (:path parameters))]
                              (if-let [project (projects-api/update-project!
                                                (tenant/scope (:database components) (hu/tenant-hostname request)) project-id
                                                (:user-id (:identity request)) (:body parameters))]
                                (ok project)
                                (not-found {:reason "Project not found"}))))}

         :delete {:summary   "Delete a project"
                  :responses (merge hu/openapi-401
                                     hu/openapi-404
                                     {204 {:description "Deleted"}})
                  :handler   (fn [{:keys [components parameters] :as request}]
                               (let [project-id (:project-id (:path parameters))]
                                 (if (projects-api/delete-project!
                                      (tenant/scope (:database components) (hu/tenant-hostname request)) project-id
                                      (:user-id (:identity request)))
                                   {:status 204}
                                   (not-found {:reason "Project not found"}))))}}]

    ;; --- Notes ---
    ["/notes"
     ["" {:get {:summary   "List notes for a project"
                :handler   (fn [{:keys [components parameters] :as request}]
                             (let [project-id (:project-id (:path parameters))]
                               (if-let [notes (projects-api/get-notes
                                               (tenant/scope (:database components) (hu/tenant-hostname request)) project-id
                                               (:user-id (:identity request)))]
                                 (ok notes)
                                 (not-found {:reason "Project not found"}))))}

          :post {:summary   "Add a note (text or voice)"
                 :handler   (fn [{:keys [components body-params parameters] :as request}]
                              (let [project-id (:project-id (:path parameters))
                                    source     (get body-params :source "text")]
                                ;; Voice source: body-params should contain pre-transcribed content.
                                ;; Whisper integration can be added here by replacing :content
                                ;; with the transcription result before persisting.
                                (if-let [note (projects-api/create-note!
                                               (tenant/scope (:database components) (hu/tenant-hostname request))
                                               project-id (:user-id (:identity request))
                                               {:content (get body-params :content "")
                                                :source  source})]
                                  (ok note)
                                  (not-found {:reason "Project not found"}))))}}]]

    ;; --- Scoring ---
    ["/scores"
     ["" {:get {:summary   "Get latest score run per definition"
                :handler   (fn [{:keys [components parameters] :as request}]
                             (let [project-id (:project-id (:path parameters))]
                               (if-let [scores (projects-api/get-latest-scores
                                                (tenant/scope (:database components) (hu/tenant-hostname request)) project-id
                                                (:user-id (:identity request)))]
                                 (ok scores)
                                 (not-found {:reason "Project not found"}))))}

          :post {:summary    "Trigger scoring (pass definition_ids to score specific definitions)"
                 ;; score-project! runs one synchronous Claude call per
                 ;; definition-id on the request thread (api/projects.clj
                 ;; score-project!) — capping the vector bounds worst-case
                 ;; cost/latency of a single request; the rate limit bounds
                 ;; how often that worst case can be repeated.
                 :rate-limit {:max-requests 5 :window-ms 60000}
                 :parameters {:body [:map
                                     [:definition-ids {:optional true} [:vector {:max 20} :uuid]]]}
                 :handler    (fn [{:keys [components parameters] :as request}]
                               (let [project-id     (:project-id (:path parameters))
                                     definition-ids (:definition-ids (:body parameters))]
                                 (if-let [project (projects-api/score-project!
                                                   (tenant/scope (:database components) (hu/tenant-hostname request))
                                                   (:scorer components)
                                                   project-id (:user-id (:identity request)) definition-ids)]
                                   (ok project)
                                   (not-found {:reason "Project not found"}))))}}]

     ["/history" {:get {:summary   "Get all score runs for all versions and definitions"
                        :handler   (fn [{:keys [components parameters] :as request}]
                                     (let [project-id (:project-id (:path parameters))]
                                       (ok (or (projects-api/get-score-history
                                                (tenant/scope (:database components) (hu/tenant-hostname request)) project-id
                                                (:user-id (:identity request)))
                                               []))))}}]

     ["/section-questions"
      {:post {:summary    "Generate guiding questions for a score dimension"
              :rate-limit {:max-requests 10 :window-ms 60000}
              :handler   (fn [{:keys [components body-params parameters] :as request}]
                           (let [project-id (:project-id (:path parameters))
                                 db         (tenant/scope (:database components) (hu/tenant-hostname request))
                                 scorer     (:scorer components)
                                 params     {:dimension-name        (:dimension-name body-params)
                                             :rationale             (:rationale body-params)
                                             :score-definition-name (:score-definition-name body-params)}]
                             (if-let [result (projects-api/get-section-questions
                                              db project-id (:user-id (:identity request))
                                              (fn []
                                                (if-let [api-key (:api-key scorer)]
                                                  (llm-scorer/generate-section-questions api-key params)
                                                  {:questions []})))]
                               (ok result)
                               (not-found {:reason "Project not found"}))))}}]]

    ;; --- Conversations (SSE) ---
    ["/conversations/:agent"
     {:parameters {:path {:project-id :uuid :agent string?}}}

     ["" {:get {:summary   "Get conversation history"
                :handler   (fn [{:keys [components parameters] :as request}]
                             (let [project-id (:project-id (:path parameters))
                                   agent-type (:agent (:path parameters))]
                               (ok (or (projects-api/get-conversation
                                        (tenant/scope (:database components) (hu/tenant-hostname request))
                                        project-id (:user-id (:identity request)) agent-type)
                                       []))))}

          :post {:summary    "Send a message — returns SSE stream of tokens"
                 :rate-limit {:max-requests 10 :window-ms 60000}
                 :parameters {:body [:map [:message {:optional true} [:string {:max 8000}]]]}
                 :handler   (fn [{:keys [components parameters] :as request}]
                              (let [user-id    (:user-id (:identity request))
                                    project-id (:project-id (:path parameters))
                                    agent-type (:agent (:path parameters))
                                    user-msg   (get (:body parameters) :message "")
                                    db         (tenant/scope (:database components) (hu/tenant-hostname request))
                                    scorer     (:scorer components)]
                                (if-not (persistence/get-project db project-id user-id)
                                  (not-found {:reason "Project not found"})
                                  (let [history    (projects-api/get-conversation
                                                    db project-id user-id agent-type)
                                        ;; Build messages array for Anthropic
                                        messages   (-> (mapv (fn [m]
                                                               {:role    (:role m)
                                                                :content (:content m)})
                                                             history)
                                                       (conj {:role "user" :content user-msg}))
                                        system-prompt (agents/get-system-prompt agent-type)]
                                    (if-let [api-key (:api-key scorer)]
                                      ;; LLM scorer — stream tokens via SSE
                                      (hu/sse-response
                                       (fn [output-stream]
                                         (let [full-response (llm-scorer/stream-conversation!
                                                               api-key
                                                               system-prompt
                                                               messages
                                                               output-stream)]
                                           ;; Persist the completed turn after streaming
                                           (projects-api/save-conversation-turn!
                                            db project-id user-id agent-type
                                            user-msg full-response))))
                                      ;; Mock scorer — return stub response as single SSE event
                                      (hu/sse-response
                                       (fn [output-stream]
                                         (let [writer (java.io.OutputStreamWriter. output-stream "UTF-8")
                                               reply  "I'm a mock assistant. Configure KALEIDOSCOPE_SCORER_TYPE=llm and ANTHROPIC_API_KEY to enable real responses."]
                                           (hu/write-sse-event! writer {:token reply})
                                           (.write writer "data: [DONE]\n\n")
                                           (.flush writer)
                                           (.close writer)
                                           (projects-api/save-conversation-turn!
                                            db project-id user-id agent-type
                                            user-msg reply)))))))))}}]]

    ;; --- Skills ---
    ["/skills"
     ["" {:get {:summary   "Get the skill tree for a project"
                :handler   (fn [{:keys [components parameters] :as request}]
                             (let [project-id (:project-id (:path parameters))]
                               (ok (or (projects-api/get-skill-tree
                                        (tenant/scope (:database components) (hu/tenant-hostname request)) project-id
                                        (:user-id (:identity request)))
                                       []))))}}]

     ["/generate" {:post {:summary    "Generate a skill tree using the Eng Lead agent"
                          :rate-limit {:max-requests 5 :window-ms 60000}
                          :handler   (fn [{:keys [components parameters] :as request}]
                                       (let [project-id (:project-id (:path parameters))
                                             db         (tenant/scope (:database components) (hu/tenant-hostname request))
                                             scorer     (:scorer components)]
                                         (if-let [project (persistence/get-project db project-id
                                                                                    (:user-id (:identity request)))]
                                           (ok (projects-api/generate-skills!
                                                db project-id (:user-id (:identity request))
                                                (fn []
                                                  (if-let [api-key (:api-key scorer)]
                                                    (llm-scorer/generate-skills api-key project)
                                                    []))))
                                           (not-found {:reason "Project not found"}))))}}]

     ["/:skill-id"
      {:parameters {:path {:project-id :uuid :skill-id :uuid}}}
      ["" {:put {:summary   "Update skill mastery status"
                 :handler   (fn [{:keys [components body-params parameters] :as request}]
                              (let [project-id (:project-id (:path parameters))
                                    skill-id   (:skill-id (:path parameters))]
                                (if-let [tree (projects-api/update-skill!
                                               (tenant/scope (:database components) (hu/tenant-hostname request))
                                               project-id (:user-id (:identity request)) skill-id body-params)]
                                  (ok tree)
                                  (not-found {:reason "Project or skill not found"}))))}}]]]

    ;; --- Local paths (code context override) ---
    ["/local-paths"
     ["" {:put {:summary   "Set explicit local paths for Engineering Reviewer code context"
                :responses (merge hu/openapi-401
                                   hu/openapi-404
                                   {200 {:description "The updated project"
                                         :content     {"application/json" {:schema [:any]}}}})
                :handler   (fn [{:keys [components body-params parameters] :as request}]
                             (let [project-id (:project-id (:path parameters))
                                   paths      (vec (filter (complement str/blank?)
                                                           (map str/trim
                                                                (or (:local-paths body-params) []))))]
                               (if (not-every? local-files/path-allowed? paths)
                                 (bad-request {:reason "One or more paths are not under an allowed root"})
                                 (if-let [project (projects-api/update-project!
                                                    (tenant/scope (:database components) (hu/tenant-hostname request))
                                                    project-id (:user-id (:identity request))
                                                    {:local-paths paths})]
                                   (ok project)
                                   (not-found {:reason "Project not found"})))))}}]]

    ;; --- Briefs ---
    ["/briefs"
     ["" {:get {:summary "List all brief versions for a project"
                :handler (fn [{:keys [components parameters] :as request}]
                           (let [project-id (:project-id (:path parameters))]
                             (if-let [briefs (projects-api/get-all-briefs
                                              (tenant/scope (:database components) (hu/tenant-hostname request)) project-id
                                              (:user-id (:identity request)))]
                               (ok briefs)
                               (not-found {:reason "Project not found"}))))}}]

     ["/latest"
      {:get {:summary "Get the latest brief version for a project"
             :handler (fn [{:keys [components parameters] :as request}]
                        (let [project-id (:project-id (:path parameters))]
                          (if-let [brief (projects-api/get-latest-brief
                                          (tenant/scope (:database components) (hu/tenant-hostname request)) project-id
                                          (:user-id (:identity request)))]
                            (ok brief)
                            (not-found {:reason "No brief found for this project"}))))}}]

     ["/:version"
      {:parameters {:path {:project-id :uuid :version string?}}
       :get {:summary "Get a specific brief version"
             :handler (fn [{:keys [components parameters] :as request}]
                        (let [project-id (:project-id (:path parameters))
                              version    (parse-long (:version (:path parameters)))]
                          (if-let [brief (projects-api/get-brief-by-version
                                          (tenant/scope (:database components) (hu/tenant-hostname request)) project-id
                                          (:user-id (:identity request)) version)]
                            (ok brief)
                            (not-found {:reason "Brief version not found"}))))}}]]]])
