(ns kaleidoscope.http-api.projects
  (:require [kaleidoscope.api.authentication :as oidc]
            [kaleidoscope.api.projects :as projects-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [kaleidoscope.persistence.projects :as persistence]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.scoring.llm-scorer :as llm-scorer]
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
                           (let [user-id (oidc/get-verified-email (:identity request))]
                             (ok (projects-api/get-projects (:database components) user-id))))}

        :post {:summary   "Create a project (triggers scoring against default definitions)"
               :responses (merge hu/openapi-401
                                  {200 {:description "The created project with scores"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :handler   (fn [{:keys [components body-params] :as request}]
                            (let [user-id (oidc/get-verified-email (:identity request))]
                              (try
                                (ok (projects-api/create-project!
                                     (:database components)
                                     (:scorer components)
                                     user-id
                                     body-params))
                                (catch Exception e
                                  (log/errorf "Error creating project: %s" e)
                                  (bad-request {:error (.getMessage e)})))))}}]

   ;; --- Individual project endpoints ---
   ["/:project-id"
    {:parameters {:path {:project-id string?}}}

    ["" {:get {:summary   "Get a project with all latest score runs"
               :responses (merge hu/openapi-401
                                  hu/openapi-404
                                  {200 {:description "The project"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :handler   (fn [{:keys [components path-params] :as request}]
                            (let [user-id    (oidc/get-verified-email (:identity request))
                                  project-id (parse-uuid (:project-id path-params))]
                              (if-let [project (projects-api/get-project
                                                (:database components) project-id user-id)]
                                (ok project)
                                (not-found {:reason "Project not found"}))))}

         :put {:summary   "Update a project"
               :responses (merge hu/openapi-401
                                  hu/openapi-404
                                  {200 {:description "The updated project"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :handler   (fn [{:keys [components body-params path-params] :as request}]
                            (let [user-id    (oidc/get-verified-email (:identity request))
                                  project-id (parse-uuid (:project-id path-params))]
                              (if-let [project (projects-api/update-project!
                                                (:database components) project-id user-id body-params)]
                                (ok project)
                                (not-found {:reason "Project not found"}))))}

         :delete {:summary   "Delete a project"
                  :responses (merge hu/openapi-401
                                     hu/openapi-404
                                     {204 {:description "Deleted"}})
                  :handler   (fn [{:keys [components path-params] :as request}]
                               (let [user-id    (oidc/get-verified-email (:identity request))
                                     project-id (parse-uuid (:project-id path-params))]
                                 (if (projects-api/delete-project!
                                      (:database components) project-id user-id)
                                   {:status 204}
                                   (not-found {:reason "Project not found"}))))}}]

    ;; --- Notes ---
    ["/notes"
     ["" {:get {:summary   "List notes for a project"
                :handler   (fn [{:keys [components path-params] :as request}]
                             (let [user-id    (oidc/get-verified-email (:identity request))
                                   project-id (parse-uuid (:project-id path-params))]
                               (if-let [notes (projects-api/get-notes
                                               (:database components) project-id user-id)]
                                 (ok notes)
                                 (not-found {:reason "Project not found"}))))}

          :post {:summary   "Add a note (text or voice)"
                 :handler   (fn [{:keys [components body-params path-params] :as request}]
                              (let [user-id    (oidc/get-verified-email (:identity request))
                                    project-id (parse-uuid (:project-id path-params))
                                    source     (get body-params :source "text")]
                                ;; Voice source: body-params should contain pre-transcribed content.
                                ;; Whisper integration can be added here by replacing :content
                                ;; with the transcription result before persisting.
                                (if-let [note (projects-api/create-note!
                                               (:database components)
                                               project-id user-id
                                               {:content (get body-params :content "")
                                                :source  source})]
                                  (ok note)
                                  (not-found {:reason "Project not found"}))))}}]]

    ;; --- Scoring ---
    ["/scores"
     ["" {:get {:summary   "Get latest score run per definition"
                :handler   (fn [{:keys [components path-params] :as request}]
                             (let [user-id    (oidc/get-verified-email (:identity request))
                                   project-id (parse-uuid (:project-id path-params))]
                               (ok (or (persistence/get-latest-score-runs
                                        (:database components) project-id)
                                       []))))}

          :post {:summary   "Trigger scoring (pass definition_ids to score specific definitions)"
                 :handler   (fn [{:keys [components body-params path-params] :as request}]
                              (let [user-id        (oidc/get-verified-email (:identity request))
                                    project-id     (parse-uuid (:project-id path-params))
                                    definition-ids (when-let [ids (:definition-ids body-params)]
                                                     (mapv parse-uuid ids))]
                                (if-let [project (projects-api/score-project!
                                                  (:database components)
                                                  (:scorer components)
                                                  project-id user-id definition-ids)]
                                  (ok project)
                                  (not-found {:reason "Project not found"}))))}}]

     ["/history" {:get {:summary   "Get all score runs for all versions and definitions"
                        :handler   (fn [{:keys [components path-params] :as request}]
                                     (let [user-id    (oidc/get-verified-email (:identity request))
                                           project-id (parse-uuid (:project-id path-params))]
                                       (ok (or (projects-api/get-score-history
                                                (:database components) project-id user-id)
                                               []))))}}]

     ["/section-questions"
      {:post {:summary   "Generate guiding questions for a score dimension"
              :handler   (fn [{:keys [components body-params path-params] :as request}]
                           (let [user-id    (oidc/get-verified-email (:identity request))
                                 project-id (parse-uuid (:project-id path-params))
                                 db         (:database components)
                                 scorer     (:scorer components)
                                 params     {:dimension-name      (:dimension-name body-params)
                                             :rationale           (:rationale body-params)
                                             :score-definition-name (:score-definition-name body-params)}]
                             (if-let [result (projects-api/get-section-questions
                                              db project-id user-id
                                              (fn []
                                                (if-let [api-key (:api-key scorer)]
                                                  (llm-scorer/generate-section-questions api-key params)
                                                  {:questions []})))]
                               (ok result)
                               (not-found {:reason "Project not found"}))))}}]]

    ;; --- Conversations (SSE) ---
    ["/conversations/:agent"
     {:parameters {:path {:project-id string? :agent string?}}}

     ["" {:get {:summary   "Get conversation history"
                :handler   (fn [{:keys [components path-params] :as request}]
                             (let [user-id    (oidc/get-verified-email (:identity request))
                                   project-id (parse-uuid (:project-id path-params))
                                   agent-type (:agent path-params)]
                               (ok (or (projects-api/get-conversation
                                        (:database components)
                                        project-id user-id agent-type)
                                       []))))}

          :post {:summary   "Send a message — returns SSE stream of tokens"
                 :handler   (fn [{:keys [components body-params path-params] :as request}]
                              (let [user-id    (oidc/get-verified-email (:identity request))
                                    project-id (parse-uuid (:project-id path-params))
                                    agent-type (:agent path-params)
                                    user-msg   (get body-params :message "")
                                    db         (:database components)
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
                :handler   (fn [{:keys [components path-params] :as request}]
                             (let [user-id    (oidc/get-verified-email (:identity request))
                                   project-id (parse-uuid (:project-id path-params))]
                               (ok (or (projects-api/get-skill-tree
                                        (:database components) project-id user-id)
                                       []))))}}]

     ["/generate" {:post {:summary   "Generate a skill tree using the Eng Lead agent"
                          :handler   (fn [{:keys [components path-params] :as request}]
                                       (let [user-id    (oidc/get-verified-email (:identity request))
                                             project-id (parse-uuid (:project-id path-params))
                                             db         (:database components)
                                             scorer     (:scorer components)]
                                         (if-let [project (persistence/get-project db project-id user-id)]
                                           (ok (projects-api/generate-skills!
                                                db project-id user-id
                                                (fn []
                                                  (if-let [api-key (:api-key scorer)]
                                                    (llm-scorer/generate-skills api-key project)
                                                    []))))
                                           (not-found {:reason "Project not found"}))))}}]

     ["/:skill-id"
      {:parameters {:path {:skill-id string?}}}
      ["" {:put {:summary   "Update skill mastery status"
                 :handler   (fn [{:keys [components body-params path-params] :as request}]
                              (let [user-id    (oidc/get-verified-email (:identity request))
                                    project-id (parse-uuid (:project-id path-params))
                                    skill-id   (parse-uuid (:skill-id path-params))]
                                (if-let [tree (projects-api/update-skill!
                                               (:database components)
                                               project-id user-id skill-id body-params)]
                                  (ok tree)
                                  (not-found {:reason "Project or skill not found"}))))}}]]]]])
