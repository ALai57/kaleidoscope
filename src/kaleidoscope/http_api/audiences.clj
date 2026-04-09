(ns kaleidoscope.http-api.audiences
  (:require [kaleidoscope.api.articles :as articles-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [ring.util.http-response :refer [ok not-found]]
            [taoensso.timbre :as log]))

(def reitit-audiences-routes
  ["/article-audiences" {:tags     ["audiences"]
                         :security [{:andrewslai-pkce ["roles" "profile"]}]
                         ;; For testing only - this is a mechanism to always get results from a particular
                         ;; host URL.
                         ;;
                         ;;:host      "andrewslai.localhost"
                         }
   ["" {:get {:summary    "Retrieve all audiences"
              :responses  (merge hu/openapi-401
                                 {200 {:description "A collection of all audiences"
                                       :content     {"application/json"
                                                     {:schema [:any]}}}})
              :parameters {:query [:map {:closed true}
                                   [:article-id {:optional true} :int]]}
              :handler    (fn [{:keys [components parameters] :as request}]
                            (log/debugf "Received params %s" parameters)
                            (let [query-params (:query parameters)
                                  audiences    (->> {:hostname (hu/get-host request)}
                                                    (merge query-params)
                                                    (articles-api/get-article-audiences (:database components) ))]
                              (if (empty? audiences)
                                (not-found)
                                (ok audiences))))}
        :put {:summary   "A collection of all audiences"
              :responses (merge hu/openapi-401
                                {200 {:description "The group that was created"
                                      :content     {"application/json"
                                                    {:schema [:any]}}}})
              :parameters {:body [:map
                                   [:article-id :int]
                                   [:group-id :uuid]]}
              :handler   (fn [{:keys [components parameters] :as request}]
                           (let [{:keys [article-id group-id]} (:body parameters)]
                             (ok (articles-api/add-audience-to-article! (:database components)
                                                                        {:id       article-id
                                                                         :hostname (hu/get-host request)}
                                                                        {:id group-id}))))}}]

   ["/:audience-id" {:delete {:summary    "Delete an audience"
                              :responses  (merge hu/openapi-401
                                                 {200 {:description "Success deleting the group"
                                                       :content     {"application/json"
                                                                     {:schema [:any]}}}})
                              :parameters {:path {:audience-id :uuid}}
                              :handler    (fn [{:keys [components parameters] :as request}]
                                            (let [{:keys [audience-id]} (:path parameters)]
                                              (ok (articles-api/delete-article-audience! (:database components) audience-id))))}}]])
