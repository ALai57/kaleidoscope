(ns kaleidoscope.http-api.interests
  (:require [kaleidoscope.api.curation :as curation]
            [kaleidoscope.api.interests :as interests-api]
            [ring.util.http-response :refer [not-found ok]]))

(def TasteProfile
  [:map
   [:keywords {:optional true} [:vector {:max 50} [:string {:max 100}]]]
   [:formats {:optional true} [:vector {:max 8}
                               [:enum "podcast" "article" "show" "video"
                                "book" "paper" "newsletter" "course"]]]
   [:lengths {:optional true} [:vector {:max 10} [:string {:max 100}]]]
   [:trusted-sources {:optional true} [:vector {:max 50} [:string {:max 200}]]]
   ;; The explore/exploit dial is a *user* control with hard bounds — reject,
   ;; don't clamp, at the boundary so a bad client can't silently mis-set it.
   [:novelty-ratio {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:cadence {:optional true} [:string {:max 50}]]
   [:refinements {:optional true} [:vector {:max 50} [:string {:max 2000}]]]])

(def InterestRequest
  [:map
   [:intent [:string {:min 1 :max 5000}]]
   [:taste-profile {:optional true} TasteProfile]])

(def InterestUpdateRequest
  [:map
   [:intent {:optional true} [:string {:min 1 :max 5000}]]
   [:taste-profile {:optional true} TasteProfile]])

(def CurationRequest
  [:map
   [:scrutiny {:optional true} [:enum "quick" "standard" "rigorous"]]
   [:shelf-size {:optional true} [:int {:min 1 :max 24}]]])

(def RecommendationStatusRequest
  [:map
   [:status [:enum "shelved" "queued" "archived"]]])

(def RespondRequest
  [:map
   [:answers [:vector {:max 5} [:string {:max 2000}]]]])

(def reitit-interests-routes
  ["/interests"
   {:tags     ["interests"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]}

   ["" {:get {:summary "List interests for the authenticated user"
              :handler (fn [{:keys [components] :as request}]
                         (ok (interests-api/get-interests (:database components)
                                                          (:user-id (:identity request)))))}

        :post {:summary    "Create an interest (free-text intent + optional taste profile)"
               :rate-limit {:max-requests 10 :window-ms 60000}
               :parameters {:body InterestRequest}
               :handler    (fn [{:keys [components parameters] :as request}]
                             (ok (interests-api/create-interest!
                                  (:database components)
                                  (:user-id (:identity request))
                                  (:body parameters))))}}]

   ["/:interest-id"
    {:parameters {:path {:interest-id :uuid}}}

    ["" {:get {:summary "Get an interest with its taste profile"
               :handler (fn [{:keys [components parameters] :as request}]
                          (let [user-id     (:user-id (:identity request))
                                interest-id (:interest-id (:path parameters))]
                            (if-let [interest (interests-api/get-interest
                                               (:database components) user-id interest-id)]
                              (ok interest)
                              (not-found {:reason "Interest not found"}))))}

         :put {:summary    "Update intent and/or taste profile (partial edits merge)"
               :parameters {:body InterestUpdateRequest}
               :handler    (fn [{:keys [components parameters] :as request}]
                             (let [user-id     (:user-id (:identity request))
                                   interest-id (:interest-id (:path parameters))]
                               (if-let [interest (interests-api/update-interest!
                                                  (:database components) user-id interest-id
                                                  (:body parameters))]
                                 (ok interest)
                                 (not-found {:reason "Interest not found"}))))}

         :delete {:summary "Delete an interest (its shelf and curation runs go with it)"
                  :handler (fn [{:keys [components parameters] :as request}]
                             (let [user-id     (:user-id (:identity request))
                                   interest-id (:interest-id (:path parameters))]
                               (if (interests-api/delete-interest!
                                    (:database components) user-id interest-id)
                                 {:status 204}
                                 (not-found {:reason "Interest not found"}))))}}]

    ["/recommendations"
     ["" {:get {:summary    "Read the interest's shelf (optionally filtered by status/kind)"
                :parameters {:query [:map
                                     [:status {:optional true} [:enum "shelved" "queued" "archived"]]
                                     [:kind {:optional true} [:string {:max 50}]]]}
                :handler    (fn [{:keys [components parameters] :as request}]
                              (let [user-id     (:user-id (:identity request))
                                    interest-id (:interest-id (:path parameters))]
                                (if-let [shelf (interests-api/get-shelf
                                                (:database components) user-id interest-id
                                                (:query parameters))]
                                  (ok shelf)
                                  (not-found {:reason "Interest not found"}))))}}]

     ["/:recommendation-id"
      {:parameters {:path {:interest-id :uuid :recommendation-id :uuid}}
       :put {:summary    "Update a recommendation's status (shelve / queue / archive)"
             :parameters {:body RecommendationStatusRequest}
             :handler    (fn [{:keys [components parameters] :as request}]
                           (let [user-id           (:user-id (:identity request))
                                 interest-id       (:interest-id (:path parameters))
                                 recommendation-id (:recommendation-id (:path parameters))]
                             (if-let [rec (interests-api/update-recommendation-status!
                                           (:database components) user-id interest-id
                                           recommendation-id (:status (:body parameters)))]
                               (ok rec)
                               (not-found {:reason "Recommendation not found"}))))}}]]

    ["/curate"
     {:post {:summary    "Run the curation workflow and refresh this interest's shelf"
             ;; One curation run is at least one Claude call in production —
             ;; rate limited like the other LLM-triggering routes.
             :rate-limit {:max-requests 5 :window-ms 60000}
             :parameters {:body CurationRequest}
             :handler    (fn [{:keys [components parameters] :as request}]
                           (let [user-id     (:user-id (:identity request))
                                 interest-id (:interest-id (:path parameters))]
                             (if-let [result (curation/run-curation!
                                              (:database components)
                                              (:workflow-executor components)
                                              user-id interest-id
                                              (:body parameters))]
                               (ok result)
                               (not-found {:reason "Interest not found"}))))}}]

    ["/curation-runs/:run-id/steps/:step-run-id/respond"
     {:parameters {:path {:interest-id :uuid :run-id :uuid :step-run-id :uuid}}
      :post {:summary    "Answer refinement questions; folds answers into the taste profile and resumes curation"
             :rate-limit {:max-requests 10 :window-ms 60000}
             :parameters {:body RespondRequest}
             :handler    (fn [{:keys [components parameters] :as request}]
                           (let [user-id     (:user-id (:identity request))
                                 {:keys [interest-id run-id step-run-id]} (:path parameters)]
                             (if-let [result (curation/respond-to-curation-step!
                                              (:database components)
                                              (:workflow-executor components)
                                              user-id interest-id run-id step-run-id
                                              (:answers (:body parameters)))]
                               (ok result)
                               (not-found {:reason "Run or step not found, or step not awaiting input"}))))}}]]])
