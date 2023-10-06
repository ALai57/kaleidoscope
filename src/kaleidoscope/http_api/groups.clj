(ns kaleidoscope.http-api.groups
  (:require [kaleidoscope.api.authentication :as oidc]
            [kaleidoscope.api.groups :as groups-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [ring.util.http-response :refer [no-content ok unauthorized]]
            [taoensso.timbre :as log]))

(def reitit-groups-routes
  ["/groups" {:tags     ["groups"]
              :security [{:andrewslai-pkce ["roles" "profile"]}]
              ;; For testing only - this is a mechanism to always get results from a particular
              ;; host URL.
              ;;
              ;;:host      "andrewslai.localhost"
              }
   ["" {:get {:summary   "Retrieve groups the user owns"
              :responses (merge hu/openapi-401
                                {200 {:description "A collection of groups the user owns"
                                      :content     {"application/json"
                                                    {:schema [:any]}}}})

              :handler (fn [{:keys [components] :as request}]
                         (ok (groups-api/get-users-groups (:database components)
                                                          (oidc/get-user-id (:identity request)))))}
        :post {:summary   "Create a group"
               :responses (merge hu/openapi-401
                                 {200 {:description "The group that was created"
                                       :content     {"application/json"
                                                     {:schema [:any]}}}})
               :handler   (fn [{:keys [components body-params] :as request}]
                            (try
                              (log/info "Creating group!" body-params)
                              (let [group (assoc body-params
                                                 :owner-id (oidc/get-user-id (:identity request)))]
                                (ok (doto (groups-api/create-group! (:database components) group)
                                      log/info)))
                              (catch Exception e
                                (log/error "Caught exception " e))))}}]

   ["/:group-id" {:put {:summary    "Create a group"
                        :responses  (merge hu/openapi-401
                                           {200 {:description "The group that was created"
                                                 :content     {"application/json"
                                                               {:schema [:any]}}}})
                        :parameters {:path {:group-id string?}}
                        :handler    (fn [{:keys [components body-params path-params] :as request}]
                                      (try
                                        (let [{:keys [group-id]} path-params
                                              _                  (log/info "Creating group!" group-id body-params)
                                              group              (assoc body-params
                                                                        :id       group-id
                                                                        :owner-id (oidc/get-user-id (:identity request)))]
                                          (ok (doto (groups-api/create-group! (:database components) group)
                                                log/info)))
                                        (catch Exception e
                                          (log/error "Caught exception " e))))}
                  :delete {:summary    "Delete a group"
                           :responses  (merge hu/openapi-401
                                              {200 {:description "Success deleting the group"
                                                    :content     {"application/json"
                                                                  {:schema [:any]}}}})
                           :parameters {:path {:group-id string?}}
                           :handler    (fn [{:keys [components path-params] :as request}]
                                         (try
                                           (let [{:keys [group-id]} path-params
                                                 requester-id       (oidc/get-user-id (:identity request))]
                                             (log/infof "User %s attempting to delete group %s!" requester-id group-id)
                                             (if-let [result (groups-api/delete-group! (:database components) requester-id group-id)]
                                               (no-content)
                                               (unauthorized)))
                                           (catch Exception e
                                             (log/error "Caught exception " e))))}}]
   ["/:group-id/members" {:post {:summary    "Add a member"
                                 :responses  (merge hu/openapi-401
                                                    {200 {:description "The member that was added"
                                                          :content     {"application/json"
                                                                        {:schema [:any]}}}})
                                 :parameters {:path {:group-id string?}}
                                 :handler    (fn [{:keys [components body-params path-params] :as request}]
                                               (try
                                                 (log/info "Adding member!" body-params)
                                                 (let [{:keys [group-id]} path-params
                                                       requester-id       (oidc/get-user-id (:identity request))
                                                       member             body-params]
                                                   (ok (doto (groups-api/add-users-to-group! (:database components) requester-id group-id member)
                                                         log/info)))
                                                 (catch Exception e
                                                   (log/error "Caught exception " e))))}}]
   ["/:group-id/members/:membership-id" {:delete {:summary    "Delete a member from a group"
                                                  :responses  (merge hu/openapi-401
                                                                     {204 {:description "Success deleting the member"
                                                                           :content     {"application/json"
                                                                                         {:schema [:any]}}}})
                                                  :parameters {:path {:group-id      string?
                                                                      :membership-id string?}}
                                                  :handler    (fn [{:keys [components path-params] :as request}]
                                                                (try
                                                                  (let [{:keys [group-id membership-id]} path-params
                                                                        requester-id                     (oidc/get-user-id (:identity request))]
                                                                    (log/infof "User %s attempting to remove member %s from group %s!" requester-id membership-id group-id)
                                                                    (if-let [result (groups-api/remove-user-from-group! (:database components) requester-id group-id membership-id)]
                                                                      (no-content)
                                                                      (unauthorized)))
                                                                  (catch Exception e
                                                                    (log/error "Caught exception " e))))}}]])
